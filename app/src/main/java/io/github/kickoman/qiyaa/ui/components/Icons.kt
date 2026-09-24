package io.github.kickoman.qiyaa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** The SVG path icons used by the mock (24×24 viewBox). */
object IconPaths {
    const val PLAY = "M7 4v16l14-8z"
    const val PAUSE = "M6 5h4v14H6zM14 5h4v14h-4z"
    const val PREV = "M5 5h3v14H5zM20 5v14L9 12z"
    const val NEXT = "M16 5h3v14h-3zM4 5v14l11-7z"
    const val STOP = "M5 5h14v14H5z"
}

@Composable
fun PathIcon(pathData: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val path: Path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color)
        }
    }
}
