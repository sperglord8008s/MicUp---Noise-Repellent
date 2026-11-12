package com.micplugin.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.micplugin.MainActivity
import com.micplugin.audio.AudioEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class AudioProcessingService : Service() {

    companion object {
        const val CHANNEL_ID = "micplugin_audio"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.micplugin.STOP"
        const val ACTION_RESET_AUDIO = "com.micplugin.RESET_AUDIO"
        private const val EXTRA_RESET_REASON = "reset_reason"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AudioProcessingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioProcessingService::class.java))
        }

        fun reinitialize(context: Context, reason: String = "Manual reset") {
            val intent = Intent(context, AudioProcessingService::class.java).apply {
                action = ACTION_RESET_AUDIO
                putExtra(EXTRA_RESET_REASON, reason)
            }
            context.startForegroundService(intent)
        }
    }

    @Inject lateinit var audioEngine: AudioEngine

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val resetMutex = Mutex()
    private var statusJob: Job? = null
    private var deviceChangeJob: Job? = null
    private var deviceCallbacksArmed = false
    private lateinit var audioManager: AudioManager

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onDeviceInventoryChanged("added", addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onDeviceInventoryChanged("removed", removedDevices)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AudioRouteDiagnostics.bind(this)
        AudioRouteDiagnostics.event("Audio service created on Android ${android.os.Build.VERSION.RELEASE}")
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    buildNotification("Starting audio stack…", 0, 0f),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(NOTIF_ID, buildNotification("Starting audio stack…", 0, 0f))
            }
        } catch (e: Exception) {
            AudioRouteDiagnostics.event(
                "Microphone foreground service rejected: ${e.javaClass.simpleName}: ${e.message}",
            )
            stopSelf()
            return
        }
        acquireWakeLock()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        startStatusUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val reason = when (intent?.action) {
            ACTION_RESET_AUDIO -> intent.getStringExtra(EXTRA_RESET_REASON) ?: "Manual reset"
            else -> "Service start"
        }
        requestStackReset(reason)
        return START_STICKY
    }

    private fun requestStackReset(reason: String) {
        scope.launch {
            resetMutex.withLock {
                reinitializeEntireAudioStack(reason)
            }
        }
    }

    /**
     * Full reset, deliberately staged so Android's HAL has time to release stale device handles.
     * The selected communication route is established before Oboe opens its input stream.
     */
    private suspend fun reinitializeEntireAudioStack(reason: String) {
        AudioRouteDiagnostics.begin(reason)
        updateNotification("Resetting audio routes…", 0, 0f)
        deviceCallbacksArmed = false

        try {
            AudioRouteDiagnostics.phase("Stop", "Muting and closing output, input and DSP streams")
            SoftwareLoopback.stop()
            audioEngine.stop()

            val prefs = getSharedPreferences("micup_prefs", Context.MODE_PRIVATE)

            AudioRouteDiagnostics.phase("Inventory", "Re-reading Android AudioDeviceInfo and MicrophoneInfo")
            SoftwareLoopback.inventorySummary(this).lineSequence().forEach(AudioRouteDiagnostics::event)

            val route = SoftwareLoopback.prepareRoute(this)
            if (route.failureReason != null) {
                AudioRouteDiagnostics.event("Route preparation warning: ${route.failureReason}")
            }

            AudioRouteDiagnostics.phase("Input", "Opening ${route.requestedInputLabel}")
            audioEngine.setMonitorCallback { buffer: FloatArray, size: Int ->
                SoftwareLoopback.writeProcessed(buffer, size)
            }
            audioEngine.setInputDevice(
                route.nativeInputDeviceId,
                route.isCommunicationRoute,
            )
            val inputStarted = audioEngine.start()
            if (!inputStarted) {
                SoftwareLoopback.stop()
                AudioRouteDiagnostics.complete(false, "Native input/processing streams failed to open")
                updateNotification("Audio input failed — open Settings", 0, 0f)
                return
            }

            val inputPause = prefs.getLong("audio_input_settle_ms", 250L).coerceIn(50L, 2000L)
            AudioRouteDiagnostics.phase("Input settle", "Waiting ${inputPause}ms before monitor output")
            delay(inputPause)

            AudioRouteDiagnostics.phase("Output", "Opening ${route.requestedOutputLabel} muted")
            val outputStarted = SoftwareLoopback.start(this, route)
            val verifyTimeout = prefs.getLong("audio_output_verify_ms", 3000L)
                .coerceIn(500L, 10000L)
            val outputVerified = outputStarted &&
                SoftwareLoopback.awaitRouteAndEnable(route, verifyTimeout)

            val success = inputStarted && outputVerified
            val detail = if (success) {
                "Input and output reopened; physical output route verified"
            } else {
                route.failureReason
                    ?: "Input is running, but monitor output stayed safety-muted because routing was not verified"
            }
            AudioRouteDiagnostics.complete(success, detail)
            val status = audioEngine.status.value
            updateNotification(
                if (success) "Active · route verified" else "Input active · output route failed",
                audioEngine.pluginChain.slots.value.size,
                status.latencyMs,
            )
        } catch (e: Exception) {
            SoftwareLoopback.stop()
            audioEngine.stop()
            AudioRouteDiagnostics.event(
                "Reset exception: ${e.javaClass.simpleName}: ${e.message}",
            )
            AudioRouteDiagnostics.complete(false, e.message ?: "Unexpected reset failure")
            updateNotification("Audio reset failed — open Settings", 0, 0f)
        } finally {
            deviceCallbacksArmed = true
        }
    }

    private fun onDeviceInventoryChanged(
        action: String,
        devices: Array<out AudioDeviceInfo>,
    ) {
        if (devices.isEmpty()) return
        val summary = devices.joinToString { "type=${it.type}, id=${it.id}" }
        AudioRouteDiagnostics.inventoryChanged("$action $summary")
        if (!deviceCallbacksArmed) return

        deviceChangeJob?.cancel()
        deviceChangeJob = scope.launch {
            // Bluetooth publishes profile endpoints in bursts. Wait until the inventory settles.
            delay(700)
            resetMutex.withLock {
                reinitializeEntireAudioStack("Audio device $action")
            }
        }
    }

    private fun startStatusUpdates() {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch {
            while (isActive) {
                try {
                    val routeStatus = AudioRouteDiagnostics.status.value
                    if (!routeStatus.isResetting) {
                        val status = audioEngine.status.value
                        updateNotification(
                            if (routeStatus.lastResetSucceeded == false) {
                                "Input active · output route failed"
                            } else {
                                "Active · route verified"
                            },
                            audioEngine.pluginChain.slots.value.size,
                            status.latencyMs,
                        )
                    }
                } catch (e: Exception) {
                    AudioRouteDiagnostics.event("Notification update failed: ${e.message}")
                }
                delay(5000)
            }
        }
    }

    private fun updateNotification(text: String, plugins: Int, latencyMs: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, plugins, latencyMs))
    }

    private fun buildNotification(text: String, plugins: Int, latencyMs: Float): Notification {
        val mainIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AudioProcessingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val resetIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AudioProcessingService::class.java).apply {
                action = ACTION_RESET_AUDIO
                putExtra(EXTRA_RESET_REASON, "Notification reset")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val latency = if (latencyMs > 0f) " · ${latencyMs.toInt()}ms" else ""
        val pluginsText = if (plugins > 0) " · $plugins plugins" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MicUp NR Active")
            .setContentText("$text$pluginsText$latency")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(mainIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_popup_sync, "Reset audio", resetIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MicUp Audio",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Real-time audio processing service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MicPlugin::AudioWakeLock",
        ).apply { acquire() }
    }

    override fun onDestroy() {
        deviceCallbacksArmed = false
        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (_: Exception) {}
        deviceChangeJob?.cancel()
        statusJob?.cancel()
        scope.cancel()
        audioEngine.setMonitorCallback(null)
        SoftwareLoopback.stop()
        audioEngine.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        AudioRouteDiagnostics.event("Audio service destroyed")
        super.onDestroy()
    }
}
