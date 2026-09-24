package io.github.kickoman.qiyaa.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import io.github.kickoman.qiyaa.data.AccentTheme

object Qi {
    val colors: QiColors
        @Composable @ReadOnlyComposable get() = LocalQiColors.current
}

@Composable
fun QiYaaTheme(accent: AccentTheme, content: @Composable () -> Unit) {
    val colors = remember(accent) { accentColors(accent) }
    val scheme: ColorScheme = darkColorScheme(
        primary = colors.acc,
        onPrimary = colors.deep,
        secondary = colors.acc2,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surface2,
        onSurfaceVariant = colors.muted,
        outline = colors.border,
        error = colors.error,
    )
    CompositionLocalProvider(LocalQiColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography.copy(bodyLarge = sans(14.sp).copy(color = colors.text)),
            content = content,
        )
    }
}

/** Default text style for the app body: Plex Sans 14sp in the text color. */
val BodyStyle: TextStyle
    @Composable @ReadOnlyComposable get() = sans(14.sp, 400, Qi.colors.text)
