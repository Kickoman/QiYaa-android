package io.github.kickoman.qiyaa.ui.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.kickoman.qiyaa.audio.Analyzer
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.audio.Spectrum
import io.github.kickoman.qiyaa.data.VisualizerMode
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val FFT_SIZE = Analyzer.DEFAULT_SIZE
private const val FRAME_MS = 33L
private const val SCOPE_WINDOW = 576
private const val IDLE_BAR_LEVEL = 0.06f

@Composable
fun VisualizerStrip(
    mode: VisualizerMode,
    playing: Boolean,
    audioBus: AudioBus,
    offLabel: String,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    val colors = Qi.colors
    val analyzer = remember { Analyzer(FFT_SIZE) }
    val spectrum = remember { Spectrum() }
    val left = remember { FloatArray(FFT_SIZE) }
    val right = remember { FloatArray(FFT_SIZE) }
    val mono = remember { FloatArray(FFT_SIZE) }
    val spectrumDb = remember { FloatArray(analyzer.binCount) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(mode, playing) {
        if (mode == VisualizerMode.OFF) return@LaunchedEffect
        if (!playing) {
            spectrum.reset()
            frame++
            return@LaunchedEffect
        }
        while (isActive) {
            audioBus.visualizerTap.read(left, right, FFT_SIZE)
            for (i in 0 until FFT_SIZE) mono[i] = 0.5f * (left[i] + right[i])
            if (mode == VisualizerMode.SPECTRUM) {
                analyzer.analyze(mono, spectrumDb)
                spectrum.update(spectrumDb, FFT_SIZE, audioBus.visualizerTap.sampleRate)
            }
            frame++
            delay(FRAME_MS)
        }
    }

    Box(modifier.tap(onClick = onTap), contentAlignment = Alignment.Center) {
        if (mode == VisualizerMode.OFF) {
            QiText(offLabel, ReadoutStyle, color = colors.dim)
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            frame
            if (mode == VisualizerMode.SPECTRUM) {
                drawSpectrum(spectrum, playing, colors.accent)
            } else {
                drawScope(mono, playing, colors.accent)
            }
        }
    }
}

private fun DrawScope.drawSpectrum(spectrum: Spectrum, playing: Boolean, color: Color) {
    val height = size.height
    val barCount = spectrum.barCount
    val gap = 2.dp.toPx()
    val barWidth = (size.width - gap * (barCount - 1)) / barCount
    for (bar in 0 until barCount) {
        val level = if (playing) spectrum.levels[bar] else IDLE_BAR_LEVEL
        val barHeight = (height * level).coerceAtLeast(1f)
        val x = bar * (barWidth + gap)
        drawRoundRect(color, Offset(x, height - barHeight), Size(barWidth, barHeight), CornerRadius(1f))
        if (playing) {
            val peakY = height - height * spectrum.peaks[bar]
            drawRect(color, Offset(x, peakY - 1f), Size(barWidth, 2f))
        }
    }
}

private fun DrawScope.drawScope(mono: FloatArray, playing: Boolean, color: Color) {
    val height = size.height
    val start = FFT_SIZE - SCOPE_WINDOW
    val path = Path()
    val middle = height / 2f
    for (i in 0 until SCOPE_WINDOW) {
        val sample = if (playing) mono[start + i] else 0f
        val x = size.width * i / (SCOPE_WINDOW - 1)
        val y = (middle - sample * middle).coerceIn(0f, height)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
}
