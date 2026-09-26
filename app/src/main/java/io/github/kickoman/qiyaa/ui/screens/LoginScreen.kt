package io.github.kickoman.qiyaa.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.AppViewModel
import io.github.kickoman.qiyaa.ui.LoginStatus
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.LedDot
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import io.github.kickoman.qiyaa.yandex.DeviceAuth

private const val BLINK_MS = 600

@Composable
fun LoginScreen(viewModel: AppViewModel) {
    val colors = Qi.colors
    val context = LocalContext.current
    val login by viewModel.login.collectAsStateWithLifecycle()
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        QiText(stringResource(R.string.brand), mono(12.sp, 600, 3.sp), color = colors.muted)
        Spacer(Modifier.height(10.dp))
        QiText(stringResource(R.string.login_title), sans(26.sp, 600, colors.text, lineHeight = 30.sp))
        Spacer(Modifier.height(28.dp))
        QiText(stringResource(R.string.login_step1), LabelStyle, color = colors.dim)
        Spacer(Modifier.height(8.dp))
        QiText(
            stringResource(R.string.login_step1_text),
            sans(14.sp, 400, colors.textSecondary, lineHeight = 21.sp),
        )
        Spacer(Modifier.height(16.dp))

        val failed = login.status is LoginStatus.Failed
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.deep)
                .border(1.dp, colors.insetBorder, RoundedCornerShape(8.dp))
                .tap(enabled = failed) { viewModel.startDeviceLogin() }
                .padding(vertical = 22.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            QiText(
                login.userCode.ifEmpty { "· · · ·" },
                mono(
                    44.sp,
                    500,
                    6.sp,
                ).copy(color = colors.accent, shadow = Shadow(colors.accentBackground, blurRadius = 16f)),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(10.dp))
        ActionButton(
            stringResource(R.string.login_open_device),
            Modifier.fillMaxWidth(),
            background = colors.accent,
            color = colors.deep,
            height = 52.dp,
        ) { open(login.verificationUrl) }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val blink = rememberInfiniteTransition(label = "blink")
            val alpha by blink.animateFloat(
                1f,
                0.2f,
                infiniteRepeatable(tween(BLINK_MS, easing = LinearEasing), RepeatMode.Reverse),
                label = "alpha",
            )
            LedDot(colors.accent, Modifier.alpha(if (failed) 1f else alpha))
            val status =
                when (val current = login.status) {
                    LoginStatus.Requesting -> stringResource(R.string.login_status_requesting)
                    LoginStatus.Waiting -> stringResource(R.string.login_status_waiting)
                    LoginStatus.SigningIn -> stringResource(R.string.login_status_signing_in)
                    is LoginStatus.Failed -> stringResource(
                        R.string.login_status_failed,
                        current.message.uppercase(),
                    )
                }
            QiText(status, HintStyle, color = colors.dim)
        }

        Spacer(Modifier.height(28.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
            QiText(stringResource(R.string.login_step2), LabelStyle, color = colors.dim)
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
        }
        Spacer(Modifier.height(12.dp))
        QiText(
            stringResource(R.string.login_step2_text),
            sans(13.sp, 400, colors.textSecondary, lineHeight = 19.5.sp),
        )
        Spacer(Modifier.height(12.dp))
        ActionButton(
            stringResource(R.string.login_open_page),
            Modifier.fillMaxWidth(),
            background = colors.surface2,
            color = colors.text,
            height = 48.dp,
        ) { open(DeviceAuth.BROWSER_LOGIN_URL) }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.deep)
                .border(1.dp, colors.insetBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            val style = mono(12.sp, 400).copy(color = colors.text)
            BasicTextField(
                value = login.tokenInput,
                onValueChange = viewModel::setTokenInput,
                textStyle = style,
                singleLine = true,
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.useToken() }),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (login.tokenInput.isEmpty()) {
                        QiText(
                            stringResource(R.string.login_token_hint),
                            style,
                            color = colors.dim,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        ActionButton(
            stringResource(R.string.login_use_token),
            Modifier.fillMaxWidth(),
            background = colors.background,
            color = if (login.tokenInput.isNotEmpty()) colors.text else colors.muted,
            height = 48.dp,
            border = colors.border,
        ) { viewModel.useToken() }
        if (login.tokenError) {
            Spacer(Modifier.height(8.dp))
            QiText(stringResource(R.string.login_token_error), mono(11.sp, 400), color = colors.error)
        }
        Spacer(Modifier.weight(1f).height(24.dp))
        QiText(
            stringResource(R.string.login_footer),
            mono(11.sp, 400, lineHeight = 16.5.sp),
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            color = colors.dimmer,
            textAlign = TextAlign.Center,
        )
    }
}
