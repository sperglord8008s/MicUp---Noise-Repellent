package com.micplugin.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.micplugin.CrashReporter
import com.micplugin.service.ShizukuState
import com.micplugin.service.VirtualMicTier

@Composable
fun SettingsScreen(navController: NavController, vm: AudioViewModel = hiltViewModel()) {
    val status      by vm.engineStatus.collectAsState()
    val tier        by vm.virtualMicTier.collectAsState()
    val shizukuState by vm.shizukuState.collectAsState()
    val routeStatus by vm.audioRouteStatus.collectAsState()
    val routeEvents by vm.audioRouteEvents.collectAsState()
    val context     = LocalContext.current

    var crashReports by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    var viewingCrash by remember { mutableStateOf<java.io.File?>(null) }
    val audioPrefs = remember {
        context.getSharedPreferences("micup_prefs", android.content.Context.MODE_PRIVATE)
    }
    var releasePauseMs by remember {
        mutableFloatStateOf(audioPrefs.getLong("audio_release_pause_ms", 300L).toFloat())
    }
    var inputSettleMs by remember {
        mutableFloatStateOf(audioPrefs.getLong("audio_input_settle_ms", 250L).toFloat())
    }
    var routeTimeoutMs by remember {
        mutableFloatStateOf(audioPrefs.getLong("audio_route_timeout_ms", 8000L).toFloat())
    }
    var outputVerifyMs by remember {
        mutableFloatStateOf(audioPrefs.getLong("audio_output_verify_ms", 3000L).toFloat())
    }

    LaunchedEffect(Unit) { crashReports = CrashReporter.getCrashReports(context) }
    LaunchedEffect(routeStatus.inventoryVersion, routeStatus.generation) {
        vm.loadOutputDevices(context)
        vm.loadInputDevices(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioColors.Background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Surface(color = StudioColors.Surface, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = StudioColors.TextPrimary)
                }
                Text("SETTINGS", style = MaterialTheme.typography.titleMedium,
                    color = StudioColors.TextPrimary, letterSpacing = 2.sp)
            }
        }

        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            // ── Audio ─────────────────────────────────────────────────────────
            SettingsSection("Audio") {
                SettingsInfoRow("Sample Rate", "${status.sampleRate} Hz")
                SettingsInfoRow("Frames/Burst", "${status.framesPerBurst}")
                SettingsInfoRow("Latency (est.)", "~${status.latencyMs.toInt()} ms")
                HorizontalDivider(color = StudioColors.Border)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Audio route", fontSize = 12.sp, color = StudioColors.TextMuted)
                        Text(
                            routeStatus.phase,
                            fontSize = 12.sp,
                            color = when (routeStatus.lastResetSucceeded) {
                                true -> Color(0xFF3DFCAC)
                                false -> StudioColors.MeterRed
                                null -> StudioColors.Accent
                            },
                        )
                    }
                    if (routeStatus.isResetting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = StudioColors.Accent,
                        )
                    }
                    InfoButton(
                        title = "Verified audio routing",
                        text = "MicUp now closes both streams, releases Android's old route, " +
                            "selects SCO/BLE communication audio when required, opens input, " +
                            "waits, then opens output muted. It only unmutes after AudioTrack " +
                            "reports the requested physical device. A speaker fallback stays muted.",
                    )
                }
                Text(routeStatus.detail, fontSize = 10.sp, color = StudioColors.TextMuted)
                AudioRouteDetail("Requested input", routeStatus.requestedInput)
                AudioRouteDetail("Requested output", routeStatus.requestedOutput)
                AudioRouteDetail("Actual output", routeStatus.actualOutput)
                AudioRouteDetail("Communication", routeStatus.communicationDevice)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { vm.reinitializeAudioStack(context) },
                        enabled = !routeStatus.isResetting,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = StudioColors.Accent),
                    ) {
                        Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset stack", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { vm.refreshAudioDevices(context) },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, StudioColors.Border),
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rescan", fontSize = 11.sp)
                    }
                }

                HorizontalDivider(color = StudioColors.Border)
                RouteTimingControl(
                    title = "Release pause",
                    valueMs = releasePauseMs,
                    range = 100f..1500f,
                    help = "Pause after closing Oboe, AudioTrack and the old SCO route. " +
                        "Increase this if the phone's audio HAL keeps stale device handles.",
                    onValueChange = { releasePauseMs = it },
                    onValueCommitted = {
                        vm.setAudioReleasePause(context, releasePauseMs.toLong())
                    },
                )
                RouteTimingControl(
                    title = "Input settle pause",
                    valueMs = inputSettleMs,
                    range = 50f..1500f,
                    help = "Pause between opening the selected microphone and creating the " +
                        "monitor AudioTrack. Bluetooth and some USB HALs need this sequence.",
                    onValueChange = { inputSettleMs = it },
                    onValueCommitted = {
                        vm.setAudioInputSettle(context, inputSettleMs.toLong())
                    },
                )
                RouteTimingControl(
                    title = "SCO/BLE route timeout",
                    valueMs = routeTimeoutMs,
                    range = 2000f..20000f,
                    help = "Maximum wait for Android to confirm setCommunicationDevice. " +
                        "Bluetooth profile switching can be slow; increase this if the log " +
                        "shows the route arriving just after a timeout.",
                    onValueChange = { routeTimeoutMs = it },
                    onValueCommitted = {
                        vm.setAudioRouteTimeout(context, routeTimeoutMs.toLong())
                    },
                )
                RouteTimingControl(
                    title = "Physical output verification",
                    valueMs = outputVerifyMs,
                    range = 500f..8000f,
                    help = "How long MicUp waits for AudioTrack.routedDevice after playback " +
                        "starts muted. It never unmutes an unverified speaker fallback.",
                    onValueChange = { outputVerifyMs = it },
                    onValueCommitted = {
                        vm.setAudioOutputVerify(context, outputVerifyMs.toLong())
                    },
                )
            }

            // ── Shizuku ───────────────────────────────────────────────────────
            SettingsSection("Shizuku (ADB Access)") {
                val (stateColor, stateLabel) = when (shizukuState) {
                    ShizukuState.READY       -> Color(0xFF3DFCAC) to "READY"
                    ShizukuState.NEED_GRANT  -> Color(0xFFFFD700) to "NEED PERMISSION"
                    ShizukuState.UNAVAILABLE -> Color(0xFFFF6B6B) to "NOT RUNNING"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Status", fontSize = 12.sp, color = StudioColors.TextMuted)
                        Text(stateLabel, fontSize = 11.sp, color = stateColor)
                    }
                    if (shizukuState == ShizukuState.NEED_GRANT) {
                        Button(
                            onClick = { vm.requestShizukuPermission() },
                            colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF00B4D8)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Text("Grant", fontSize = 11.sp)
                        }
                    }
                }
                if (shizukuState == ShizukuState.UNAVAILABLE) {
                    Text(
                        "Install Shizuku from Play Store, start it via ADB or wireless debugging, then reopen this app.",
                        fontSize = 10.sp, color = StudioColors.TextMuted, lineHeight = 14.sp,
                    )
                }
                if (shizukuState == ShizukuState.READY) {
                    Text(
                        "Shizuku active — ALSA loopback and appops routing available.",
                        fontSize = 10.sp, color = StudioColors.TextMuted, lineHeight = 14.sp,
                    )
                }
            }

            // ── Virtual Mic ───────────────────────────────────────────────────
            SettingsSection("Virtual Microphone") {
                Surface(
                    shape  = RoundedCornerShape(7.dp),
                    color  = Color(tier.badgeColorHex).copy(alpha = 0.05f),
                    border = BorderStroke(0.5.dp, Color(tier.badgeColorHex).copy(alpha = 0.4f)),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ACTIVE TIER", fontSize = 8.sp, color = StudioColors.TextMuted, letterSpacing = 1.sp)
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape  = RoundedCornerShape(4.dp),
                                color  = Color(tier.badgeColorHex).copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, Color(tier.badgeColorHex)),
                            ) {
                                Text(tier.displayName, fontSize = 9.sp, color = Color(tier.badgeColorHex),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(tier.description, fontSize = 11.sp, color = StudioColors.TextMuted)
                    }
                }

                VirtualMicTier.values().forEach { t ->
                    val reason = vm.checkTierAvailability(t)
                    TierRow(
                        tier              = t,
                        isActive          = t == tier,
                        unavailableReason = reason,
                        onClick           = {
                            if (t == VirtualMicTier.SHIZUKU_ADB &&
                                shizukuState == ShizukuState.NEED_GRANT) {
                                vm.requestShizukuPermission()
                            } else {
                                vm.setTier(t)
                            }
                        },
                    )
                }
            }


            SettingsSection("Monitor Output Device") {
                val devices    by vm.outputDevices.collectAsState()
                val selectedId by vm.selectedOutputDeviceId.collectAsState()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    InfoButton(
                        title = "A2DP versus SCO",
                        text = "A2DP is the normal high-quality Bluetooth headphone output, " +
                            "but it has substantial latency. SCO is low-bandwidth call audio " +
                            "and supports a Bluetooth microphone. Selecting a Bluetooth mic " +
                            "forces the matching SCO/BLE communication output because Android " +
                            "cannot keep classic A2DP playback and SCO capture active together.",
                    )
                }
                if (devices.isEmpty()) {
                    Text("No devices found", fontSize = 12.sp, color = StudioColors.TextMuted)
                } else {
                    devices.forEach { device ->
                        val isSelected = device.id == selectedId
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setOutputDevice(context, device.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected,
                                onClick = { vm.setOutputDevice(context, device.id) },
                                colors  = RadioButtonDefaults.colors(selectedColor = StudioColors.Accent))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(device.name, fontSize = 12.sp,
                                    color = if (isSelected) StudioColors.Accent else StudioColors.TextPrimary)
                                Text(
                                    device.details,
                                    fontSize = 9.sp,
                                    color = StudioColors.TextMuted,
                                    lineHeight = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection("Input Device") {
                val devices    by vm.inputDevices.collectAsState()
                val selectedId by vm.selectedInputDeviceId.collectAsState()
                if (devices.isEmpty()) {
                    Text("No devices found", fontSize = 12.sp, color = StudioColors.TextMuted)
                } else {
                    devices.forEach { device ->
                        val isSelected = device.id == selectedId
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.setInputDevice(context, device.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected,
                                onClick = { vm.setInputDevice(context, device.id) },
                                colors  = RadioButtonDefaults.colors(selectedColor = StudioColors.Accent))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(device.name, fontSize = 12.sp,
                                    color = if (isSelected) StudioColors.Accent else StudioColors.TextPrimary)
                                Text(
                                    device.details,
                                    fontSize = 9.sp,
                                    color = StudioColors.TextMuted,
                                    lineHeight = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            SettingsSection("Audio Route Log") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Last ${routeEvents.size} routing events",
                        fontSize = 11.sp,
                        color = StudioColors.TextMuted,
                    )
                    InfoButton(
                        title = "Audio route log",
                        text = "Shows every stop, device inventory, communication-device " +
                            "request, preferred-device result and actual routed output. The " +
                            "persistent copy is capped at 256 KiB.",
                    )
                }
                Surface(
                    color = StudioColors.Background,
                    border = BorderStroke(0.5.dp, StudioColors.Border),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 220.dp),
                ) {
                    val logScroll = rememberScrollState()
                    LaunchedEffect(routeEvents.size) {
                        logScroll.animateScrollTo(logScroll.maxValue)
                    }
                    SelectionContainer {
                        Text(
                            routeEvents.takeLast(60).joinToString("\n").ifBlank {
                                "No route events yet. Tap Reset stack."
                            },
                            modifier = Modifier.padding(8.dp).verticalScroll(logScroll),
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            color = StudioColors.TextMuted,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val text = vm.exportAudioRouteLog(context)
                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("MicUp audio route log", text),
                            )
                            android.widget.Toast.makeText(
                                context,
                                "Audio route log copied",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, StudioColors.Border),
                    ) {
                        Text("Copy log", fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = { vm.clearAudioRouteLog(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clear log", fontSize = 11.sp, color = StudioColors.TextMuted)
                    }
                }
            }

            // ── Plugins ───────────────────────────────────────────────────────
            SettingsSection("Plugins") {
                SettingsActionRow("Manage Plugin Paths", Icons.Default.FolderOpen) { navController.navigate("plugin_paths") }
                SettingsActionRow("Rescan Plugin Directories", Icons.Default.Refresh) { vm.rescan() }
            }

            // ── About ─────────────────────────────────────────────────────────
            SettingsSection("About") {
                SettingsInfoRow("Version", com.micplugin.BuildConfig.VERSION_NAME)
                SettingsInfoRow("Build", "MicUp NR 1.1.1 · libspecbleach 0.4.1 · Oboe 1.8.1")
                SettingsInfoRow("Native", "C++17 · NDK 26 · AAudio/OpenSL ES")
            }

            // ── Crash Reports ─────────────────────────────────────────────────
            SettingsSection("Crash Reports") {
                if (crashReports.isEmpty()) {
                    Text("No crashes recorded.", fontSize = 12.sp, color = StudioColors.TextMuted)
                } else {
                    crashReports.forEach { file ->
                        Row(Modifier.fillMaxWidth().clickable { viewingCrash = file },
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(file.name.removePrefix("crash_").removeSuffix(".txt"),
                                fontSize = 11.sp, color = StudioColors.MeterRed)
                            Icon(Icons.Default.ChevronRight, null, tint = StudioColors.TextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SettingsActionRow("Clear All Crash Reports", Icons.Default.DeleteForever) {
                        CrashReporter.clearAll(context); crashReports = emptyList()
                    }
                }
            }
        }
    }

    viewingCrash?.let { file ->
        Dialog(onDismissRequest = { viewingCrash = null }) {
            Surface(shape = RoundedCornerShape(12.dp), color = StudioColors.Card,
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Crash Report", style = MaterialTheme.typography.titleSmall, color = StudioColors.MeterRed)
                        IconButton(onClick = { viewingCrash = null }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, tint = StudioColors.TextMuted)
                        }
                    }
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(color = StudioColors.Border); Spacer(Modifier.height(8.dp))
                    Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(file.readText(), fontSize = 9.sp, color = StudioColors.TextMuted,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { file.delete(); crashReports = CrashReporter.getCrashReports(context); viewingCrash = null },
                        modifier = Modifier.align(Alignment.End)) { Text("Delete", color = StudioColors.MeterRed) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        Surface(shape = RoundedCornerShape(8.dp), color = StudioColors.Card,
            border = BorderStroke(0.5.dp, StudioColors.Border), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = StudioColors.TextMuted)
        Text(value, fontSize = 12.sp, color = StudioColors.TextPrimary)
    }
}

@Composable
private fun AudioRouteDetail(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 9.sp, color = StudioColors.TextMuted)
        Text(
            value,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            color = StudioColors.TextPrimary,
        )
    }
}

@Composable
private fun RouteTimingControl(
    title: String,
    valueMs: Float,
    range: ClosedFloatingPointRange<Float>,
    help: String,
    onValueChange: (Float) -> Unit,
    onValueCommitted: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 11.sp, color = StudioColors.TextMuted)
                Spacer(Modifier.width(4.dp))
                InfoButton(title = title, text = help)
            }
            Text("${valueMs.toInt()} ms", fontSize = 11.sp, color = StudioColors.Accent)
        }
        Slider(
            value = valueMs,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueCommitted,
            valueRange = range,
            steps = 13,
            colors = SliderDefaults.colors(
                thumbColor = StudioColors.Accent,
                activeTrackColor = StudioColors.Accent,
                inactiveTrackColor = StudioColors.Border,
            ),
            modifier = Modifier.height(30.dp),
        )
    }
}

@Composable
private fun SettingsActionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = StudioColors.Accent)
        Icon(icon, null, tint = StudioColors.Accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TierRow(
    tier: VirtualMicTier,
    isActive: Boolean,
    unavailableReason: String?,
    onClick: () -> Unit,
) {
    val available = unavailableReason == null
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = available || isActive) { if (available) onClick() }
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected  = isActive,
                onClick   = if (available) onClick else null,
                colors    = RadioButtonDefaults.colors(
                    selectedColor   = Color(tier.badgeColorHex),
                    unselectedColor = if (available) StudioColors.TextMuted
                                     else StudioColors.TextMuted.copy(alpha = 0.35f),
                ),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                tier.displayName, fontSize = 12.sp,
                color = when {
                    isActive   -> Color(tier.badgeColorHex)
                    !available -> StudioColors.TextMuted.copy(alpha = 0.4f)
                    else       -> StudioColors.TextMuted
                },
                modifier = Modifier.weight(1f),
            )
            if (!available) {
                Icon(
                    Icons.Default.Lock, contentDescription = null,
                    tint     = StudioColors.TextMuted.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        if (unavailableReason != null) {
            Text(
                unavailableReason,
                fontSize   = 9.sp,
                color      = Color(0xFFFF6B6B).copy(alpha = 0.85f),
                lineHeight = 12.sp,
                modifier   = Modifier.padding(start = 48.dp, bottom = 2.dp),
            )
        }
    }
}
