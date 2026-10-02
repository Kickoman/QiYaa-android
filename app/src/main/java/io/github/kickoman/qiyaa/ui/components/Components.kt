package io.github.kickoman.qiyaa.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.theme.ButtonStyle
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.sans

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
    val colored = if (color != Color.Unspecified) style.copy(color = color) else style
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign != null) colored.copy(textAlign = textAlign) else colored,
        maxLines = maxLines,
        overflow = overflow,
    )
}

fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = this.then(
    Modifier.clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick),
)

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

@Composable
fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Qi.colors.surface,
    color: Color = Qi.colors.text,
    height: Dp = 44.dp,
    border: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    var shaped = modifier.height(height).clip(shape).background(background)
    if (border != null) shaped = shaped.border(1.dp, border, shape)
    Box(shaped.tap(onClick = onClick), contentAlignment = Alignment.Center) {
        QiText(text, ButtonStyle, color = color, maxLines = 1)
    }
}

/**
 * A button as wide as its [modifier] makes it, with an icon instead of a label (a label in
 * Belarusian or Russian may not fit). [description] is what a screen reader says.
 */
@Composable
fun IconWideButton(
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(Qi.colors.surface)
            .tap(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Like or unlike the current track: an outlined heart, filled in the accent colour once liked. */
@Composable
fun LikeButton(liked: Boolean, modifier: Modifier = Modifier, height: Dp = 44.dp, onClick: () -> Unit) {
    IconWideButton(
        stringResource(if (liked) R.string.player_liked else R.string.player_like),
        modifier,
        height,
        onClick,
    ) {
        if (liked) {
            PathIcon(IconPaths.HEART, 22.dp, Qi.colors.accent)
        } else {
            PathIcon(IconPaths.HEART, 22.dp, Qi.colors.muted, strokeWidth = IconPaths.OUTLINE)
        }
    }
}

@Composable
fun IconActionButton(
    modifier: Modifier = Modifier,
    background: Color = Qi.colors.surface,
    size: Dp = 44.dp,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun PillToggle(text: String, active: Boolean, onClick: () -> Unit) {
    val colors = Qi.colors
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) colors.accentBackground else colors.surface)
            .tap(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        QiText(text, ButtonStyle, color = if (active) colors.accent else colors.dim)
    }
}

@Composable
fun RoundButton(
    size: Dp,
    background: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun LedDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Chip(text: String, active: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val colors = Qi.colors
    val color = if (active) colors.accent else colors.text
    Row(
        Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) colors.accentBackground else colors.surface)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LedDot(color.copy(alpha = if (active) 1f else 0.25f))
        QiText(text, sans(13.sp, 500), color = color, maxLines = 1)
    }
}
