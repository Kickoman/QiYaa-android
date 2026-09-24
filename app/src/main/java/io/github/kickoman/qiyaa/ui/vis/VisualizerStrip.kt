package io.github.kickoman.qiyaa.ui.vis

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.kickoman.qiyaa.audio.Analyzer
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.audio.Spectrum
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

const val VIS_SPECTRUM = 0
const val VIS_SCOPE = 1
const val VIS_OFF = 2

/**
 * The 36dp strip over the cover: spectrum (19 bars with peak markers) → oscilloscope → off.
 * Redraws ~30 times a second only while something plays, like the desktop app.
 */
@Composable
fun VisualizerStrip(mode: Int, playing: Boolean, bus: AudioBus, offLabel: String, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val c = Qi.colors
    val analyzer = remember { Analyzer(1024) }
    val spectrum = remember { Spectrum(19) }
    val left = remember { FloatArray(1024) }
    val right = remember { FloatArray(1024) }
    val mono = remember { FloatArray(1024) }
    val db = remember { FloatArray(513) }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(mode, playing) {
        if (mode == VIS_OFF) return@LaunchedEffect
        if (!playing) {
            spectrum.reset()
            frame++
            return@LaunchedEffect
        }
        while (isActive) {
            bus.visTap.read(left, right, 1024)
            for (i in 0 until 1024) mono[i] = 0.5f * (left[i] + right[i])
            if (mode == VIS_SPECTRUM) {
                analyzer.analyze(mono, db)
                spectrum.update(db, 1024, bus.visTap.sampleRate)
            }
            frame++
            delay(33)
        }
    }

    Box(modifier.tap(onClick = onTap), contentAlignment = Alignment.Center) {
        if (mode == VIS_OFF) {
            QiText(offLabel, mono(10.sp, 400, 1.5.sp), color = c.dim)
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame // subscribe to the ticker
            val h = size.height
            if (mode == VIS_SPECTRUM) {
                val n = spectrum.bars
                val gap = 2.dp.toPx()
                val bw = (size.width - gap * (n - 1)) / n
                for (b in 0 until n) {
                    val level = if (playing) spectrum.levels[b] else 0.06f
                    val bh = (h * level).coerceAtLeast(1f)
                    val x = b * (bw + gap)
                    drawRoundRect(c.acc, Offset(x, h - bh), Size(bw, bh), CornerRadius(1f))
                    if (playing) {
                        val py = h - h * spectrum.peaks[b]
                        drawRect(c.acc, Offset(x, py - 1f), Size(bw, 2f))
                    }
                }
            } else {
                // Oscilloscope: the last 576 frames, like Winamp's 75-px window.
                val window = 576
                val start = 1024 - window
                val path = Path()
                val mid = h / 2f
                for (x in 0 until window) {
                    val v = if (playing) mono[start + x] else 0f
                    val px = size.width * x / (window - 1)
                    val py = (mid - v * mid).coerceIn(0f, h)
                    if (x == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, c.acc, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}
