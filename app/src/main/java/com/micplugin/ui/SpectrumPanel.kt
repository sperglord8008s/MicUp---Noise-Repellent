package com.micplugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micplugin.audio.AudioLevels
import com.micplugin.audio.NoiseRepellentStatus
import com.micplugin.audio.SpectrumFrame
import kotlin.math.exp
import kotlin.math.ln

private val SpectrumInput = Color(0xFF41D8FF)
private val SpectrumOutput = StudioColors.MeterGreen
private val SpectrumNoise = StudioColors.MeterAmber

@Composable
internal fun SpectrumPanel(
    frame: SpectrumFrame,
    status: NoiseRepellentStatus,
    levels: AudioLevels,
    onActiveChange: (Boolean) -> Unit,
) {
    DisposableEffect(Unit) {
        onActiveChange(true)
        onDispose { onActiveChange(false) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Live spectrum")
            InfoButton(
                title = "Live spectrum",
                text = "A 4096-point display-only FFT. Cyan is raw microphone input, " +
                    "amber is the learned noise profile, and green is the final processed " +
                    "monitor signal. The graph runs only while this panel is visible, so " +
                    "collapsing the Noise Repellent card removes its CPU cost.",
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(StudioColors.Background, RoundedCornerShape(6.dp))
                .border(1.dp, StudioColors.Border, RoundedCornerShape(6.dp)),
        ) {
            val grid = StudioColors.Border.copy(alpha = 0.75f)
            listOf(0f, -20f, -40f, -60f, -80f, -100f, -120f).forEach { db ->
                val y = dbToY(db)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            val nyquist = frame.sampleRate.coerceAtLeast(1) / 2f
            listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
                .filter { it < nyquist }
                .forEach { frequency ->
                    val x = frequencyToX(frequency, nyquist)
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
                }

            drawSpectrum(frame.noiseDb, frame, SpectrumNoise, 1.5f)
            drawSpectrum(frame.inputDb, frame, SpectrumInput, 1.4f)
            drawSpectrum(frame.outputDb, frame, SpectrumOutput, 1.8f)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("20 Hz", fontSize = 8.sp, color = StudioColors.TextMuted)
            Text("100", fontSize = 8.sp, color = StudioColors.TextMuted)
            Text("1k", fontSize = 8.sp, color = StudioColors.TextMuted)
            Text("10k", fontSize = 8.sp, color = StudioColors.TextMuted)
            Text("${frame.sampleRate / 2000}k Hz", fontSize = 8.sp, color = StudioColors.TextMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SpectrumLegend(SpectrumInput, "Input")
            SpectrumLegend(SpectrumNoise, "Noise profile")
            SpectrumLegend(SpectrumOutput, "Output")
        }

        Surface(
            color = StudioColors.CardElevated,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel("Audio feed")
                    InfoButton(
                        title = "Audio feed diagnostics",
                        text = "RMS is average signal energy; peak catches brief overloads; " +
                            "crest is the peak-to-average gap. DC should stay close to zero. " +
                            "Clipping should be 0%. Short reads mean Android did not provide a " +
                            "complete microphone block in time and may indicate a dropout.",
                    )
                }
                DiagnosticRow(
                    "Input",
                    "RMS ${formatDb(levels.inputDb)}",
                    "Peak ${formatDb(status.inputPeakDb)}",
                )
                DiagnosticRow(
                    "Health",
                    "Crest ${"%.1f".format((status.inputPeakDb - levels.inputDb).coerceAtLeast(0f))} dB",
                    "Clip ${"%.3f".format(status.inputClipPercent)}%",
                )
                DiagnosticRow(
                    "Capture",
                    "DC ${"%.5f".format(status.inputDcOffset)}",
                    "Short reads ${status.shortReadCount}",
                )
                DiagnosticRow(
                    "NR geometry",
                    "${status.frameSize}/${status.fftSize} frame/FFT",
                    "hop ${status.hopSize}",
                )
                DiagnosticRow(
                    "Profile",
                    "${status.profileBins} bins · ${status.profileBlocks} blocks",
                    if (status.transientDetected) {
                        "Transient ${"%.0f".format(status.transientIntensity * 100f)}%"
                    } else {
                        "No transient"
                    },
                )
                if (status.tonalPeaksHz.isNotEmpty()) {
                    Text(
                        "Tonal peaks: " + status.tonalPeaksHz.joinToString { "%.0f Hz".format(it) },
                        fontSize = 8.sp,
                        color = StudioColors.TextMuted,
                    )
                }
            }
        }
    }
}

private fun DrawScope.dbToY(db: Float): Float =
    size.height * ((0f - db.coerceIn(-120f, 0f)) / 120f)

private fun DrawScope.frequencyToX(frequency: Float, nyquist: Float): Float {
    val minimum = 20f
    val maximum = nyquist.coerceAtLeast(minimum + 1f)
    val fraction = (ln(frequency.coerceAtLeast(minimum) / minimum) /
        ln(maximum / minimum)).coerceIn(0f, 1f)
    return size.width * fraction
}

private fun DrawScope.drawSpectrum(
    values: FloatArray,
    frame: SpectrumFrame,
    color: Color,
    strokeWidth: Float,
) {
    if (values.size < 2 || frame.fftSize <= 0 || frame.sampleRate <= 0) return
    val pointCount = size.width.toInt().coerceIn(96, 384)
    val nyquist = frame.sampleRate / 2f
    val minimum = 20f
    val logRange = ln(nyquist / minimum)
    val path = Path()
    for (point in 0..pointCount) {
        val fraction = point.toFloat() / pointCount.toFloat()
        val frequency = minimum * exp(logRange * fraction)
        val sourceBin = (frequency * frame.fftSize / frame.sampleRate)
            .coerceIn(0f, (values.size - 1).toFloat())
        val lower = sourceBin.toInt()
        val upper = (lower + 1).coerceAtMost(values.lastIndex)
        val db = values[lower] +
            (sourceBin - lower.toFloat()) * (values[upper] - values[lower])
        val x = size.width * fraction
        val y = dbToY(db)
        if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth))
}

@Composable
private fun SpectrumLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(10.dp).height(2.dp).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 8.sp, color = StudioColors.TextMuted)
    }
}

@Composable
private fun DiagnosticRow(label: String, first: String, second: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 8.sp, color = StudioColors.TextMuted, modifier = Modifier.weight(0.8f))
        Text(first, fontSize = 8.sp, color = StudioColors.TextPrimary, modifier = Modifier.weight(1.25f))
        Text(second, fontSize = 8.sp, color = StudioColors.TextPrimary, modifier = Modifier.weight(1.1f))
    }
}

private fun formatDb(value: Float): String =
    if (value <= -100f) "−∞" else "${"%.1f".format(value)} dB"
