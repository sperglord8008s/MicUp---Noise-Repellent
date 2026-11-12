package com.micplugin.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class AudioRouteStatus(
    val phase: String = "Idle",
    val detail: String = "Audio service has not reported a route yet",
    val requestedInput: String = "Auto",
    val requestedOutput: String = "Auto",
    val actualOutput: String = "Not active",
    val communicationDevice: String = "None",
    val isResetting: Boolean = false,
    val lastResetSucceeded: Boolean? = null,
    val generation: Int = 0,
    val inventoryVersion: Int = 0,
)

/**
 * Small, process-local route journal used by the service and Settings UI.
 *
 * Routing failures are otherwise almost invisible: Android can accept a preferred-device
 * request and still choose a different physical route. Keeping both the requested and the
 * observed route makes that failure explicit, and the on-disk copy survives an app restart.
 */
object AudioRouteDiagnostics {
    private const val TAG = "MicUp.AudioRoute"
    private const val MAX_VISIBLE_EVENTS = 120
    private const val MAX_LOG_BYTES = 256 * 1024
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val _status = MutableStateFlow(AudioRouteStatus())
    val status: StateFlow<AudioRouteStatus> = _status

    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    private var logFile: File? = null

    @Synchronized
    fun bind(context: Context) {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        logFile = File(directory, "audio_route.log")
    }

    @Synchronized
    fun begin(reason: String) {
        _status.value = _status.value.copy(
            phase = "Resetting",
            detail = reason,
            actualOutput = "Awaiting route verification",
            communicationDevice = "None",
            isResetting = true,
            lastResetSucceeded = null,
        )
        event("RESET begin: $reason")
    }

    @Synchronized
    fun phase(name: String, detail: String) {
        _status.value = _status.value.copy(phase = name, detail = detail)
        event("$name: $detail")
    }

    @Synchronized
    fun requested(input: String, output: String) {
        _status.value = _status.value.copy(
            requestedInput = input,
            requestedOutput = output,
        )
        event("Requested input=$input; output=$output")
    }

    @Synchronized
    fun observed(actualOutput: String, communicationDevice: String) {
        _status.value = _status.value.copy(
            actualOutput = actualOutput,
            communicationDevice = communicationDevice,
        )
        event("Observed output=$actualOutput; communication=$communicationDevice")
    }

    @Synchronized
    fun complete(success: Boolean, detail: String) {
        val previous = _status.value
        _status.value = previous.copy(
            phase = if (success) "Ready" else "Route failed",
            detail = detail,
            isResetting = false,
            lastResetSucceeded = success,
            generation = previous.generation + 1,
        )
        event("RESET ${if (success) "complete" else "failed"}: $detail")
    }

    @Synchronized
    fun routeLost(detail: String) {
        _status.value = _status.value.copy(
            phase = "Route changed",
            detail = detail,
            lastResetSucceeded = false,
            actualOutput = detail,
        )
        event("ROUTE LOST: $detail")
    }

    @Synchronized
    fun inventoryChanged(detail: String) {
        _status.value = _status.value.copy(
            inventoryVersion = _status.value.inventoryVersion + 1,
        )
        event("Device inventory: $detail")
    }

    @Synchronized
    fun event(message: String) {
        val line = "${LocalTime.now().format(timeFormat)}  $message"
        Log.i(TAG, message)
        _events.value = (_events.value + line).takeLast(MAX_VISIBLE_EVENTS)
        appendToFile(line)
    }

    @Synchronized
    fun clear(context: Context) {
        bind(context)
        _events.value = emptyList()
        try { logFile?.writeText("") } catch (e: Exception) {
            Log.w(TAG, "Could not clear route log", e)
        }
    }

    @Synchronized
    fun export(context: Context): String {
        bind(context)
        return try {
            logFile?.takeIf { it.exists() }?.readText().orEmpty()
        } catch (e: Exception) {
            _events.value.joinToString("\n")
        }
    }

    private fun appendToFile(line: String) {
        val file = logFile ?: return
        try {
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                val retained = file.readLines().takeLast(MAX_VISIBLE_EVENTS / 2)
                file.writeText(retained.joinToString("\n", postfix = "\n"))
            }
            file.appendText("$line\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not append route log", e)
        }
    }
}
