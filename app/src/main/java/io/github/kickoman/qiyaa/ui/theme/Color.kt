package io.github.kickoman.qiyaa.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.kickoman.qiyaa.data.AccentTheme

@Immutable
data class QiColors(
    val background: Color = Color(0xFF15161A),
    val text: Color = Color(0xFFD9DBE0),
    val textSecondary: Color = Color(0xFFA7ABB5),
    val muted: Color = Color(0xFF8B8F99),
    val dim: Color = Color(0xFF6C707A),
    val dimmer: Color = Color(0xFF4A4E58),
    val surface: Color = Color(0xFF1C1E23),
    val surface2: Color = Color(0xFF22242A),
    val border: Color = Color(0xFF2B2E36),
    val deep: Color = Color(0xFF0C0D10),
    val insetBorder: Color = Color(0xFF1E2026),
    val tabBackground: Color = Color(0xFF101114),
    val handle: Color = Color(0xFF3A3E48),
    val error: Color = Color(0xFFFA6863),
    val accent: Color,
    val accentDark: Color,
    val accentBackground: Color,
)

// sRGB renderings of the mock's OKLCH accents; the exact values are listed in ui/README.md.
fun accentColors(theme: AccentTheme): QiColors = when (theme) {
    AccentTheme.CLASSIC_GREEN ->
        QiColors(
            accent = Color(0xFF29E16F),
            accentDark = Color(0xFF1C8742),
            accentBackground = Color(0xFF0F3118),
        )
    AccentTheme.AMBER ->
        QiColors(
            accent = Color(0xFFFFB113),
            accentDark = Color(0xFF9D6800),
            accentBackground = Color(0xFF372508),
        )
    AccentTheme.ICE_BLUE ->
        QiColors(
            accent = Color(0xFF59D6FA),
            accentDark = Color(0xFF0A819D),
            accentBackground = Color(0xFF0E2D36),
        )
}

val LocalQiColors = staticCompositionLocalOf { accentColors(AccentTheme.DEFAULT) }
