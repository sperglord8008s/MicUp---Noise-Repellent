package com.micplugin.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MicrophoneInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

data class OutputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val details: String = "",
)

data class PreparedAudioRoute(
    val outputDevice: AudioDeviceInfo?,
    val inputDevice: AudioDeviceInfo?,
    val nativeInputDeviceId: Int,
    val communicationDevice: AudioDeviceInfo?,
    val isCommunicationRoute: Boolean,
    val routeRequestAccepted: Boolean,
    val requestedInputLabel: String,
    val requestedOutputLabel: String,
    val failureReason: String? = null,
)

/**
 * Audible monitor path and Android route coordinator.
 *
 * The order here matters. A communication/SCO route must be selected and observed before the
 * input stream is opened. The monitor AudioTrack is then created for that route, starts muted,
 * and is only unmuted after getRoutedDevice() confirms Android did not silently fall back to the
 * speaker. AudioProcessingService owns that complete stop -> route -> input -> output sequence.
 */
object SoftwareLoopback {

    private const val TAG = "MicPlugin.Loopback"
    private const val PREFS = "micup_prefs"
    private const val SAMPLE_RATE = 48000
    private const val FORMAT = AudioFormat.ENCODING_PCM_FLOAT
    private const val QUEUE_CAP = 16
    private const val DEFAULT_RELEASE_PAUSE_MS = 300L
    private const val DEFAULT_COMMUNICATION_TIMEOUT_MS = 8000L
    private const val DEFAULT_OUTPUT_VERIFY_TIMEOUT_MS = 3000L

    private var monitorTrack: AudioTrack? = null
    private var workerThread: Thread? = null
    private var audioManager: AudioManager? = null
    private var selectedDeviceId: Int = -1
    private var monitorEnabled: Boolean = true
    @Volatile private var running = false
    @Volatile private var routeVerified = false
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null
    private var activeRoute: PreparedAudioRoute? = null

    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CAP)
    private val pool = ArrayBlockingQueue<FloatArray>(QUEUE_CAP * 2)

    val isRunning: Boolean get() = running

    // ── Device inventory ─────────────────────────────────────────────────────

    fun getOutputDevices(context: Context): List<OutputDevice> {
        val am = context.getSystemService(AudioManager::class.java)
        val devices = if (Build.VERSION.SDK_INT >= 23) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink }
                .sortedWith(compareBy(::outputPriority, AudioDeviceInfo::getId))
        } else emptyList()

        return listOf(
            OutputDevice(
                -1,
                "Auto (best available)",
                -1,
                "Android chooses from wired, Bluetooth media, USB, earpiece, then speaker",
            ),
        ) + devices.map { deviceEntry(it, null) }
    }

    fun getInputDevices(context: Context): List<OutputDevice> {
        val am = context.getSystemService(AudioManager::class.java)
        val microphoneInfo = microphoneInfoByInternalId(am)
        val devices = if (Build.VERSION.SDK_INT >= 23) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.isSource }
                .sortedWith(compareBy(::inputPriority, AudioDeviceInfo::getId))
        } else emptyList()

        return listOf(
            OutputDevice(
                -1,
                "Auto (Android default)",
                -1,
                "Oboe uses AUDIO_DEVICE_NONE and lets Android select the source",
            ),
        ) + devices.map { deviceEntry(it, microphoneInfo[it.id]) }
    }

    fun inventorySummary(context: Context): String {
        val inputs = getInputDevices(context).drop(1)
        val outputs = getOutputDevices(context).drop(1)
        return buildString {
            append("Inputs (${inputs.size}): ")
            append(inputs.joinToString { "${it.name} [${it.details}]" }.ifBlank { "none" })
            append("\nOutputs (${outputs.size}): ")
            append(outputs.joinToString { "${it.name} [${it.details}]" }.ifBlank { "none" })
        }
    }

    fun setOutputDevice(context: Context, deviceId: Int) {
        selectedDeviceId = deviceId
        val devices = context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        persistSelection(context, "output", deviceId, devices.firstOrNull { it.id == deviceId })
    }

    fun setInputDevice(context: Context, deviceId: Int) {
        val devices = context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        persistSelection(context, "input", deviceId, devices.firstOrNull { it.id == deviceId })
    }

    // ── Ordered route preparation ────────────────────────────────────────────

    suspend fun prepareRoute(context: Context): PreparedAudioRoute {
        AudioRouteDiagnostics.bind(context)
        val am = context.getSystemService(AudioManager::class.java)
        audioManager = am

        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource }
        val outputSelection = resolveSelection(context, "output", outputs, ::chooseAutoOutput)
        val inputSelection = resolveSelection(context, "input", inputs) { null }

        var requestedOutput = outputSelection.device
        val requestedInput = inputSelection.device
        val communicationRequired = isCommunicationDevice(requestedOutput) ||
            isCommunicationDevice(requestedInput)

        AudioRouteDiagnostics.requested(inputSelection.label, outputSelection.label)

        AudioRouteDiagnostics.phase("Release", "Stopping old communication route")
        releaseSystemRoute(am)
        val releasePause = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("audio_release_pause_ms", DEFAULT_RELEASE_PAUSE_MS)
            .coerceIn(100L, 2000L)
        delay(releasePause)

        var communicationDevice: AudioDeviceInfo? = null
        var accepted = true
        var failure: String? = null

        if (communicationRequired) {
            AudioRouteDiagnostics.phase("Bluetooth communication", "Selecting SCO/BLE call route")
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val available = am.availableCommunicationDevices
                communicationDevice = findCommunicationSink(
                    available,
                    requestedOutput,
                    requestedInput,
                )
                if (communicationDevice == null) {
                    accepted = false
                    failure = "No matching SCO/BLE communication sink is available"
                } else {
                    // A Bluetooth microphone and A2DP playback cannot remain active together.
                    // The platform's matching communication sink is therefore the effective output.
                    requestedOutput = communicationDevice
                    accepted = requestCommunicationDevice(
                        am,
                        communicationDevice,
                        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getLong(
                                "audio_route_timeout_ms",
                                DEFAULT_COMMUNICATION_TIMEOUT_MS,
                            ).coerceIn(2000L, 30000L),
                    )
                    if (!accepted) {
                        failure = "Android did not activate ${deviceLabel(communicationDevice)}"
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                run { am.isBluetoothScoOn = true }
                val timeout = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getLong("audio_route_timeout_ms", DEFAULT_COMMUNICATION_TIMEOUT_MS)
                    .coerceIn(2000L, 30000L)
                val deadline = System.currentTimeMillis() + timeout
                @Suppress("DEPRECATION")
                while (!am.isBluetoothScoOn && System.currentTimeMillis() < deadline) delay(100)
                @Suppress("DEPRECATION")
                run { accepted = am.isBluetoothScoOn }
                if (!accepted) failure = "Legacy Bluetooth SCO did not become active"
            }
        } else {
            AudioRouteDiagnostics.phase("Media route", "Using Android media routing")
            am.mode = AudioManager.MODE_NORMAL
        }

        val effectiveOutputLabel = when {
            requestedOutput == null -> "Android default output"
            communicationRequired && requestedOutput.id != outputSelection.device?.id ->
                "${deviceLabel(requestedOutput)} (SCO/BLE required by selected mic)"
            else -> deviceLabel(requestedOutput)
        }
        AudioRouteDiagnostics.requested(inputSelection.label, effectiveOutputLabel)
        val nativeInputId = requestedInput?.id ?: -1
        if (inputSelection.wasExplicit && requestedInput != null) {
            updateResolvedId(context, "input", requestedInput)
        }
        if (outputSelection.wasExplicit && outputSelection.device != null) {
            updateResolvedId(context, "output", outputSelection.device)
        }

        return PreparedAudioRoute(
            outputDevice = requestedOutput,
            inputDevice = requestedInput,
            nativeInputDeviceId = nativeInputId,
            communicationDevice = communicationDevice,
            isCommunicationRoute = communicationRequired,
            routeRequestAccepted = accepted,
            requestedInputLabel = inputSelection.label,
            requestedOutputLabel = effectiveOutputLabel,
            failureReason = failure,
        )
    }

    private suspend fun requestCommunicationDevice(
        am: AudioManager,
        target: AudioDeviceInfo,
        timeoutMs: Long,
    ): Boolean {
        repeat(2) { attempt ->
            if (attempt > 0) {
                AudioRouteDiagnostics.event("Retrying communication route after clear")
                am.clearCommunicationDevice()
                delay(250)
            }
            val requestAccepted = try { am.setCommunicationDevice(target) } catch (e: Exception) {
                AudioRouteDiagnostics.event("setCommunicationDevice threw ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            AudioRouteDiagnostics.event(
                "setCommunicationDevice(${deviceLabel(target)}) accepted=$requestAccepted",
            )
            if (!requestAccepted) return@repeat

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val active = am.communicationDevice
                if (active != null && devicesMatch(active, target)) {
                    AudioRouteDiagnostics.event("Communication route active: ${deviceLabel(active)}")
                    return true
                }
                delay(100)
            }
        }
        return false
    }

    // ── Monitor AudioTrack ───────────────────────────────────────────────────

    fun start(context: Context, route: PreparedAudioRoute): Boolean {
        if (running) return true
        AudioRouteDiagnostics.bind(context)
        audioManager = context.getSystemService(AudioManager::class.java)
        activeRoute = route
        routeVerified = false

        monitorEnabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("monitor_enabled", true)
        selectedDeviceId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("output_device_id", -1)

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            FORMAT,
        ).coerceAtLeast(4096)

        val track = buildMonitorTrack(minBuffer, route.isCommunicationRoute)
        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            try { track?.release() } catch (_: Exception) {}
            AudioRouteDiagnostics.event("AudioTrack creation failed")
            return false
        }
        monitorTrack = track
        installRoutingListener(track)

        val preferredAccepted = try {
            route.outputDevice?.let { track.setPreferredDevice(it) } ?: true
        } catch (e: Exception) {
            AudioRouteDiagnostics.event("setPreferredDevice failed: ${e.message}")
            false
        }
        AudioRouteDiagnostics.event(
            "AudioTrack preferred=${route.outputDevice?.let(::deviceLabel) ?: "default"}; " +
                "accepted=$preferredAccepted",
        )

        // Safety invariant: remain muted until routedDevice proves this is not a speaker fallback.
        track.setVolume(0f)
        return try {
            requestAudioFocus(route.isCommunicationRoute)
            track.play()
            running = true
            queue.clear()
            startWorker()
            preferredAccepted && route.routeRequestAccepted
        } catch (e: Exception) {
            AudioRouteDiagnostics.event("AudioTrack start failed: ${e.javaClass.simpleName}: ${e.message}")
            stop()
            false
        }
    }

    suspend fun awaitRouteAndEnable(
        route: PreparedAudioRoute,
        timeoutMs: Long = DEFAULT_OUTPUT_VERIFY_TIMEOUT_MS,
    ): Boolean {
        if (!running || !route.routeRequestAccepted) {
            holdSafetyMute(route.failureReason ?: "Android rejected the route request")
            return false
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var actual: AudioDeviceInfo? = null
        while (System.currentTimeMillis() < deadline) {
            actual = currentRoutedDevice()
            if (actual != null && outputMatchesRoute(actual, route)) {
                routeVerified = true
                monitorTrack?.setVolume(if (monitorEnabled) 1f else 0f)
                val communication = currentCommunicationDeviceLabel()
                AudioRouteDiagnostics.observed(deviceLabel(actual), communication)
                AudioRouteDiagnostics.event(
                    "Monitor route verified; audio ${if (monitorEnabled) "unmuted" else "kept muted by Monitor switch"}",
                )
                return true
            }
            delay(100)
        }

        val actualLabel = actual?.let(::deviceLabel) ?: "No routed device reported"
        holdSafetyMute(
            "Requested ${route.requestedOutputLabel}, but Android routed $actualLabel; " +
                "monitor held muted to prevent speaker playback",
        )
        AudioRouteDiagnostics.observed(actualLabel, currentCommunicationDeviceLabel())
        return false
    }

    @Suppress("DEPRECATION")
    fun stop() {
        running = false
        routeVerified = false
        queue.clear()
        pool.clear()
        workerThread?.interrupt()
        try { workerThread?.join(500) } catch (_: InterruptedException) {}
        workerThread = null

        val track = monitorTrack
        routingListener?.let { listener ->
            try { track?.removeOnRoutingChangedListener(listener) } catch (_: Exception) {}
        }
        routingListener = null
        try { track?.setVolume(0f) } catch (_: Exception) {}
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        monitorTrack = null
        activeRoute = null
        abandonAudioFocus()
        audioManager?.let(::releaseSystemRoute)
        Log.i(TAG, "SoftwareLoopback stopped")
    }

    fun writeProcessed(buffer: FloatArray, size: Int) {
        if (!running || !monitorEnabled || !routeVerified) return
        val copy = pool.poll() ?: FloatArray(size)
        if (copy.size >= size) {
            System.arraycopy(buffer, 0, copy, 0, size)
        } else {
            buffer.copyInto(copy, 0, 0, minOf(size, copy.size))
        }
        if (!queue.offer(copy)) {
            queue.poll()?.let { pool.offer(it) }
            queue.offer(copy)
        }
    }

    fun setMonitorEnabled(enabled: Boolean) {
        monitorEnabled = enabled
        monitorTrack?.setVolume(if (enabled && routeVerified) 1f else 0f)
        if (!enabled) queue.clear()
        AudioRouteDiagnostics.event(
            "Monitor ${if (enabled) "ON" else "OFF"}; routeVerified=$routeVerified",
        )
    }

    fun setMicrophoneSidetone(mute: Boolean) {}
    fun onVoipCallStateChanged(active: Boolean) {}

    private fun startWorker() {
        workerThread = Thread({
            AudioRouteDiagnostics.event("Monitor worker started")
            while (running) {
                try {
                    val buffer = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    if (monitorEnabled && routeVerified) {
                        val result = monitorTrack?.write(
                            buffer,
                            0,
                            buffer.size,
                            AudioTrack.WRITE_BLOCKING,
                        ) ?: AudioTrack.ERROR_INVALID_OPERATION
                        if (result < 0) AudioRouteDiagnostics.event("AudioTrack.write returned $result")
                    }
                    pool.offer(buffer)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    AudioRouteDiagnostics.event("Monitor write error: ${e.message}")
                }
            }
            AudioRouteDiagnostics.event("Monitor worker stopped")
        }, "MicUp-Monitor-Worker").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun installRoutingListener(track: AudioTrack) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val listener = AudioRouting.OnRoutingChangedListener { routing ->
            val actual = routing.routedDevice
            AudioRouteDiagnostics.event(
                "AudioTrack routing callback: ${actual?.let(::deviceLabel) ?: "none"}",
            )
            val route = activeRoute
            if (routeVerified && route != null) {
                // Mute first. Routing callbacks can arrive while Android is between devices.
                monitorTrack?.setVolume(0f)
                if (actual != null && outputMatchesRoute(actual, route)) {
                    monitorTrack?.setVolume(if (monitorEnabled) 1f else 0f)
                } else {
                    val actualLabel = actual?.let(::deviceLabel) ?: "no active output"
                    holdSafetyMute("Route changed unexpectedly to $actualLabel")
                    AudioRouteDiagnostics.observed(actualLabel, currentCommunicationDeviceLabel())
                    AudioRouteDiagnostics.routeLost(actualLabel)
                }
            }
        }
        routingListener = listener
        track.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
    }

    private fun holdSafetyMute(reason: String) {
        routeVerified = false
        try { monitorTrack?.setVolume(0f) } catch (_: Exception) {}
        queue.clear()
        AudioRouteDiagnostics.event("SAFETY MUTE: $reason")
    }

    private fun currentRoutedDevice(): AudioDeviceInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) monitorTrack?.routedDevice else null
    } catch (_: Exception) { null }

    private fun currentCommunicationDeviceLabel(): String {
        val am = audioManager ?: return "None"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.let(::deviceLabel) ?: "None"
        } else {
            @Suppress("DEPRECATION")
            if (am.isBluetoothScoOn) "Legacy Bluetooth SCO" else "None"
        }
    }

    private fun outputMatchesRoute(actual: AudioDeviceInfo, route: PreparedAudioRoute): Boolean {
        val expected = if (route.isCommunicationRoute) {
            route.communicationDevice ?: route.outputDevice
        } else route.outputDevice
        return expected == null || devicesMatch(actual, expected)
    }

    private fun requestAudioFocus(communication: Boolean) {
        val am = audioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> monitorTrack?.setVolume(0f)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> monitorTrack?.setVolume(0.2f)
                AudioManager.AUDIOFOCUS_GAIN ->
                    monitorTrack?.setVolume(if (monitorEnabled && routeVerified) 1f else 0f)
            }
        }
        focusListener = listener
        val attributes = audioAttributes(communication)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(false)
                .build()
            am.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                listener,
                if (communication) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let(am::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                focusListener?.let(am::abandonAudioFocus)
            }
        } catch (_: Exception) {}
        focusRequest = null
        focusListener = null
    }

    private fun buildMonitorTrack(bufferSize: Int, communication: Boolean): AudioTrack? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes(communication))
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(FORMAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                if (communication) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2,
                AudioTrack.MODE_STREAM,
            )
        }
    } catch (e: Exception) {
        AudioRouteDiagnostics.event("buildMonitorTrack failed: ${e.message}")
        null
    }

    private fun audioAttributes(communication: Boolean): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(
                if (communication) AudioAttributes.USAGE_VOICE_COMMUNICATION
                else AudioAttributes.USAGE_MEDIA,
            )
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    @Suppress("DEPRECATION")
    private fun releaseSystemRoute(am: AudioManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            AudioRouteDiagnostics.event("Route release warning: ${e.message}")
        }
    }

    // ── Selection persistence / identity matching ────────────────────────────

    private data class ResolvedSelection(
        val device: AudioDeviceInfo?,
        val label: String,
        val wasExplicit: Boolean,
    )

    private fun resolveSelection(
        context: Context,
        prefix: String,
        devices: List<AudioDeviceInfo>,
        chooseAuto: (List<AudioDeviceInfo>) -> AudioDeviceInfo?,
    ): ResolvedSelection {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getInt("${prefix}_device_id", -1)
        if (savedId == -1) {
            val automatic = chooseAuto(devices)
            return ResolvedSelection(
                automatic,
                automatic?.let { "Auto -> ${deviceLabel(it)}" } ?: "Auto (Android default)",
                false,
            )
        }

        val savedType = prefs.getInt("${prefix}_device_type", Int.MIN_VALUE)
        val savedAddress = prefs.getString("${prefix}_device_address", "").orEmpty()
        val savedProduct = prefs.getString("${prefix}_device_product", "").orEmpty()
        val resolved = devices.firstOrNull { it.id == savedId }
            ?: devices.firstOrNull {
                savedAddress.isNotBlank() && it.type == savedType && deviceAddress(it) == savedAddress
            }
            ?: devices.firstOrNull {
                savedProduct.isNotBlank() && it.type == savedType &&
                    cleanProductName(it).equals(savedProduct, ignoreCase = true)
            }
            ?: devices.firstOrNull { it.type == savedType }

        if (resolved != null) {
            if (resolved.id != savedId) {
                AudioRouteDiagnostics.event(
                    "$prefix device id changed $savedId -> ${resolved.id}; matched by stable identity",
                )
            }
            return ResolvedSelection(resolved, deviceLabel(resolved), true)
        }

        val fallback = chooseAuto(devices)
        val previous = when {
            savedProduct.isNotBlank() -> savedProduct
            savedType != Int.MIN_VALUE -> typeConstant(savedType)
            else -> "device id $savedId"
        }
        AudioRouteDiagnostics.event("Saved $prefix $previous is unavailable; falling back to Auto")
        return ResolvedSelection(
            fallback,
            "$previous unavailable -> ${fallback?.let(::deviceLabel) ?: "Android default"}",
            true,
        )
    }

    private fun persistSelection(
        context: Context,
        prefix: String,
        id: Int,
        device: AudioDeviceInfo?,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putInt("${prefix}_device_id", id)
            if (device == null) {
                remove("${prefix}_device_type")
                remove("${prefix}_device_address")
                remove("${prefix}_device_product")
            } else {
                putInt("${prefix}_device_type", device.type)
                putString("${prefix}_device_address", deviceAddress(device))
                putString("${prefix}_device_product", cleanProductName(device).orEmpty())
            }
        }.apply()
    }

    private fun updateResolvedId(context: Context, prefix: String, device: AudioDeviceInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("${prefix}_device_id", device.id)
            .putInt("${prefix}_device_type", device.type)
            .putString("${prefix}_device_address", deviceAddress(device))
            .putString("${prefix}_device_product", cleanProductName(device).orEmpty())
            .apply()
    }

    private fun chooseAutoOutput(devices: List<AudioDeviceInfo>): AudioDeviceInfo? =
        devices.minWithOrNull(compareBy(::outputPriority, AudioDeviceInfo::getId))

    private fun findCommunicationSink(
        available: List<AudioDeviceInfo>,
        requestedOutput: AudioDeviceInfo?,
        requestedInput: AudioDeviceInfo?,
    ): AudioDeviceInfo? {
        val desired = when {
            isCommunicationDevice(requestedOutput) -> requestedOutput
            isCommunicationDevice(requestedInput) -> requestedInput
            else -> null
        }
        return available.firstOrNull { candidate ->
            desired != null && devicesMatch(candidate, desired)
        } ?: available.firstOrNull { candidate ->
            desired != null && candidate.type == desired.type &&
                deviceAddress(candidate).isNotBlank() &&
                deviceAddress(candidate) == deviceAddress(desired)
        } ?: available.firstOrNull { candidate ->
            candidate.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    candidate.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    // ── Android device schema / labels ───────────────────────────────────────

    private fun deviceEntry(device: AudioDeviceInfo, microphone: MicrophoneInfo?): OutputDevice {
        val typeLabel = humanType(device.type)
        val product = cleanProductName(device)
        val microphoneDescription = microphone?.description
            ?.trim()
            ?.takeUnless { it.isBlank() || it.equals("unknown", true) }
        val name = when {
            product != null && !product.equals(typeLabel, true) -> "$product · $typeLabel"
            microphoneDescription != null && device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                "$typeLabel · $microphoneDescription"
            else -> typeLabel
        }

        val details = buildList {
            add("${typeConstant(device.type)} (${device.type})")
            add("ID ${device.id}")
            add(if (device.isSource && device.isSink) "input/output" else if (device.isSource) "input" else "output")
            deviceAddress(device).takeIf { it.isNotBlank() }?.let { add("address $it") }
            device.sampleRates.takeIf { it.isNotEmpty() }?.let { rates ->
                add("rates ${rates.joinToString("/")}")
            }
            device.channelCounts.takeIf { it.isNotEmpty() }?.let { counts ->
                add("channels ${counts.joinToString("/")}")
            }
            microphone?.let {
                add("microphone ID ${it.id}")
                add(microphoneLocation(it.location))
                add(microphoneDirectionality(it.directionality))
            }
        }.joinToString(" · ")
        return OutputDevice(device.id, name, device.type, details)
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val product = cleanProductName(device)
        val base = humanType(device.type)
        return buildString {
            if (product != null && !product.equals(base, true)) append("$product · ")
            append(base)
            append(" [${typeConstant(device.type)}, id=${device.id}]")
        }
    }

    private fun cleanProductName(device: AudioDeviceInfo?): String? {
        val value = device?.productName?.toString()?.trim().orEmpty()
        return value.takeUnless {
            it.isBlank() || it.equals("unknown", true) || it.equals("default", true) ||
                it.equals("android", true) || it.equals("null", true)
        }
    }

    private fun deviceAddress(device: AudioDeviceInfo?): String =
        if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { device.address.orEmpty() } catch (_: SecurityException) { "" }
        } else ""

    private fun devicesMatch(first: AudioDeviceInfo, second: AudioDeviceInfo): Boolean {
        if (first.id == second.id) return true
        if (first.type != second.type) return false
        val firstAddress = deviceAddress(first)
        val secondAddress = deviceAddress(second)
        if (firstAddress.isNotBlank() && secondAddress.isNotBlank()) {
            return firstAddress == secondAddress
        }
        val firstProduct = cleanProductName(first)
        val secondProduct = cleanProductName(second)
        return firstProduct != null && secondProduct != null &&
            firstProduct.equals(secondProduct, ignoreCase = true)
    }

    private fun isCommunicationDevice(device: AudioDeviceInfo?): Boolean = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        AudioDeviceInfo.TYPE_BLE_HEADSET -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else -> false
    }

    private fun outputPriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> 1
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> 2
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 3
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 4
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 5
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> 6
        else -> 7
    }

    private fun inputPriority(device: AudioDeviceInfo): Int = when (device.type) {
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 0
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 1
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> 2
        else -> 3
    }

    private fun humanType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_UNKNOWN -> "Unknown Android audio device"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line audio"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset call audio (SCO)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth headphones media audio (A2DP)"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI audio"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio accessory"
        AudioDeviceInfo.TYPE_DOCK -> "Dock audio"
        AudioDeviceInfo.TYPE_FM -> "FM audio"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM tuner"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TV tuner"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony audio"
        AudioDeviceInfo.TYPE_AUX_LINE -> "Auxiliary line"
        AudioDeviceInfo.TYPE_IP -> "IP audio device"
        AudioDeviceInfo.TYPE_BUS -> "Audio bus"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Bluetooth hearing aid"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Safe built-in speaker"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote submix"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE broadcast"
        else -> "Android audio device type $type"
    }

    private fun typeConstant(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_UNKNOWN -> "TYPE_UNKNOWN"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "TYPE_BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "TYPE_LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "TYPE_LINE_DIGITAL"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "TYPE_HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "TYPE_HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "TYPE_HDMI_EARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "TYPE_USB_ACCESSORY"
        AudioDeviceInfo.TYPE_DOCK -> "TYPE_DOCK"
        AudioDeviceInfo.TYPE_FM -> "TYPE_FM"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "TYPE_BUILTIN_MIC"
        AudioDeviceInfo.TYPE_FM_TUNER -> "TYPE_FM_TUNER"
        AudioDeviceInfo.TYPE_TV_TUNER -> "TYPE_TV_TUNER"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TYPE_TELEPHONY"
        AudioDeviceInfo.TYPE_AUX_LINE -> "TYPE_AUX_LINE"
        AudioDeviceInfo.TYPE_IP -> "TYPE_IP"
        AudioDeviceInfo.TYPE_BUS -> "TYPE_BUS"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "TYPE_HEARING_AID"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "TYPE_BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "TYPE_REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "TYPE_BLE_BROADCAST"
        else -> "TYPE_$type"
    }

    private fun microphoneInfoByInternalId(am: AudioManager): Map<Int, MicrophoneInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyMap()
        return try { am.microphones.associateBy { it.id } }
        catch (e: Exception) {
            AudioRouteDiagnostics.event("AudioManager.getMicrophones unavailable: ${e.message}")
            emptyMap()
        }
    }

    private fun microphoneLocation(location: Int): String = when (location) {
        MicrophoneInfo.LOCATION_MAINBODY -> "main body"
        MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "movable main body"
        MicrophoneInfo.LOCATION_PERIPHERAL -> "peripheral"
        else -> "location unknown"
    }

    private fun microphoneDirectionality(directionality: Int): String = when (directionality) {
        MicrophoneInfo.DIRECTIONALITY_OMNI -> "omnidirectional"
        MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "bidirectional"
        MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
        MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "hypercardioid"
        MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "supercardioid"
        else -> "directionality unknown"
    }
}
