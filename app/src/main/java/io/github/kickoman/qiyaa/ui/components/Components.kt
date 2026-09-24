package io.github.kickoman.qiyaa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kickoman.qiyaa.ui.theme.ButtonStyle
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import androidx.compose.foundation.text.BasicText

/** Plain text without Material padding; the app draws everything by hand. */
@Composable
fun QiText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val s = if (color != Color.Unspecified) style.copy(color = color) else style
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign != null) s.copy(textAlign = textAlign) else s,
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** Tap without ripple (the mock has flat "style-active" states instead). */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick),
)

/** 48dp screen header: caption on the left, anything on the right. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, right: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        QiText(title, CaptionStyle, color = Qi.colors.muted)
        Row(verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

/** Flat rounded button, mono 500 11sp tracking 1.5 (ADD / REM / LIKE …). */
@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    bg: Color = Qi.colors.surface,
    color: Color = Qi.colors.text,
    height: Dp = 44.dp,
    border: Color? = null,
    onClick: () -> Unit,
) {
    var m = modifier
        .height(height)
        .clip(RoundedCornerShape(8.dp))
        .background(bg)
    if (border != null) m = m.border(1.dp, border, RoundedCornerShape(8.dp))
    Box(m.tap(onClick = onClick), contentAlignment = Alignment.Center) {
        QiText(text, ButtonStyle, color = color, maxLines = 1)
    }
}

/** Square icon button with the same look as [ActionButton]. */
@Composable
fun IconActionButton(
    modifier: Modifier = Modifier,
    bg: Color = Qi.colors.surface,
    size: Dp = 44.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 32dp pill toggle (ON / AUTO). */
@Composable
fun PillToggle(text: String, active: Boolean, onClick: () -> Unit) {
    val c = Qi.colors
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) c.accBg else c.surface)
            .tap(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        QiText(text, ButtonStyle, color = if (active) c.acc else c.dim)
    }
}

/** Round transport button. */
@Composable
fun RoundButton(size: Dp, bg: Color, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Small LED dot (status line, mini player). */
@Composable
fun LedDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** 40dp chip (stations / playlists). */
@Composable
fun Chip(text: String, active: Boolean, onClick: () -> Unit) {
    val c = Qi.colors
    val color = if (active) c.acc else c.text
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) c.accBg else c.surface)
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LedDot(color.copy(alpha = if (active) 1f else 0.25f))
        QiText(text, io.github.kickoman.qiyaa.ui.theme.sans(13.sp, 500), color = color, maxLines = 1)
    }
}

/** Label for small mono readouts (10sp, tracking 1.5). */
val ReadoutStyle = mono(10.sp, 400, 1.5.sp)
