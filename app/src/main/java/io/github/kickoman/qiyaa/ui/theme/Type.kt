package io.github.kickoman.qiyaa.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.kickoman.qiyaa.R

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.W400),
    Font(R.font.ibm_plex_mono_medium, FontWeight.W500),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.W600),
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.W400),
    Font(R.font.ibm_plex_sans_medium, FontWeight.W500),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.W600),
)

private val tightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** `font: <weight> <size> 'IBM Plex Mono'` + optional letter-spacing, as written in the mock. */
fun mono(size: TextUnit, weight: Int = 400, tracking: TextUnit = TextUnit.Unspecified, color: Color = Color.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = PlexMono, fontSize = size, fontWeight = FontWeight(weight), letterSpacing = tracking, color = color, lineHeight = lineHeight, lineHeightStyle = tightLines)

fun sans(size: TextUnit, weight: Int = 400, color: Color = Color.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = PlexSans, fontSize = size, fontWeight = FontWeight(weight), color = color, lineHeight = lineHeight, lineHeightStyle = tightLines)

/** Section caption: mono 600 12sp, tracking 2 (e.g. "QIYAA", "PLAYLIST"). */
val CaptionStyle = mono(12.sp, 600, 2.sp)

/** Small mono label: 500 10sp tracking 2 ("PRESET", "STEP 1 · DEVICE CODE"). */
val LabelStyle = mono(10.sp, 500, 2.sp)

/** Button text: mono 500 11sp tracking 1.5. */
val ButtonStyle = mono(11.sp, 500, 1.5.sp)
