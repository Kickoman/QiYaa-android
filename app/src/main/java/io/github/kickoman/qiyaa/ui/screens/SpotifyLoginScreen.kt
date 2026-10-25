package io.github.kickoman.qiyaa.ui.screens

import android.content.ClipData
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.SpotifyBrowserStatus
import io.github.kickoman.qiyaa.ui.SpotifyCodeStatus
import io.github.kickoman.qiyaa.ui.SpotifyViewModel
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.LedDot
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.QrCodeImage
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.render
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import kotlinx.coroutines.launch

private const val STATUS_BLINK_MS = 600

@Composable
fun SpotifyLoginScreen(viewModel: SpotifyViewModel) {
    val colors = Qi.colors
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val login by viewModel.login.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.browserPages.collect { url -> if (!openInBrowser(context, url)) viewModel.noBrowser() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            QiText(stringResource(R.string.brand), mono(12.sp, 600, 3.sp), color = colors.muted)
            QiText(
                stringResource(R.string.login_later),
                mono(11.sp, 500, 1.sp),
                Modifier.tap(onClick = viewModel::leaveLogin),
                color = colors.muted,
                maxLines = 1,
            )
        }
        QiText(
            stringResource(R.string.spotify_login_title),
            sans(26.sp, 600, colors.text, lineHeight = 30.sp),
        )
        Spacer(Modifier.height(12.dp))
        QiText(stringResource(R.string.spotify_premium_note), HintStyle, color = colors.muted)

        Spacer(Modifier.height(28.dp))
        QiText(stringResource(R.string.spotify_login_browser_step), LabelStyle, color = colors.dim)
        Spacer(Modifier.height(8.dp))
        QiText(
            stringResource(R.string.spotify_login_browser_text),
            sans(14.sp, 400, colors.textSecondary, lineHeight = 21.sp),
        )
        Spacer(Modifier.height(16.dp))
        ActionButton(
            stringResource(R.string.spotify_login_browser),
            Modifier.fillMaxWidth(),
            background = colors.accent,
            color = colors.deep,
            height = 52.dp,
            onClick = viewModel::startBrowserLogin,
        )
        when (val browser = login.browser) {
            SpotifyBrowserStatus.Idle -> {}

            SpotifyBrowserStatus.Waiting ->
                StatusLine(stringResource(R.string.spotify_login_browser_waiting), blinking = true)

            is SpotifyBrowserStatus.Failed ->
                StatusLine(
                    stringResource(R.string.spotify_login_browser_failed, browser.error.render().uppercase()),
                    blinking = false,
                )
        }

        Spacer(Modifier.height(28.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
            QiText(stringResource(R.string.spotify_login_code_step), LabelStyle, color = colors.dim)
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
        }
        Spacer(Modifier.height(12.dp))
        QiText(
            stringResource(R.string.spotify_login_code_text),
            sans(14.sp, 400, colors.textSecondary, lineHeight = 21.sp),
        )
        Spacer(Modifier.height(16.dp))
        val failed = login.code is SpotifyCodeStatus.Failed
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.deep)
                .border(1.dp, colors.insetBorder, RoundedCornerShape(8.dp))
                .tap(enabled = failed, onClick = viewModel::startCodeLogin)
                .padding(vertical = 22.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            QiText(
                login.userCode.ifEmpty { "· · · ·" },
                mono(
                    40.sp,
                    500,
                    6.sp,
                ).copy(color = colors.accent, shadow = Shadow(colors.accentBackground, blurRadius = 16f)),
                textAlign = TextAlign.Center,
            )
        }
        val code = login.code
        StatusLine(
            when (code) {
                SpotifyCodeStatus.Requesting -> stringResource(R.string.spotify_login_code_requesting)

                SpotifyCodeStatus.Waiting -> stringResource(R.string.spotify_login_code_waiting)

                is SpotifyCodeStatus.Failed ->
                    stringResource(R.string.spotify_login_code_failed, code.error.render().uppercase())
            },
            blinking = !failed,
        )
        val pairUrl = login.pairUrl
        if (pairUrl.isNotEmpty()) {
            val copied = stringResource(R.string.jam_copied)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    stringResource(R.string.spotify_login_open_pair),
                    Modifier.weight(3f),
                    background = colors.surface2,
                    color = colors.text,
                    height = 48.dp,
                ) {
                    if (!openInBrowser(context, pairUrl)) viewModel.noBrowser()
                }
                ActionButton(
                    stringResource(R.string.spotify_login_copy),
                    Modifier.weight(2f),
                    background = colors.background,
                    color = colors.text,
                    height = 48.dp,
                    border = colors.border,
                ) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, pairUrl))) }
                    viewModel.say(copied)
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeImage(pairUrl, Modifier.fillMaxWidth(0.6f))
            }
            Spacer(Modifier.height(8.dp))
            QiText(
                stringResource(R.string.spotify_login_qr_note),
                HintStyle,
                Modifier.fillMaxWidth(),
                color = colors.dim,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusLine(text: String, blinking: Boolean) {
    val colors = Qi.colors
    Row(
        Modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val blink = rememberInfiniteTransition(label = "blink")
        val alpha by blink.animateFloat(
            1f,
            0.2f,
            infiniteRepeatable(tween(STATUS_BLINK_MS, easing = LinearEasing), RepeatMode.Reverse),
            label = "alpha",
        )
        LedDot(colors.accent, Modifier.alpha(if (blinking) alpha else 1f))
        QiText(text, HintStyle, color = colors.dim)
    }
}
