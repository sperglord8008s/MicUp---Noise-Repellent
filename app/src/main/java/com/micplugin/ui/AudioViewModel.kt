package com.micplugin.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micplugin.audio.AudioEngine
import com.micplugin.audio.AudioLevels
import com.micplugin.audio.NoiseRepellentStatus
import com.micplugin.audio.SpectrumFrame
import com.micplugin.plugin.*
import com.micplugin.preset.EffectState
import com.micplugin.preset.Preset
import com.micplugin.preset.PresetManager
import com.micplugin.plugin.PluginPathPrefs
import com.micplugin.service.ShizukuManager
import com.micplugin.service.AudioProcessingService
import com.micplugin.service.AudioRouteDiagnostics
import com.micplugin.service.SoftwareLoopback
import com.micplugin.service.ShizukuState
import com.micplugin.service.VirtualMicService
import com.micplugin.service.VirtualMicTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class GateState(
    val enabled: Boolean = false,
    val thresholdDb: Float = -50f,
    val attackMs: Float = 5f,
    val releaseMs: Float = 150f,
)

data class EqState(
    val enabled: Boolean = false,
    val bands: List<Float> = List(10) { 0f },
)

data class CompState(
    val enabled: Boolean = false,
    val thresholdDb: Float = -18f,
    val ratio: Float = 4f,
    val attackMs: Float = 10f,
    val releaseMs: Float = 100f,
    val makeupDb: Float = 0f,
)

data class ReverbState(
    val enabled: Boolean = false,
    val mix: Float = 0f,
    val roomSize: Float = 0.5f,
    val damping: Float = 0.5f,
)

data class PitchState(
    val enabled: Boolean = false,
    val semitones: Float = 0f,
)

data class NoiseRepellentState(
    val enabled: Boolean = true,
    val learning: Boolean = false,
    val secondsRemaining: Int = 0,
    val reductionDb: Float = 18f,
    val tonalReductionDb: Float = 18f,
    val outputGainDb: Float = 10f,
    val smoothingPercent: Float = 35f,
    val smoothingMode: Int = 3,
    val dfttStrength: Float = 1f,
    val signalProtectionPercent: Float = 70f,
    val whiteningPercent: Float = 0f,
    val suppressionPercent: Float = 100f,
    val aggressiveness: Float = 0.5f,
    val profileOffsetDb: Float = 0f,
    val tonalProfileOffsetDb: Float = 0f,
    val adaptiveNoise: Boolean = false,
    val estimatorMethod: Int = 0,
    val qualityMode: Int = 1,
    val curveEnabled: Boolean = false,
    val curveBandsDb: List<Float> = List(10) { 0f },
    val residualListen: Boolean = false,
    val transientProtection: Boolean = true,
)

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val audioEngine: AudioEngine,
    private val pluginManager: PluginManager,
    private val presetManager: PresetManager,
    private val virtualMicService: VirtualMicService,
    private val shizukuManager: ShizukuManager,
    private val pluginPathPrefs: PluginPathPrefs,
) : ViewModel() {

    val levels:      StateFlow<AudioLevels>    = audioEngine.levels
    val engineStatus = audioEngine.status
    val pluginSlots: StateFlow<List<PluginSlot>> = audioEngine.pluginChain.slots
    val discovered:  StateFlow<List<PluginDescriptor>> = pluginManager.discovered
    val presets:     StateFlow<List<Preset>>   = presetManager.presets
    val virtualMicTier: StateFlow<VirtualMicTier> = virtualMicService.activeTier
    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state
    val noiseRepellentStatus: StateFlow<NoiseRepellentStatus> =
        audioEngine.noiseRepellentStatus
    val spectrum: StateFlow<SpectrumFrame> = audioEngine.spectrum
    val audioRouteStatus = AudioRouteDiagnostics.status
    val audioRouteEvents = AudioRouteDiagnostics.events

    private val _gateState   = MutableStateFlow(GateState())
    private val _eqState     = MutableStateFlow(EqState())
    private val _compState   = MutableStateFlow(CompState())
    private val _reverbState = MutableStateFlow(ReverbState())
    private val _pitchState  = MutableStateFlow(PitchState())
    private val _noiseRepellentState = MutableStateFlow(NoiseRepellentState())
    private val _masterBypass = MutableStateFlow(false)

    val gateState:    StateFlow<GateState>    = _gateState
    val eqState:      StateFlow<EqState>      = _eqState
    val compState:    StateFlow<CompState>    = _compState
    val reverbState:  StateFlow<ReverbState>  = _reverbState
    val pitchState:   StateFlow<PitchState>   = _pitchState
    val noiseRepellentState: StateFlow<NoiseRepellentState> = _noiseRepellentState
    val masterBypass: StateFlow<Boolean>      = _masterBypass

    private val _selectedPreset = MutableStateFlow<Preset?>(null)
    val selectedPreset: StateFlow<Preset?> = _selectedPreset
    private var noiseLearnJob: Job? = null

    init {
        viewModelScope.launch { presetManager.loadAll() }
        viewModelScope.launch { pluginManager.scanAll() }
        // When tier upgrades to Shizuku/Root, tell C++ engine to stop zeroing output
        viewModelScope.launch {
            virtualMicService.activeTier.collect { tier ->
                val inject = tier == VirtualMicTier.SHIZUKU_ADB || tier == VirtualMicTier.ROOT_MAGISK
                audioEngine.setInjectionMode(inject)
            }
        }
    }

    // ── Engine control ─────────────────────────────────────────────────────────
    fun toggleEngine(context: android.content.Context) {
        if (engineStatus.value.isRunning) {
            cancelNoiseLearning()
            com.micplugin.service.AudioProcessingService.stop(context)
        } else {
            com.micplugin.service.AudioProcessingService.start(context)
        }
    }

    // ── Noise Repellent ──────────────────────────────────────────────────────
    fun setNoiseRepellentEnabled(on: Boolean) {
        if (!on && _noiseRepellentState.value.learning) cancelNoiseLearning()
        _noiseRepellentState.value = _noiseRepellentState.value.copy(enabled = on)
        audioEngine.setNoiseRepellentEnabled(on)
    }

    fun toggleNoiseLearning() {
        if (_noiseRepellentState.value.learning) {
            cancelNoiseLearning()
            return
        }
        if (!engineStatus.value.isRunning) return

        noiseLearnJob?.cancel()
        setMasterBypass(false)
        setNoiseRepellentEnabled(true)
        audioEngine.resetNoiseRepellentProfile()
        audioEngine.setNoiseLearning(true)
        _noiseRepellentState.value = _noiseRepellentState.value.copy(
            learning = true,
            secondsRemaining = 3,
        )
        noiseLearnJob = viewModelScope.launch {
            for (remaining in 3 downTo 1) {
                _noiseRepellentState.value = _noiseRepellentState.value.copy(
                    secondsRemaining = remaining,
                )
                delay(1000)
            }
            audioEngine.setNoiseLearning(false)
            _noiseRepellentState.value = _noiseRepellentState.value.copy(
                learning = false,
                secondsRemaining = 0,
            )
        }
    }

    fun cancelNoiseLearning() {
        noiseLearnJob?.cancel()
        noiseLearnJob = null
        audioEngine.setNoiseLearning(false)
        _noiseRepellentState.value = _noiseRepellentState.value.copy(
            learning = false,
            secondsRemaining = 0,
        )
    }

    fun resetNoiseProfile() {
        cancelNoiseLearning()
        audioEngine.resetNoiseRepellentProfile()
    }

    fun setNoiseReduction(db: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(reductionDb = db)
        audioEngine.setNoiseReduction(db)
    }

    fun setNoiseTonalReduction(db: Float) {
        _noiseRepellentState.value =
            _noiseRepellentState.value.copy(tonalReductionDb = db)
        audioEngine.setNoiseTonalReduction(db)
    }

    fun setNoiseOutputGain(db: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(outputGainDb = db)
        audioEngine.setNoiseOutputGain(db)
    }

    fun setNoiseSmoothing(percent: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(smoothingPercent = percent)
        audioEngine.setNoiseSmoothing(percent / 100f)
    }

    fun setNoiseSmoothingMode(mode: Int) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(smoothingMode = mode)
        audioEngine.setNoiseSmoothingMode(mode)
    }

    fun setNoiseDfttStrength(strength: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(dfttStrength = strength)
        audioEngine.setNoiseDfttStrength(strength)
    }

    fun setNoiseSignalProtection(percent: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(
            signalProtectionPercent = percent,
        )
        audioEngine.setNoiseMasking(percent / 100f)
    }

    fun setNoiseWhitening(percent: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(whiteningPercent = percent)
        audioEngine.setNoiseWhitening(percent / 100f)
    }

    fun setNoiseSuppression(percent: Float) {
        _noiseRepellentState.value =
            _noiseRepellentState.value.copy(suppressionPercent = percent)
        audioEngine.setNoiseSuppressionStrength(percent / 100f)
    }

    fun setNoiseAggressiveness(value: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(aggressiveness = value)
        audioEngine.setNoiseAggressiveness(value)
    }

    fun setNoiseProfileOffset(db: Float) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(profileOffsetDb = db)
        audioEngine.setNoiseProfileOffset(db)
    }

    fun setNoiseTonalProfileOffset(db: Float) {
        _noiseRepellentState.value =
            _noiseRepellentState.value.copy(tonalProfileOffsetDb = db)
        audioEngine.setNoiseTonalProfileOffset(db)
    }

    fun setNoiseAdaptive(on: Boolean) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(adaptiveNoise = on)
        audioEngine.setNoiseAdaptive(on)
    }

    fun setNoiseEstimatorMethod(method: Int) {
        cancelNoiseLearning()
        _noiseRepellentState.value =
            _noiseRepellentState.value.copy(estimatorMethod = method)
        audioEngine.setNoiseEstimatorMethod(method)
    }

    fun setNoiseQualityMode(mode: Int) {
        cancelNoiseLearning()
        _noiseRepellentState.value = _noiseRepellentState.value.copy(qualityMode = mode)
        audioEngine.setNoiseQualityMode(mode)
    }

    fun setNoiseCurveEnabled(on: Boolean) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(curveEnabled = on)
        audioEngine.setNoiseCurveEnabled(on)
    }

    fun setNoiseCurveBand(band: Int, db: Float) {
        val bands = _noiseRepellentState.value.curveBandsDb.toMutableList()
        if (band !in bands.indices) return
        bands[band] = db
        _noiseRepellentState.value = _noiseRepellentState.value.copy(curveBandsDb = bands)
        audioEngine.setNoiseCurveBand(band, db)
    }

    fun setSpectrumEnabled(enabled: Boolean) = audioEngine.setSpectrumEnabled(enabled)

    fun setNoiseResidualListen(on: Boolean) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(residualListen = on)
        audioEngine.setNoiseResidualListen(on)
    }

    fun setNoiseTransientProtection(on: Boolean) {
        _noiseRepellentState.value = _noiseRepellentState.value.copy(transientProtection = on)
        audioEngine.setNoiseTransientProtection(on)
    }

    fun setMasterBypass(bypass: Boolean) {
        _masterBypass.value = bypass
        audioEngine.setMasterBypass(bypass)
    }

    // ── Gate ──────────────────────────────────────────────────────────────────
    fun setGateEnabled(on: Boolean) {
        _gateState.value = _gateState.value.copy(enabled = on)
        audioEngine.setGateEnabled(on)
    }
    fun setGateThreshold(db: Float) {
        _gateState.value = _gateState.value.copy(thresholdDb = db)
        audioEngine.setGateThreshold(db)
    }
    fun setGateAttack(ms: Float) {
        _gateState.value = _gateState.value.copy(attackMs = ms)
        audioEngine.setGateAttack(ms)
    }
    fun setGateRelease(ms: Float) {
        _gateState.value = _gateState.value.copy(releaseMs = ms)
        audioEngine.setGateRelease(ms)
    }

    // ── EQ ────────────────────────────────────────────────────────────────────
    fun setEqEnabled(on: Boolean) {
        _eqState.value = _eqState.value.copy(enabled = on)
        audioEngine.setEqEnabled(on)
    }
    fun setEqBand(band: Int, db: Float) {
        val bands = _eqState.value.bands.toMutableList()
        bands[band] = db
        _eqState.value = _eqState.value.copy(bands = bands)
        audioEngine.setEqBand(band, db)
    }

    // ── Compressor ────────────────────────────────────────────────────────────
    fun setCompEnabled(on: Boolean) {
        _compState.value = _compState.value.copy(enabled = on)
        audioEngine.setCompEnabled(on)
    }
    fun setCompThreshold(db: Float) {
        _compState.value = _compState.value.copy(thresholdDb = db)
        audioEngine.setCompThreshold(db)
    }
    fun setCompRatio(ratio: Float) {
        _compState.value = _compState.value.copy(ratio = ratio)
        audioEngine.setCompRatio(ratio)
    }
    fun setCompAttack(ms: Float) {
        _compState.value = _compState.value.copy(attackMs = ms)
        audioEngine.setCompAttack(ms)
    }
    fun setCompRelease(ms: Float) {
        _compState.value = _compState.value.copy(releaseMs = ms)
        audioEngine.setCompRelease(ms)
    }
    fun setCompMakeup(db: Float) {
        _compState.value = _compState.value.copy(makeupDb = db)
        audioEngine.setCompMakeup(db)
    }

    // ── Reverb ────────────────────────────────────────────────────────────────
    fun setReverbEnabled(on: Boolean) {
        _reverbState.value = _reverbState.value.copy(enabled = on)
        audioEngine.setReverbEnabled(on)
    }
    fun setReverbMix(mix: Float) {
        _reverbState.value = _reverbState.value.copy(mix = mix)
        audioEngine.setReverbMix(mix)
    }
    fun setReverbRoom(size: Float) {
        _reverbState.value = _reverbState.value.copy(roomSize = size)
        audioEngine.setReverbRoom(size)
    }
    fun setReverbDamp(d: Float) {
        _reverbState.value = _reverbState.value.copy(damping = d)
        audioEngine.setReverbDamp(d)
    }

    // ── Pitch ─────────────────────────────────────────────────────────────────
    fun setPitchEnabled(on: Boolean) {
        _pitchState.value = _pitchState.value.copy(enabled = on)
        audioEngine.setPitchEnabled(on)
    }
    fun setPitchSemitones(st: Float) {
        _pitchState.value = _pitchState.value.copy(semitones = st)
        audioEngine.setPitchSemitones(st)
    }

    // ── Plugin chain ──────────────────────────────────────────────────────────
    fun addPlugin(descriptor: PluginDescriptor) {
        viewModelScope.launch {
            val handle = if (descriptor.format != PluginFormat.APK)
                audioEngine.loadNativePlugin(descriptor.path, descriptor.format) else 0L
            val slot = PluginSlot(descriptor = descriptor, nativeHandle = handle)
            audioEngine.pluginChain.addPlugin(slot)
        }
    }

    fun removePlugin(id: UUID) {
        val slot = audioEngine.pluginChain.getSlot(id) ?: return
        if (slot.nativeHandle != 0L) audioEngine.unloadNativePlugin(slot.nativeHandle)
        audioEngine.pluginChain.removePlugin(id)
    }

    fun togglePlugin(id: UUID) = audioEngine.pluginChain.toggleEnabled(id)

    fun reorderPlugin(from: Int, to: Int) = audioEngine.pluginChain.reorder(from, to)

    fun setPluginParam(slotId: UUID, paramId: Int, value: Float) {
        val slot = audioEngine.pluginChain.getSlot(slotId) ?: return
        if (slot.nativeHandle != 0L)
            audioEngine.setNativePluginParam(slot.nativeHandle, paramId, value)
        audioEngine.pluginChain.updateParam(slotId, paramId, value)
    }

    // ── Presets ───────────────────────────────────────────────────────────────
    fun applyPreset(preset: Preset) {
        _selectedPreset.value = preset
        val e = preset.effects
        setNoiseRepellentEnabled(e.noiseRepellentEnabled)
        setNoiseReduction(e.noiseReduction)
        setNoiseTonalReduction(e.noiseTonalReduction)
        setNoiseOutputGain(e.noiseOutputGain)
        setNoiseSmoothing(e.noiseSmoothing)
        setNoiseSmoothingMode(e.noiseSmoothingMode)
        setNoiseDfttStrength(e.noiseDfttStrength)
        setNoiseSignalProtection(e.noiseSignalProtection)
        setNoiseWhitening(e.noiseWhitening)
        setNoiseSuppression(e.noiseSuppression)
        setNoiseAggressiveness(e.noiseAggressiveness)
        setNoiseProfileOffset(e.noiseProfileOffset)
        setNoiseTonalProfileOffset(e.noiseTonalProfileOffset)
        setNoiseAdaptive(e.noiseAdaptive)
        setNoiseEstimatorMethod(e.noiseEstimatorMethod)
        setNoiseQualityMode(e.noiseQualityMode)
        setNoiseCurveEnabled(e.noiseCurveEnabled)
        e.noiseCurveBands.forEachIndexed(::setNoiseCurveBand)
        setNoiseResidualListen(e.noiseResidualListen)
        setNoiseTransientProtection(e.noiseTransientProtection)
        setGateEnabled(e.gateEnabled);     setGateThreshold(e.gateThreshold)
        setGateAttack(e.gateAttack);       setGateRelease(e.gateRelease)
        setEqEnabled(e.eqEnabled);         e.eqBands.forEachIndexed { i, db -> setEqBand(i, db) }
        setCompEnabled(e.compEnabled);     setCompThreshold(e.compThreshold)
        setCompRatio(e.compRatio);         setCompAttack(e.compAttack)
        setCompRelease(e.compRelease);     setCompMakeup(e.compMakeup)
        setReverbEnabled(e.reverbEnabled); setReverbMix(e.reverbMix)
        setReverbRoom(e.reverbRoom);       setReverbDamp(e.reverbDamp)
        setPitchEnabled(e.pitchEnabled);   setPitchSemitones(e.pitchSemitones)
        _gateState.value   = GateState(e.gateEnabled, e.gateThreshold, e.gateAttack, e.gateRelease)
        _eqState.value     = EqState(e.eqEnabled, e.eqBands)
        _compState.value   = CompState(e.compEnabled, e.compThreshold, e.compRatio, e.compAttack, e.compRelease, e.compMakeup)
        _reverbState.value = ReverbState(e.reverbEnabled, e.reverbMix, e.reverbRoom, e.reverbDamp)
        _pitchState.value  = PitchState(e.pitchEnabled, e.pitchSemitones)
        _noiseRepellentState.value = NoiseRepellentState(
            enabled = e.noiseRepellentEnabled,
            reductionDb = e.noiseReduction,
            tonalReductionDb = e.noiseTonalReduction,
            outputGainDb = e.noiseOutputGain,
            smoothingPercent = e.noiseSmoothing,
            smoothingMode = e.noiseSmoothingMode,
            dfttStrength = e.noiseDfttStrength,
            signalProtectionPercent = e.noiseSignalProtection,
            whiteningPercent = e.noiseWhitening,
            suppressionPercent = e.noiseSuppression,
            aggressiveness = e.noiseAggressiveness,
            profileOffsetDb = e.noiseProfileOffset,
            tonalProfileOffsetDb = e.noiseTonalProfileOffset,
            adaptiveNoise = e.noiseAdaptive,
            estimatorMethod = e.noiseEstimatorMethod,
            qualityMode = e.noiseQualityMode,
            curveEnabled = e.noiseCurveEnabled,
            curveBandsDb = e.noiseCurveBands,
            residualListen = e.noiseResidualListen,
            transientProtection = e.noiseTransientProtection,
        )
    }

    fun savePreset(name: String) {
        val preset = Preset(
            name    = name,
            effects = EffectState(
                noiseRepellentEnabled = _noiseRepellentState.value.enabled,
                noiseReduction = _noiseRepellentState.value.reductionDb,
                noiseTonalReduction = _noiseRepellentState.value.tonalReductionDb,
                noiseOutputGain = _noiseRepellentState.value.outputGainDb,
                noiseSmoothing = _noiseRepellentState.value.smoothingPercent,
                noiseSmoothingMode = _noiseRepellentState.value.smoothingMode,
                noiseDfttStrength = _noiseRepellentState.value.dfttStrength,
                noiseSignalProtection = _noiseRepellentState.value.signalProtectionPercent,
                noiseWhitening = _noiseRepellentState.value.whiteningPercent,
                noiseSuppression = _noiseRepellentState.value.suppressionPercent,
                noiseAggressiveness = _noiseRepellentState.value.aggressiveness,
                noiseProfileOffset = _noiseRepellentState.value.profileOffsetDb,
                noiseTonalProfileOffset = _noiseRepellentState.value.tonalProfileOffsetDb,
                noiseAdaptive = _noiseRepellentState.value.adaptiveNoise,
                noiseEstimatorMethod = _noiseRepellentState.value.estimatorMethod,
                noiseQualityMode = _noiseRepellentState.value.qualityMode,
                noiseCurveEnabled = _noiseRepellentState.value.curveEnabled,
                noiseCurveBands = _noiseRepellentState.value.curveBandsDb,
                noiseResidualListen = _noiseRepellentState.value.residualListen,
                noiseTransientProtection = _noiseRepellentState.value.transientProtection,
                gateEnabled   = _gateState.value.enabled,
                gateThreshold = _gateState.value.thresholdDb,
                gateAttack    = _gateState.value.attackMs,
                gateRelease   = _gateState.value.releaseMs,
                eqEnabled     = _eqState.value.enabled,
                eqBands       = _eqState.value.bands,
                compEnabled   = _compState.value.enabled,
                compThreshold = _compState.value.thresholdDb,
                compRatio     = _compState.value.ratio,
                compAttack    = _compState.value.attackMs,
                compRelease   = _compState.value.releaseMs,
                compMakeup    = _compState.value.makeupDb,
                reverbEnabled = _reverbState.value.enabled,
                reverbMix     = _reverbState.value.mix,
                reverbRoom    = _reverbState.value.roomSize,
                reverbDamp    = _reverbState.value.damping,
                pitchEnabled  = _pitchState.value.enabled,
                pitchSemitones = _pitchState.value.semitones,
            ),
        )
        viewModelScope.launch { presetManager.save(preset) }
    }

    fun rescan() { viewModelScope.launch { pluginManager.scanAll() } }

    fun requestShizukuPermission() { shizukuManager.requestPermission() }

    /** Returns null if available, or a reason string if not. */
    fun checkTierAvailability(tier: com.micplugin.service.VirtualMicTier): String? =
        virtualMicService.checkTierAvailability(tier)

    /** Manually switch to a tier. Returns unavailability reason or null on success. */
    fun setTier(tier: com.micplugin.service.VirtualMicTier): String? =
        virtualMicService.setTier(tier)

    val lv2Paths  = pluginPathPrefs.lv2Paths
    val clapPaths = pluginPathPrefs.clapPaths
    val vst3Paths = pluginPathPrefs.vst3Paths

    fun addPluginPath(context: android.content.Context, format: com.micplugin.plugin.PluginFormat, path: String) {
        pluginPathPrefs.addPath(context, format, path)
    }
    fun removePluginPath(context: android.content.Context, format: com.micplugin.plugin.PluginFormat, path: String) {
        pluginPathPrefs.removePath(context, format, path)
    }
    fun allPluginPaths(context: android.content.Context, format: com.micplugin.plugin.PluginFormat) =
        pluginPathPrefs.allPaths(context, format)


    private val _monitoringEnabled = MutableStateFlow(true)
    val monitoringEnabled: kotlinx.coroutines.flow.StateFlow<Boolean> = _monitoringEnabled

    fun setMonitoring(context: android.content.Context, enabled: Boolean) {
        _monitoringEnabled.value = enabled
        SoftwareLoopback.setMonitorEnabled(enabled)
        // Remember the choice so it survives app/process restarts.
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("monitor_enabled", enabled).apply()
    }

    fun loadMonitoring(context: android.content.Context) {
        val saved = context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .getBoolean("monitor_enabled", true)
        _monitoringEnabled.value = saved
        SoftwareLoopback.setMonitorEnabled(saved)
    }


    fun getPluginParamsJson(nativeHandle: Long): String =
        audioEngine.getPluginParamsJson(nativeHandle)


    private val _outputDevices = MutableStateFlow<List<com.micplugin.service.OutputDevice>>(emptyList())
    val outputDevices: kotlinx.coroutines.flow.StateFlow<List<com.micplugin.service.OutputDevice>> = _outputDevices

    private val _selectedOutputDeviceId = MutableStateFlow(-1)
    val selectedOutputDeviceId: kotlinx.coroutines.flow.StateFlow<Int> = _selectedOutputDeviceId

    fun loadOutputDevices(context: android.content.Context) {
        _outputDevices.value = SoftwareLoopback.getOutputDevices(context)
        val saved = context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("output_device_id", -1)
        _selectedOutputDeviceId.value = saved
    }

    fun setOutputDevice(context: android.content.Context, deviceId: Int) {
        _selectedOutputDeviceId.value = deviceId
        SoftwareLoopback.setOutputDevice(context, deviceId)
        AudioProcessingService.reinitialize(context, "Monitor output changed")
    }

    // ── Input device (fixes #5 — external sound cards were stuck on the built-in mic) ──

    private val _inputDevices = MutableStateFlow<List<com.micplugin.service.OutputDevice>>(emptyList())
    val inputDevices: kotlinx.coroutines.flow.StateFlow<List<com.micplugin.service.OutputDevice>> = _inputDevices

    private val _selectedInputDeviceId = MutableStateFlow(-1)
    val selectedInputDeviceId: kotlinx.coroutines.flow.StateFlow<Int> = _selectedInputDeviceId

    fun loadInputDevices(context: android.content.Context) {
        _inputDevices.value = SoftwareLoopback.getInputDevices(context)
        val saved = context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .getInt("input_device_id", -1)
        _selectedInputDeviceId.value = saved
    }

    fun setInputDevice(context: android.content.Context, deviceId: Int) {
        _selectedInputDeviceId.value = deviceId
        SoftwareLoopback.setInputDevice(context, deviceId)
        AudioProcessingService.reinitialize(context, "Microphone input changed")
    }

    fun refreshAudioDevices(context: android.content.Context) {
        loadOutputDevices(context)
        loadInputDevices(context)
        AudioRouteDiagnostics.inventoryChanged("Manual inventory refresh")
        SoftwareLoopback.inventorySummary(context).lineSequence()
            .forEach(AudioRouteDiagnostics::event)
    }

    fun reinitializeAudioStack(context: android.content.Context) {
        AudioProcessingService.reinitialize(context, "Manual full audio-stack reset")
    }

    fun clearAudioRouteLog(context: android.content.Context) {
        AudioRouteDiagnostics.clear(context)
    }

    fun exportAudioRouteLog(context: android.content.Context): String =
        AudioRouteDiagnostics.export(context)

    fun setAudioReleasePause(context: android.content.Context, milliseconds: Long) {
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putLong("audio_release_pause_ms", milliseconds.coerceIn(100L, 2000L)).apply()
    }

    fun setAudioInputSettle(context: android.content.Context, milliseconds: Long) {
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putLong("audio_input_settle_ms", milliseconds.coerceIn(50L, 2000L)).apply()
    }

    fun setAudioRouteTimeout(context: android.content.Context, milliseconds: Long) {
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putLong("audio_route_timeout_ms", milliseconds.coerceIn(2000L, 30000L)).apply()
    }

    fun setAudioOutputVerify(context: android.content.Context, milliseconds: Long) {
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putLong("audio_output_verify_ms", milliseconds.coerceIn(500L, 10000L)).apply()
    }

}
