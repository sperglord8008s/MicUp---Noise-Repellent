package com.micplugin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micplugin.audio.AudioLevels
import com.micplugin.audio.EngineStatus
import com.micplugin.audio.NoiseRepellentStatus
import com.micplugin.audio.SpectrumFrame

@Composable
internal fun NoiseRepellentCard(
    vm: AudioViewModel,
    engineStatus: EngineStatus,
    state: NoiseRepellentState,
    status: NoiseRepellentStatus,
    spectrum: SpectrumFrame,
    levels: AudioLevels,
) {
    EffectCard(
        title = "Noise Repellent · Built in",
        enabled = state.enabled,
        onToggle = vm::setNoiseRepellentEnabled,
    ) {
        var advancedExpanded by remember { mutableStateOf(false) }
        val profileColor = when {
            state.learning -> StudioColors.MeterAmber
            status.profileReady -> StudioColors.MeterGreen
            else -> StudioColors.TextMuted
        }
        val profileLabel = when {
            state.learning -> "LEARNING ${state.secondsRemaining}s"
            status.profileReady -> "PROFILE READY"
            !engineStatus.isRunning -> "START AUDIO FIRST"
            !status.engineReady -> "ENGINE ERROR"
            else -> "NO PROFILE"
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Capture steady background noise, then subtract it live.",
                    color = StudioColors.TextMuted,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = profileColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.5.dp, profileColor),
                ) {
                    Text(
                        profileLabel,
                        color = profileColor,
                        fontSize = 8.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                "Keep the chestpiece still and away from the sound you want. " +
                    "Let only the fan, room, and mic self-noise through, then tap Learn.",
                color = StudioColors.TextMuted,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = vm::toggleNoiseLearning,
                    enabled = engineStatus.isRunning && status.engineReady,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.learning) StudioColors.MeterAmber else StudioColors.Accent,
                    ),
                ) {
                    Icon(
                        if (state.learning) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.learning) "Stop learning" else "Learn noise · 3 sec",
                        fontSize = 10.sp,
                    )
                }
                OutlinedButton(
                    onClick = vm::resetNoiseProfile,
                    enabled = status.profileReady && !state.learning,
                    border = BorderStroke(1.dp, StudioColors.Border),
                ) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset", fontSize = 10.sp)
                }
            }

            SpectrumPanel(
                frame = spectrum,
                status = status,
                levels = levels,
                onActiveChange = vm::setSpectrumEnabled,
            )

            HorizontalDivider(color = StudioColors.Border, thickness = 0.5.dp)
            ChoiceRow(
                title = "Quality / latency",
                helpText = "Live is a causal 512-sample temporal path (~11 ms NR latency at " +
                    "48 kHz). Balanced enables 2D processing with a 20 ms analysis frame " +
                    "(~40 ms NR latency). Studio doubles spectral resolution and NR latency. " +
                    "Changing this rebuilds the engine and clears the learned profile.",
                choices = listOf("Live" to 0, "Balanced" to 1, "Studio" to 2),
                selected = state.qualityMode,
                onSelected = vm::setNoiseQualityMode,
            )
            if (state.qualityMode == 0) {
                Text(
                    "Live mode forces Temporal smoothing. Choose Balanced or Studio for NLM/BM3D.",
                    color = StudioColors.MeterAmber,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                )
            }

            ParamSlider(
                "Broadband reduction", state.reductionDb, vm::setNoiseReduction,
                0f..40f, " dB",
                helpText = "The maximum attenuation applied to hiss, fan and room noise. " +
                    "Higher values remove more noise but can make body sounds watery. Start " +
                    "around 12–20 dB and increase only while the wanted signal stays natural.",
            )
            ParamSlider(
                "Tonal reduction", state.tonalReductionDb, vm::setNoiseTonalReduction,
                0f..40f, " dB",
                helpText = "Separate reduction floor for stable tones such as mains hum or " +
                    "electronic whine. It does not change broadband hiss reduction.",
            )
            ParamSlider(
                "Listen gain", state.outputGainDb, vm::setNoiseOutputGain,
                0f..30f, " dB",
                helpText = "Gain after Noise Repellent. It makes the monitor louder but does " +
                    "not improve denoising. Start low—stethoscope transients can be sudden.",
            )
            Text(
                "Start with low headphone volume. Bluetooth monitoring adds transport delay that " +
                    "is not included in the NR latency figure.",
                color = StudioColors.MeterAmber,
                fontSize = 9.sp,
                lineHeight = 12.sp,
            )

            HorizontalDivider(color = StudioColors.Border, thickness = 0.5.dp)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("Advanced DSP")
                IconButton(onClick = { advancedExpanded = !advancedExpanded }) {
                    Icon(
                        if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (advancedExpanded) "Hide advanced settings" else "Show advanced settings",
                        tint = StudioColors.Accent,
                    )
                }
            }

            if (advancedExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChoiceRow(
                        title = "Smoothing strategy",
                        helpText = "Temporal is fastest. NLM 2D compares nearby time/frequency " +
                            "patches. NLM + DFTT adds quefrency cleanup. BM3D groups similar " +
                            "patches and is usually the cleanest stable choice, but costs more CPU.",
                        choices = listOf(
                            "Temporal" to 0,
                            "NLM" to 1,
                            "NLM+DFTT" to 2,
                            "BM3D" to 3,
                        ),
                        selected = state.smoothingMode,
                        onSelected = vm::setNoiseSmoothingMode,
                        enabled = state.qualityMode != 0,
                    )
                    ParamSlider(
                        "Smoothing amount", state.smoothingPercent, vm::setNoiseSmoothing,
                        0f..100f, "%",
                        helpText = "Controls how strongly gain changes are smoothed. More can " +
                            "reduce musical-noise speckles, but too much smears rapid changes.",
                    )
                    if (state.smoothingMode == 2 && state.qualityMode != 0) {
                        ParamSlider(
                            "DFTT strength", state.dfttStrength, vm::setNoiseDfttStrength,
                            0f..4f, "×",
                            helpText = "Quefrency-domain cleanup strength used only by NLM + DFTT. " +
                                "Raise it for stubborn musical noise; lower it if fine detail dulls.",
                        )
                    }
                    ParamSlider(
                        "Signal protection", state.signalProtectionPercent, vm::setNoiseSignalProtection,
                        0f..100f, "%",
                        helpText = "Masking veto depth. Higher values protect bins likely to " +
                            "contain wanted signal; lower values permit stronger subtraction.",
                    )
                    ParamSlider(
                        "Suppression strength", state.suppressionPercent, vm::setNoiseSuppression,
                        0f..100f, "%",
                        helpText = "Scales SNR-dependent oversubtraction before the reduction " +
                            "floor. Lower this if the result pumps; raise it for stubborn noise.",
                    )
                    ParamSlider(
                        "Noise whitening", state.whiteningPercent, vm::setNoiseWhitening,
                        0f..100f, "%",
                        helpText = "Flattens the character of remaining noise. It can make residue " +
                            "less tonal, but high values may sound brighter or less natural.",
                    )
                    ParamSlider(
                        "Profile statistic", state.aggressiveness, vm::setNoiseAggressiveness,
                        -1f..1f,
                        helpText = "Chooses how learned profile statistics are steered: −1 leans " +
                            "toward median/minimum, 0 uses the mean, and +1 leans toward maxima. " +
                            "Positive values are more aggressive.",
                    )
                    ParamSlider(
                        "Broadband profile threshold", state.profileOffsetDb, vm::setNoiseProfileOffset,
                        -12f..12f, " dB",
                        helpText = "Moves the learned broadband noise profile. Positive values " +
                            "classify more energy as noise; negative values protect more signal.",
                    )
                    ParamSlider(
                        "Tonal profile threshold", state.tonalProfileOffsetDb, vm::setNoiseTonalProfileOffset,
                        -12f..12f, " dB",
                        helpText = "Moves only the tonal-noise profile used for hum and whistles. " +
                            "This lets you chase a tone without raising broadband subtraction.",
                    )

                    ToggleRow(
                        title = "Adaptive noise tracking",
                        subtitle = "Continuously follows a changing noise floor",
                        helpText = "Updates the profile while listening. Useful for changing fans " +
                            "or moving environments, but it can learn wanted sounds if they remain " +
                            "steady. Manual learning is safer for clinical listening.",
                        checked = state.adaptiveNoise,
                        onCheckedChange = vm::setNoiseAdaptive,
                    )
                    if (state.adaptiveNoise) {
                        ChoiceRow(
                            title = "Adaptive estimator",
                            helpText = "SPP-MMSE is the balanced default. Brandt uses a trimmed " +
                                "mean and can follow broad noise quickly. Martin minimum statistics " +
                                "is conservative. Changing estimator rebuilds NR and clears profile.",
                            choices = listOf("SPP-MMSE" to 0, "Brandt" to 1, "Martin" to 2),
                            selected = state.estimatorMethod,
                            onSelected = vm::setNoiseEstimatorMethod,
                        )
                    }
                    ToggleRow(
                        title = "Transient protection",
                        subtitle = "Preserves taps and body-sound onsets",
                        helpText = "Freezes reduction briefly in bins containing an onset. Keep it " +
                            "on for heart and lung sounds; turn it off only if impulsive noise leaks.",
                        checked = state.transientProtection,
                        onCheckedChange = vm::setNoiseTransientProtection,
                    )
                    ToggleRow(
                        title = "Hear removed noise",
                        subtitle = "Diagnostic residual-listen mode",
                        helpText = "Outputs what the denoiser removes. Wanted heart, breath or voice " +
                            "content in this signal means the settings are too aggressive.",
                        checked = state.residualListen,
                        onCheckedChange = vm::setNoiseResidualListen,
                    )
                    ToggleRow(
                        title = "Frequency reduction curve",
                        subtitle = "Apply more or less subtraction by frequency",
                        helpText = "Each fader biases reduction at that frequency. Positive values " +
                            "remove more; negative values protect that band. This is the closest " +
                            "control to ReaFIR's editable reduction shape.",
                        checked = state.curveEnabled,
                        onCheckedChange = vm::setNoiseCurveEnabled,
                    )
                    if (state.curveEnabled) {
                        val labels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            labels.forEachIndexed { index, label ->
                                EqBandFader(
                                    freq = label,
                                    gainDb = state.curveBandsDb.getOrElse(index) { 0f },
                                    onGainChange = { vm.setNoiseCurveBand(index, it) },
                                )
                            }
                        }
                        Text(
                            "Reduction bias (dB) · positive removes more",
                            fontSize = 8.sp,
                            color = StudioColors.TextMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    helpText: String,
    choices: List<Pair<String, Int>>,
    selected: Int,
    onSelected: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 10.sp, color = StudioColors.TextMuted)
            Spacer(Modifier.width(3.dp))
            InfoButton(title = title, text = helpText)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            choices.forEach { (label, value) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    label = { Text(label, fontSize = 9.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StudioColors.AccentDim,
                        selectedLabelColor = Color.White,
                        containerColor = StudioColors.CardElevated,
                        labelColor = StudioColors.TextMuted,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    helpText: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 10.sp, color = StudioColors.TextPrimary)
                Spacer(Modifier.width(3.dp))
                InfoButton(title = title, text = helpText)
            }
            Text(subtitle, fontSize = 8.sp, color = StudioColors.TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
