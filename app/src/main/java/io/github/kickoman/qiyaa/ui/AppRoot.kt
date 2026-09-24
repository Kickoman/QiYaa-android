package io.github.kickoman.qiyaa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import io.github.kickoman.qiyaa.ui.screens.LibrarySubScreen
import io.github.kickoman.qiyaa.ui.screens.LoginScreen
import io.github.kickoman.qiyaa.ui.screens.PlayerScreen
import io.github.kickoman.qiyaa.ui.screens.PlaylistScreen
import io.github.kickoman.qiyaa.ui.screens.PresetsSheet
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.QiYaaTheme
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.fmtTime

@Composable
fun AppRoot(app: AppViewModel = viewModel(), player: PlayerViewModel = viewModel()) {
    val theme by app.settings.theme.collectAsStateWithLifecycle()
    QiYaaTheme(theme) {
        val c = Qi.colors
        val screen by app.screen.collectAsStateWithLifecycle()
        val sub by app.sub.collectAsStateWithLifecycle()
        val toast by app.toast.collectAsStateWithLifecycle()
        val presetsOpen by player.presetsOpen.collectAsStateWithLifecycle()
        val pui by player.ui.collectAsStateWithLifecycle()

        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .imePadding(),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (screen) {
                        Screen.LOGIN -> LoginScreen(app)
                        Screen.PLAYER -> PlayerScreen(player)
                        Screen.PLAYLIST -> PlaylistScreen(player, onAdd = { app.go(Screen.LIBRARY) })
                        Screen.EQ -> EqScreen(player)
                        Screen.LIBRARY -> sub?.let { LibrarySubScreen(app, it) } ?: LibraryScreen(app, player)
                    }
                }
                val track = pui.current
                if (screen != Screen.LOGIN && screen != Screen.PLAYER && track != null) {
                    MiniPlayer(
                        text = "${pui.index + 1}. ${track.artistLine} — ${track.title} (${fmtTime(track.durationMs)})",
                        playing = pui.isPlaying,
                        onOpen = { app.go(Screen.PLAYER) },
                        onToggle = player::toggleMiniPlay,
                    )
                }
                if (screen != Screen.LOGIN) TabBar(screen, app::go)
            }

            if (presetsOpen) PresetsSheet(player)

            toast?.let { msg ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(start = 16.dp, end = 16.dp, bottom = 84.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.deep)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    QiText(msg.uppercase(), mono(12.sp, 500, 0.5.sp), color = c.acc, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(text: String, playing: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    val c = Qi.colors
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(c.surface)
            .tap(onClick = onOpen)
            .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LedDot(c.acc, Modifier.alpha(if (playing) 1f else 0.3f))
        QiText(text.uppercase(), mono(12.sp, 500, 0.5.sp), Modifier.weight(1f), color = c.acc, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(c.border).tap(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            PathIcon(if (playing) IconPaths.PAUSE else IconPaths.PLAY, 16.dp, c.text)
        }
    }
}

@Composable
private fun TabBar(current: Screen, onSelect: (Screen) -> Unit) {
    val c = Qi.colors
    Column(Modifier.fillMaxWidth().background(c.tabBg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.surface2))
        Row(Modifier.fillMaxWidth().height(60.dp)) {
            Tab(stringResource(R.string.tab_player), current == Screen.PLAYER, { onSelect(Screen.PLAYER) }) { color ->
                Box(Modifier.size(18.dp).border(2.dp, color, CircleShape))
            }
            Tab(stringResource(R.string.tab_playlist), current == Screen.PLAYLIST, { onSelect(Screen.PLAYLIST) }) { color ->
                Column(Modifier.size(18.dp).padding(vertical = 2.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    repeat(3) { Box(Modifier.fillMaxWidth().height(2.dp).background(color)) }
                }
            }
            Tab(stringResource(R.string.tab_eq), current == Screen.EQ, { onSelect(Screen.EQ) }) { color ->
                Row(Modifier.size(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.weight(1f).height(11.dp).background(color))
                    Box(Modifier.weight(1f).height(18.dp).background(color))
                    Box(Modifier.weight(1f).height(7.dp).background(color))
                }
            }
            Tab(stringResource(R.string.tab_library), current == Screen.LIBRARY, { onSelect(Screen.LIBRARY) }) { color ->
                Column(Modifier.size(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(2) {
                        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
private fun androidx.compose.foundation.layout.RowScope.Tab(label: String, active: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val c = Qi.colors
    val color = if (active) c.acc else c.dim
    Column(
        Modifier.weight(1f).height(60.dp).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(color)
        QiText(label, mono(10.sp, 500, 1.5.sp), Modifier.padding(top = 6.dp), color = color)
    }
}
