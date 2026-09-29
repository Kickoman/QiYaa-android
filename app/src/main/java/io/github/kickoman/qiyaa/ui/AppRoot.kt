package io.github.kickoman.qiyaa.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.LedDot
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.screens.EqScreen
import io.github.kickoman.qiyaa.ui.screens.LibraryScreen
import io.github.kickoman.qiyaa.ui.screens.LibrarySectionScreen
import io.github.kickoman.qiyaa.ui.screens.LoginScreen
import io.github.kickoman.qiyaa.ui.screens.PlayerScreen
import io.github.kickoman.qiyaa.ui.screens.PlaylistScreen
import io.github.kickoman.qiyaa.ui.screens.PresetsSheet
import io.github.kickoman.qiyaa.ui.screens.marqueeText
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.QiYaaTheme
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.yandex.SessionState

private val MiniPlayerStyle = mono(12.sp, 500, 0.5.sp)
private val TabStyle = mono(10.sp, 500, 1.5.sp)

@Composable
fun AppRoot(app: AppViewModel = viewModel(), player: PlayerViewModel = viewModel()) {
    val theme by app.settings.theme.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { player.notices.collect(app::say) }
    QiYaaTheme(theme) {
        val colors = Qi.colors
        val screen by app.screen.collectAsStateWithLifecycle()
        val section by app.section.collectAsStateWithLifecycle()
        val toast by app.toast.collectAsStateWithLifecycle()
        val presetsOpen by player.presetsOpen.collectAsStateWithLifecycle()
        val playerUi by player.ui.collectAsStateWithLifecycle()
        val sessionState by app.sessionState.collectAsStateWithLifecycle()
        val backTarget = screen.backTarget
        BackHandler(enabled = backTarget != null) { backTarget?.let(app::go) }

        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .imePadding(),
            ) {
                if (screen != Screen.LOGIN && sessionState == SessionState.Offline) OfflineStrip()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.LOGIN -> LoginScreen(app)
                        Screen.PLAYER -> PlayerScreen(player)
                        Screen.PLAYLIST -> PlaylistScreen(player, onAdd = { app.go(Screen.LIBRARY) })
                        Screen.EQ -> EqScreen(player)
                        Screen.LIBRARY -> section?.let { LibrarySectionScreen(app, it) }
                            ?: LibraryScreen(app, player)
                    }
                }
                val track = playerUi.current
                if (screen != Screen.LOGIN && screen != Screen.PLAYER && track != null) {
                    MiniPlayer(
                        text = marqueeText(playerUi.index, track),
                        playing = playerUi.isPlaying,
                        onOpen = { app.go(Screen.PLAYER) },
                        onToggle = player::toggleMiniPlay,
                    )
                }
                if (screen != Screen.LOGIN) TabBar(screen, app::go)
            }

            if (presetsOpen) {
                BackHandler { player.closePresets() }
                PresetsSheet(player)
            }

            toast?.let { message ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(start = 16.dp, end = 16.dp, bottom = 84.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.deep)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QiText(
                        message.uppercase(),
                        MiniPlayerStyle,
                        color = colors.accent,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineStrip() {
    val colors = Qi.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.deep)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LedDot(colors.error)
        QiText(stringResource(R.string.session_offline), ReadoutStyle, color = colors.error, maxLines = 1)
    }
}

@Composable
private fun MiniPlayer(text: String, playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val colors = Qi.colors
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .tap(onClick = onOpen)
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LedDot(colors.accent, Modifier.alpha(if (playing) 1f else 0.3f))
        QiText(
            text.uppercase(),
            MiniPlayerStyle,
            Modifier.weight(1f),
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.size(
                40.dp,
            ).clip(RoundedCornerShape(6.dp)).background(colors.border).tap(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            PathIcon(if (playing) IconPaths.PAUSE else IconPaths.PLAY, 16.dp, colors.text)
        }
    }
}

@Composable
private fun TabBar(current: Screen, onSelect: (Screen) -> Unit) {
    val colors = Qi.colors
    Column(Modifier.fillMaxWidth().background(colors.tabBackground)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surface2))
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            Tab(stringResource(R.string.tab_player), current == Screen.PLAYER, {
                onSelect(Screen.PLAYER)
            }) { color ->
                Box(Modifier.size(18.dp).border(2.dp, color, CircleShape))
            }
            Tab(stringResource(R.string.tab_playlist), current == Screen.PLAYLIST, {
                onSelect(Screen.PLAYLIST)
            }) { color ->
                Column(
                    Modifier.size(18.dp).padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    repeat(3) { Box(Modifier.fillMaxWidth().height(2.dp).background(color)) }
                }
            }
            Tab(stringResource(R.string.tab_eq), current == Screen.EQ, { onSelect(Screen.EQ) }) { color ->
                Row(
                    Modifier.size(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Box(Modifier.weight(1f).height(11.dp).background(color))
                    Box(Modifier.weight(1f).height(18.dp).background(color))
                    Box(Modifier.weight(1f).height(7.dp).background(color))
                }
            }
            Tab(stringResource(R.string.tab_library), current == Screen.LIBRARY, {
                onSelect(Screen.LIBRARY)
            }) { color ->
                Column(Modifier.size(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(2) {
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Box(Modifier.weight(1f).fillMaxSize().background(color))
                            Box(Modifier.weight(1f).fillMaxSize().background(color))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.Tab(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    val colors = Qi.colors
    val color = if (active) colors.accent else colors.dim
    Column(
        Modifier.weight(1f).height(60.dp).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(color)
        QiText(label, TabStyle, Modifier.padding(top = 6.dp), color = color)
    }
}
