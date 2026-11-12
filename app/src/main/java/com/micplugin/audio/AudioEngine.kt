package com.micplugin.audio

import android.util.Log
import com.micplugin.plugin.PluginChain
import com.micplugin.plugin.PluginFormat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AudioLevels(
    val inputDb: Float = -100f,
    val outputDb: Float = -100f,
    val gainReductionDb: Float = 0f,
)

data class EngineStatus(
    val isRunning: Boolean = false,
    val sampleRate: Int = 48000,
    val framesPerBurst: Int = 128,
    val latencyMs: Float = 0f,
)

data class NoiseRepellentStatus(
    val engineReady: Boolean = false,
    val profileReady: Boolean = false,
    val latencyFrames: Int = 0,
    val frameSize: Int = 0,
    val fftSize: Int = 0,
    val hopSize: Int = 0,
    val profileBins: Int = 0,
    val profileBlocks: Int = 0,
    val transientDetected: Boolean = false,
    val transientIntensity: Float = 0f,
    val inputPeakDb: Float = -100f,
    val inputDcOffset: Float = 0f,
    val inputClipPercent: Float = 0f,
    val shortReadCount: Int = 0,
    val qualityMode: Int = 1,
    val effectiveSmoothingMode: Int = 0,
    val adaptiveEnabled: Boolean = false,
    val tonalPeaksHz: List<Float> = emptyList(),
    val inputDeviceId: Int = -1,
)

data class SpectrumFrame(
    val sampleRate: Int = 48000,
    val fftSize: Int = 4096,
    val generation: Int = 0,
    val inputDb: FloatArray = FloatArray(2049) { -120f },
    val outputDb: FloatArray = FloatArray(2049) { -120f },
    val noiseDb: FloatArray = FloatArray(2049) { -120f },
)

@Singleton
class AudioEngine @Inject constructor(
    private val oboe: OboeEngine,
    val pluginChain: PluginChain,
) {
    companion object { private const val TAG = "AudioEngine" }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _levels = MutableStateFlow(AudioLevels())
    val levels: StateFlow<AudioLevels> = _levels

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    private val _noiseRepellentStatus = MutableStateFlow(NoiseRepellentStatus())
    val noiseRepellentStatus: StateFlow<NoiseRepellentStatus> = _noiseRepellentStatus

    private val _spectrum = MutableStateFlow(SpectrumFrame())
    val spectrum: StateFlow<SpectrumFrame> = _spectrum
    @Volatile private var spectrumEnabled = false

    private var meterJob: Job? = null

    fun start(): Boolean {
        val ok = oboe.start()
        if (ok) {
            val noiseStatus = readNoiseRepellentStatus()
            _noiseRepellentStatus.value = noiseStatus
            val sampleRate = oboe.getSampleRate()
            val framesPerBurst = oboe.getFramesPerBurst()
            _status.value = EngineStatus(
                isRunning      = true,
                sampleRate     = sampleRate,
                framesPerBurst = framesPerBurst,
                latencyMs      = (framesPerBurst * 2f + noiseStatus.latencyFrames) *
                    1000f / sampleRate,
            )
            startMeterPolling()
            Log.i(TAG, "Engine started at ${oboe.getSampleRate()}Hz / ${oboe.getFramesPerBurst()} frames")
        }
        return ok
    }

    fun stop() {
        meterJob?.cancel()
        oboe.stop()
        _status.value = EngineStatus(isRunning = false)
        _levels.value = AudioLevels()
    }

    fun setMonitorCallback(cb: ((FloatArray, Int) -> Unit)?) {
        oboe.setMonitorCallback(cb)
    }

    fun getInputSessionId(): Int = oboe.getInputSessionId()
    fun setMonitoring(enabled: Boolean) = oboe.setParam(98, 0, if (enabled) 1f else 0f)
    fun setMasterBypass(bypass: Boolean) = oboe.setParam(99, 0, if (bypass) 1f else 0f)
    fun setInjectionMode(enabled: Boolean) = oboe.setInjectionMode(enabled)
    fun setInputDevice(deviceId: Int) = oboe.setInputDevice(deviceId)

    // ── Built-in Noise Repellent (libspecbleach) ─────────────────────────────
    fun setNoiseRepellentEnabled(on: Boolean) =
        oboe.setParam(5, 0, if (on) 1f else 0f)
    fun setNoiseLearning(on: Boolean) =
        oboe.setParam(5, 1, if (on) 1f else 0f)
    fun setNoiseReduction(db: Float) = oboe.setParam(5, 2, db)
    fun setNoiseSmoothing(amount: Float) = oboe.setParam(5, 3, amount)
    fun setNoiseMasking(amount: Float) = oboe.setParam(5, 4, amount)
    fun setNoiseWhitening(amount: Float) = oboe.setParam(5, 5, amount)
    fun setNoiseAggressiveness(amount: Float) = oboe.setParam(5, 6, amount)
    fun setNoiseResidualListen(on: Boolean) =
        oboe.setParam(5, 7, if (on) 1f else 0f)
    fun setNoiseOutputGain(db: Float) = oboe.setParam(5, 8, db)
    fun setNoiseProfileOffset(db: Float) = oboe.setParam(5, 9, db)
    fun setNoiseTransientProtection(on: Boolean) =
        oboe.setParam(5, 10, if (on) 1f else 0f)
    fun setNoiseTonalReduction(db: Float) = oboe.setParam(5, 11, db)
    fun setNoiseSmoothingMode(mode: Int) = oboe.setParam(5, 12, mode.toFloat())
    fun setNoiseDfttStrength(strength: Float) = oboe.setParam(5, 13, strength)
    fun setNoiseSuppressionStrength(strength: Float) = oboe.setParam(5, 14, strength)
    fun setNoiseTonalProfileOffset(db: Float) = oboe.setParam(5, 15, db)
    fun setNoiseAdaptive(on: Boolean) = oboe.setParam(5, 16, if (on) 1f else 0f)
    fun setNoiseCurveEnabled(on: Boolean) = oboe.setParam(5, 17, if (on) 1f else 0f)
    fun setNoiseCurveBand(band: Int, db: Float) = oboe.setParam(5, 18 + band, db)
    fun setNoiseQualityMode(mode: Int) = oboe.setParam(5, 28, mode.toFloat())
    fun setNoiseEstimatorMethod(method: Int) = oboe.setParam(5, 29, method.toFloat())

    fun setSpectrumEnabled(enabled: Boolean) {
        spectrumEnabled = enabled
        oboe.setSpectrumEnabled(enabled)
    }

    fun resetNoiseRepellentProfile() {
        oboe.resetNoiseRepellentProfile()
        _noiseRepellentStatus.value = _noiseRepellentStatus.value.copy(profileReady = false)
    }

    // ── Built-in effect param setters (all RT-safe via atomic stores) ──────────
    fun setGateThreshold(db: Float)   = oboe.setParam(0, 0, db)
    fun setGateAttack(ms: Float)      = oboe.setParam(0, 1, ms)
    fun setGateRelease(ms: Float)     = oboe.setParam(0, 2, ms)
    fun setGateEnabled(on: Boolean)   = oboe.setParam(0, 3, if (on) 1f else 0f)

    fun setEqBand(band: Int, db: Float) = oboe.setParam(1, band, db)
    fun setEqEnabled(on: Boolean)       = oboe.setParam(1, 10, if (on) 1f else 0f)

    fun setCompThreshold(db: Float)   = oboe.setParam(2, 0, db)
    fun setCompRatio(ratio: Float)    = oboe.setParam(2, 1, ratio)
    fun setCompAttack(ms: Float)      = oboe.setParam(2, 2, ms)
    fun setCompRelease(ms: Float)     = oboe.setParam(2, 3, ms)
    fun setCompMakeup(db: Float)      = oboe.setParam(2, 4, db)
    fun setCompEnabled(on: Boolean)   = oboe.setParam(2, 5, if (on) 1f else 0f)

    fun setReverbMix(mix: Float)      = oboe.setParam(3, 0, mix)
    fun setReverbRoom(size: Float)    = oboe.setParam(3, 1, size)
    fun setReverbDamp(damp: Float)    = oboe.setParam(3, 2, damp)
    fun setReverbEnabled(on: Boolean) = oboe.setParam(3, 3, if (on) 1f else 0f)

    fun setPitchSemitones(st: Float)  = oboe.setParam(4, 0, st)
    fun setPitchEnabled(on: Boolean)  = oboe.setParam(4, 1, if (on) 1f else 0f)

    // ── Plugin loading ────────────────────────────────────────────────────────
    fun getPluginParamsJson(handle: Long): String = oboe.getPluginParams(handle)

    fun loadNativePlugin(soPath: String, format: PluginFormat): Long {
        val formatId = when (format) {
            PluginFormat.CLAP -> 0
            PluginFormat.LV2  -> 1
            PluginFormat.VST3 -> 2
            else              -> return 0L
        }
        return oboe.loadPlugin(soPath, formatId)
    }

    fun unloadNativePlugin(handle: Long) = oboe.unloadPlugin(handle)

    fun setNativePluginParam(handle: Long, paramId: Int, value: Float) =
        oboe.setPluginParam(handle, paramId, value)

    // ── Meter polling (50ms cadence, off audio thread) ────────────────────────
    private fun startMeterPolling() {
        meterJob?.cancel()
        meterJob = scope.launch {
            while (isActive) {
                val raw = oboe.getLevels()
                _levels.value = AudioLevels(
                    inputDb        = raw.getOrElse(0) { -100f },
                    outputDb       = raw.getOrElse(1) { -100f },
                    gainReductionDb = raw.getOrElse(2) { 0f },
                )
                val noiseStatus = readNoiseRepellentStatus()
                _noiseRepellentStatus.value = noiseStatus
                _status.value.let { current ->
                    if (current.isRunning && current.sampleRate > 0) {
                        _status.value = current.copy(
                            latencyMs = (current.framesPerBurst * 2f +
                                noiseStatus.latencyFrames) * 1000f /
                                current.sampleRate,
                        )
                    }
                }
                if (spectrumEnabled) readSpectrum()?.let { _spectrum.value = it }
                delay(50)
            }
        }
    }

    private fun readNoiseRepellentStatus(): NoiseRepellentStatus {
        val raw = oboe.getNoiseRepellentStatus()
        return NoiseRepellentStatus(
            engineReady = raw.getOrElse(0) { 0f } > 0.5f,
            profileReady = raw.getOrElse(1) { 0f } > 0.5f,
            latencyFrames = raw.getOrElse(2) { 0f }.toInt(),
            frameSize = raw.getOrElse(3) { 0f }.toInt(),
            fftSize = raw.getOrElse(4) { 0f }.toInt(),
            hopSize = raw.getOrElse(5) { 0f }.toInt(),
            profileBins = raw.getOrElse(6) { 0f }.toInt(),
            profileBlocks = raw.getOrElse(7) { 0f }.toInt(),
            transientDetected = raw.getOrElse(8) { 0f } > 0.5f,
            transientIntensity = raw.getOrElse(9) { 0f },
            inputPeakDb = raw.getOrElse(10) { -100f },
            inputDcOffset = raw.getOrElse(11) { 0f },
            inputClipPercent = raw.getOrElse(12) { 0f },
            shortReadCount = raw.getOrElse(13) { 0f }.toInt(),
            qualityMode = raw.getOrElse(14) { 1f }.toInt(),
            effectiveSmoothingMode = raw.getOrElse(15) { 0f }.toInt(),
            adaptiveEnabled = raw.getOrElse(16) { 0f } > 0.5f,
            tonalPeaksHz = (17..20).mapNotNull { index ->
                raw.getOrNull(index)?.takeIf { it > 0f }
            },
            inputDeviceId = raw.getOrElse(21) { -1f }.toInt(),
        )
    }

    private fun readSpectrum(): SpectrumFrame? {
        val raw = oboe.getSpectrum()
        val binCount = 2049
        if (raw.size < 3 + binCount * 3) return null
        if (raw[2].toInt() == _spectrum.value.generation) return null
        return SpectrumFrame(
            sampleRate = raw[0].toInt().coerceAtLeast(1),
            fftSize = raw[1].toInt().coerceAtLeast(1),
            generation = raw[2].toInt(),
            inputDb = raw.copyOfRange(3, 3 + binCount),
            outputDb = raw.copyOfRange(3 + binCount, 3 + binCount * 2),
            noiseDb = raw.copyOfRange(3 + binCount * 2, 3 + binCount * 3),
        )
    }
}
