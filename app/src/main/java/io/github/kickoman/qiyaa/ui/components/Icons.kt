package io.github.kickoman.qiyaa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

object IconPaths {
    const val VIEWBOX = 24f
    const val PLAY = "M7 4v16l14-8z"
    const val PAUSE = "M6 5h4v14H6zM14 5h4v14h-4z"
    const val PREVIOUS = "M5 5h3v14H5zM20 5v14L9 12z"
    const val NEXT = "M16 5h3v14h-3zM4 5v14l11-7z"
    const val STOP = "M5 5h14v14H5z"

    // Drawn as outlines (PathIcon's strokeWidth), except a filled heart for a liked track.
    const val HEART =
        "M12 20L4.4 12.3C3.5 11.4 3 10.2 3 8.9C3 6.2 5.1 4 7.7 4C9.5 4 11.1 5 12 6.5" +
            "C12.9 5 14.5 4 16.3 4C18.9 4 21 6.2 21 8.9C21 10.2 20.5 11.4 19.6 12.3Z"
    const val THUMB_DOWN =
        "M2.5 3.5H6.5V14H2.5ZM6.5 14L9.9 20.8C11.4 21.2 13 19.9 12.7 18.3L12.1 15H18.5" +
            "C19.9 15 20.9 13.7 20.6 12.3L19.2 5.2C19 4.2 18.1 3.5 17.1 3.5H6.5"
    const val OPEN_OUTSIDE =
        "M10 5H6C5.4 5 5 5.4 5 6V18C5 18.6 5.4 19 6 19H18C18.6 19 19 18.6 19 18V14" +
            "M13 5H19V11M19 5L11 13"
    const val OUTLINE = 1.8f
}

/** A 24×24 [IconPaths] path, filled, or as an outline [strokeWidth] units wide. */
@Composable
fun PathIcon(
    pathData: String,
    size: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float? = null,
) {
    val path: Path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    Canvas(modifier.size(size)) {
        val factor = this.size.minDimension / IconPaths.VIEWBOX
        scale(factor, pivot = Offset.Zero) {
            if (strokeWidth == null) {
                drawPath(path, color)
            } else {
                drawPath(
                    path,
                    color,
                    style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
