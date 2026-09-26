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

val PlexMono =
    FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.W400),
        Font(R.font.ibm_plex_mono_medium, FontWeight.W500),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.W600),
    )

val PlexSans =
    FontFamily(
        Font(R.font.ibm_plex_sans_regular, FontWeight.W400),
        Font(R.font.ibm_plex_sans_medium, FontWeight.W500),
        Font(R.font.ibm_plex_sans_semibold, FontWeight.W600),
    )

private val tightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

fun mono(
    size: TextUnit,
    weight: Int = 400,
    tracking: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = PlexMono,
    fontSize = size,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking,
    color = color,
    lineHeight = lineHeight,
    lineHeightStyle = tightLines,
)

fun sans(
    size: TextUnit,
    weight: Int = 400,
    color: Color = Color.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = TextStyle(
    fontFamily = PlexSans,
    fontSize = size,
    fontWeight = FontWeight(weight),
    color = color,
    lineHeight = lineHeight,
    lineHeightStyle = tightLines,
)

val CaptionStyle = mono(12.sp, 600, 2.sp)

val LabelStyle = mono(10.sp, 500, 2.sp)

val ButtonStyle = mono(11.sp, 500, 1.5.sp)

val ReadoutStyle = mono(10.sp, 400, 1.5.sp)

val HintStyle = mono(11.sp, 400, 1.sp)
