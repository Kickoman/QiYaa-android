package io.github.kickoman.qiyaa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.kickoman.qiyaa.ui.theme.Qi

/**
 * Horizontal slider in the mock's `input[type=range]` look: thin inset track, accent fill,
 * round thumb. [value] is 0..1. Dragging reports continuously; [onChangeFinished] fires on release.
 */
@Composable
fun HSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    accent: Color = Qi.colors.acc,
    onChangeFinished: ((Float) -> Unit)? = null,
) {
    val c = Qi.colors
    val latestChange by rememberUpdatedState(onChange)
    val latestFinished by rememberUpdatedState(onChangeFinished)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(value) }
    val shown = if (dragging) dragValue else value.coerceIn(0f, 1f)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    val v = ((pos.x - PAD_PX) / (size.width - 2 * PAD_PX)).coerceIn(0f, 1f)
                    latestChange(v)
                    latestFinished?.invoke(v)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        dragging = true
                        dragValue = ((pos.x - PAD_PX) / (size.width - 2 * PAD_PX)).coerceIn(0f, 1f)
                        latestChange(dragValue)
                    },
                    onDragEnd = {
                        dragging = false
                        latestFinished?.invoke(dragValue)
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    dragValue = ((change.position.x - PAD_PX) / (size.width - 2 * PAD_PX)).coerceIn(0f, 1f)
                    latestChange(dragValue)
                }
            },
    ) {
        val trackH = 4.dp.toPx()
        val y = size.height / 2f
        val x0 = PAD_PX
        val x1 = size.width - PAD_PX
        val r = CornerRadius(trackH / 2f)
        drawRoundRect(c.deep, Offset(x0, y - trackH / 2f), Size(x1 - x0, trackH), r)
        drawRoundRect(c.border, Offset(x0, y - trackH / 2f), Size(x1 - x0, trackH), r, style = Stroke(1f))
        val xv = x0 + (x1 - x0) * shown
        drawRoundRect(accent, Offset(x0, y - trackH / 2f), Size(xv - x0, trackH), r)
        drawCircle(accent, radius = 8.dp.toPx(), center = Offset(xv, y))
        drawCircle(c.deep, radius = 3.dp.toPx(), center = Offset(xv, y))
    }
}

private const val PAD_PX = 24f

/**
 * Vertical EQ fader: 6dp inset track, fill from the centre (acc2), 28dp thumb.
 * [value] is in dB (−12..12).
 */
@Composable
fun VerticalFader(
    value: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    thumbColor: Color = Qi.colors.acc,
    enabled: Boolean = true,
) {
    val c = Qi.colors
    val latestChange by rememberUpdatedState(onChange)
    val max = 12.0

    Canvas(
        modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun at(y: Float): Double {
                    val thumb = 14.dp.toPx()
                    val frac = 1f - ((y - thumb) / (size.height - 2 * thumb)).coerceIn(0f, 1f)
                    return (frac * 2 * max - max).let { Math.round(it * 10.0) / 10.0 }
                }
                detectDragGestures(
                    onDragStart = { pos -> latestChange(at(pos.y)) },
                ) { change, _ ->
                    change.consume()
                    latestChange(at(change.position.y))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { pos ->
                    val thumb = 14.dp.toPx()
                    val frac = 1f - ((pos.y - thumb) / (size.height - 2 * thumb)).coerceIn(0f, 1f)
                    latestChange(Math.round((frac * 2 * max - max) * 10.0) / 10.0)
                }
            },
    ) {
        val w = 6.dp.toPx()
        val thumbR = 14.dp.toPx()
        val cx = size.width / 2f
        val top = thumbR
        val bottom = size.height - thumbR
        val r = CornerRadius(w / 2f)
        drawRoundRect(c.deep, Offset(cx - w / 2f, 0f), Size(w, size.height), r)
        drawRoundRect(c.border, Offset(cx - w / 2f, 0f), Size(w, size.height), r, style = Stroke(1f))
        val frac = ((value + max) / (2 * max)).coerceIn(0.0, 1.0).toFloat()
        val yv = bottom - (bottom - top) * frac
        val yMid = (top + bottom) / 2f
        val fillTop = minOf(yv, yMid)
        val fillH = kotlin.math.abs(yv - yMid)
        if (fillH > 0f) drawRoundRect(c.acc2, Offset(cx - w / 2f, fillTop), Size(w, fillH), r)
        drawCircle(c.border, radius = thumbR, center = Offset(cx, yv))
        drawCircle(thumbColor, radius = thumbR, center = Offset(cx, yv), style = Stroke(2.dp.toPx()))
    }
}
