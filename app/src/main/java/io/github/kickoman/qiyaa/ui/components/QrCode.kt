package io.github.kickoman.qiyaa.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.nayuki.qrcodegen.QrCode

/** The four-module white margin the QR standard asks for. */
private const val QUIET_ZONE = 4

/** Dark modules on white, whatever the theme: scanners read that reliably. */
@Composable
fun QrCodeImage(text: String, modifier: Modifier = Modifier) {
    val code = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White),
    ) {
        val cell = size.minDimension / (code.size + 2 * QUIET_ZONE)
        for (y in 0 until code.size) {
            for (x in 0 until code.size) {
                if (code.getModule(x, y)) {
                    val at = Offset((x + QUIET_ZONE) * cell, (y + QUIET_ZONE) * cell)
                    // A hair wider than the cell, so neighbours meet without seams.
                    drawRect(Color.Black, at, Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
