package io.github.kickoman.qiyaa.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kickoman.qiyaa.data.AppLanguage
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono

/**
 * The interface language as three codes, the current one lit. A pick is stored and applied by
 * [onPick]; the activity is then recreated so that every screen takes the new language at once.
 */
@Composable
fun LanguageSwitch(current: AppLanguage, onPick: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (language in AppLanguage.entries) {
            val selected = language == current
            QiText(
                language.code,
                mono(11.sp, if (selected) 700 else 500, 1.sp),
                Modifier
                    .tap(enabled = !selected) {
                        onPick(language)
                        context.findActivity()?.recreate()
                    }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                color = if (selected) Qi.colors.accent else Qi.colors.muted,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
