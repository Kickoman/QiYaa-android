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
import io.github.kickoman.qiyaa.audio.EqSettings
import io.github.kickoman.qiyaa.ui.theme.Qi
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HORIZONTAL_PAD_PX = 24f
private val VERTICAL_THUMB_RADIUS = 14.dp

@Composable
fun HorizontalSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    accent: Color = Qi.colors.accent,
    onChangeFinished: ((Float) -> Unit)? = null,
) {
    val colors = Qi.colors
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
                detectTapGestures { position ->
                    val fraction = horizontalFraction(position.x, size.width)
                    latestChange(fraction)
                    latestFinished?.invoke(fraction)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        dragging = true
                        dragValue = horizontalFraction(position.x, size.width)
                        latestChange(dragValue)
                    },
                    onDragEnd = {
                        dragging = false
                        latestFinished?.invoke(dragValue)
                    },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    dragValue = horizontalFraction(change.position.x, size.width)
                    latestChange(dragValue)
                }
            },
    ) {
        val trackHeight = 4.dp.toPx()
        val centerY = size.height / 2f
        val startX = HORIZONTAL_PAD_PX
        val endX = size.width - HORIZONTAL_PAD_PX
        val radius = CornerRadius(trackHeight / 2f)
        val trackTop = Offset(startX, centerY - trackHeight / 2f)
        drawRoundRect(colors.deep, trackTop, Size(endX - startX, trackHeight), radius)
        drawRoundRect(colors.border, trackTop, Size(endX - startX, trackHeight), radius, style = Stroke(1f))
        val valueX = startX + (endX - startX) * shown
        drawRoundRect(accent, trackTop, Size(valueX - startX, trackHeight), radius)
        drawCircle(accent, radius = 8.dp.toPx(), center = Offset(valueX, centerY))
        drawCircle(colors.deep, radius = 3.dp.toPx(), center = Offset(valueX, centerY))
    }
}

private fun horizontalFraction(x: Float, width: Int): Float =
    ((x - HORIZONTAL_PAD_PX) / (width - 2 * HORIZONTAL_PAD_PX)).coerceIn(0f, 1f)

@Composable
fun VerticalFader(
    value: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    thumbColor: Color = Qi.colors.accent,
    enabled: Boolean = true,
) {
    val colors = Qi.colors
    val latestChange by rememberUpdatedState(onChange)
    val maxDb = EqSettings.MAX_DB

    Canvas(
        modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val thumbRadius = VERTICAL_THUMB_RADIUS.toPx()
                detectDragGestures(
                    onDragStart = { position ->
                        latestChange(faderDb(position.y, size.height, thumbRadius, maxDb))
                    },
                ) { change, _ ->
                    change.consume()
                    latestChange(faderDb(change.position.y, size.height, thumbRadius, maxDb))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val thumbRadius = VERTICAL_THUMB_RADIUS.toPx()
                detectTapGestures { position ->
                    latestChange(faderDb(position.y, size.height, thumbRadius, maxDb))
                }
            },
    ) {
        val trackWidth = 6.dp.toPx()
        val thumbRadius = VERTICAL_THUMB_RADIUS.toPx()
        val centerX = size.width / 2f
        val top = thumbRadius
        val bottom = size.height - thumbRadius
        val radius = CornerRadius(trackWidth / 2f)
        val trackLeft = centerX - trackWidth / 2f
        drawRoundRect(colors.deep, Offset(trackLeft, 0f), Size(trackWidth, size.height), radius)
        drawRoundRect(
            colors.border,
            Offset(trackLeft, 0f),
            Size(trackWidth, size.height),
            radius,
            style = Stroke(1f),
        )
        val fraction = ((value + maxDb) / (2 * maxDb)).coerceIn(0.0, 1.0).toFloat()
        val valueY = bottom - (bottom - top) * fraction
        val middleY = (top + bottom) / 2f
        val fillTop = minOf(valueY, middleY)
        val fillHeight = abs(valueY - middleY)
        if (fillHeight >
            0f
        ) {
            drawRoundRect(
                colors.accentDark,
                Offset(trackLeft, fillTop),
                Size(trackWidth, fillHeight),
                radius,
            )
        }
        drawCircle(colors.border, radius = thumbRadius, center = Offset(centerX, valueY))
        drawCircle(
            thumbColor,
            radius = thumbRadius,
            center = Offset(centerX, valueY),
            style = Stroke(2.dp.toPx()),
        )
    }
}

private fun faderDb(y: Float, height: Int, thumbRadius: Float, maxDb: Double): Double {
    val fraction = 1f - ((y - thumbRadius) / (height - 2 * thumbRadius)).coerceIn(0f, 1f)
    val db = fraction * 2 * maxDb - maxDb
    return (db * 10).roundToInt() / 10.0
}
