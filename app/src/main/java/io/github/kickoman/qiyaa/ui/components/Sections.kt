package io.github.kickoman.qiyaa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
internal fun SectionLabel(text: String) {
    QiText(
        text.uppercase(),
        LabelStyle,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        color = Qi.colors.dim,
    )
}

@Composable
internal fun Note(text: String, color: Color = Qi.colors.dim) {
    QiText(
        text,
        mono(11.sp, 400, lineHeight = 17.sp),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        color = color,
    )
}

@Composable
internal fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Qi.colors.surface),
        content = content,
    )
}

@Composable
internal fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Qi.colors.background))
}

@Composable
internal fun SettingRow(label: String, pills: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QiText(label, sans(14.sp, 500), Modifier.weight(1f), color = Qi.colors.text, maxLines = 2)
        pills()
    }
}

@Composable
internal fun InsetField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onAction: () -> Unit = {},
) {
    val colors = Qi.colors
    val style = mono(13.sp, 400).copy(color = colors.text)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.deep)
            .border(1.dp, colors.insetBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onAction() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) {
                    QiText(hint, style, color = colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            },
        )
    }
}
