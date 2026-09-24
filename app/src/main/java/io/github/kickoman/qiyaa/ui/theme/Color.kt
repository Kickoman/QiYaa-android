package io.github.kickoman.qiyaa.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.kickoman.qiyaa.data.AccentTheme

/** Design tokens from "Winamp Mobile Flow": cool near-black surfaces + one LED accent. */
@Immutable
data class QiColors(
    val bg: Color = Color(0xFF15161A),
    val text: Color = Color(0xFFD9DBE0),
    val sub: Color = Color(0xFFA7ABB5),
    val muted: Color = Color(0xFF8B8F99),
    val dim: Color = Color(0xFF6C707A),
    val dimmer: Color = Color(0xFF4A4E58),
    val surface: Color = Color(0xFF1C1E23),
    val surface2: Color = Color(0xFF22242A),
    val border: Color = Color(0xFF2B2E36),
    val deep: Color = Color(0xFF0C0D10),
    val insetBorder: Color = Color(0xFF1E2026),
    val tabBg: Color = Color(0xFF101114),
    val handle: Color = Color(0xFF3A3E48),
    val error: Color = Color(0xFFFA6863), // oklch(0.7 0.18 25)
    val acc: Color,
    val acc2: Color,
    val accBg: Color,
)

/** Accent triples are the sRGB renderings of the mock's OKLCH values. */
fun accentColors(theme: AccentTheme): QiColors = when (theme) {
    // oklch(0.8 0.21 150) / oklch(0.55 0.14 150) / oklch(0.28 0.06 150)
    AccentTheme.CLASSIC_GREEN -> QiColors(acc = Color(0xFF29E16F), acc2 = Color(0xFF1C8742), accBg = Color(0xFF0F3118))
    // oklch(0.82 0.17 75) / oklch(0.56 0.12 75) / oklch(0.28 0.05 75)
    AccentTheme.AMBER -> QiColors(acc = Color(0xFFFFB113), acc2 = Color(0xFF9D6800), accBg = Color(0xFF372508))
    // oklch(0.82 0.12 220) / oklch(0.56 0.1 220) / oklch(0.28 0.04 220)
    AccentTheme.ICE_BLUE -> QiColors(acc = Color(0xFF59D6FA), acc2 = Color(0xFF0A819D), accBg = Color(0xFF0E2D36))
}

val LocalQiColors = staticCompositionLocalOf { accentColors(AccentTheme.AMBER) }
