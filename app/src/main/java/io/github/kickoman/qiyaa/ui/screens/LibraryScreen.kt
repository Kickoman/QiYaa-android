package io.github.kickoman.qiyaa.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.AppViewModel
import io.github.kickoman.qiyaa.ui.LibrarySub
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
fun LibraryScreen(vm: AppViewModel, player: PlayerViewModel) {
    val c = Qi.colors
    val ctx = LocalContext.current
    val account by vm.account.collectAsStateWithLifecycle()
    val liked by vm.likedIds.collectAsStateWithLifecycle()
    val lib by vm.lib.collectAsStateWithLifecycle()
    val pui by player.ui.collectAsStateWithLifecycle()
    val track = pui.current

    LaunchedEffect(account.uid) { if (account.isValid) vm.loadLibraryLists() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.library_title)) {
            val who = account.login.ifEmpty { account.displayName }
            QiText(
                (if (who.isEmpty()) "" else "$who · ") + stringResource(R.string.library_sign_out),
                mono(11.sp, 500, 1.sp),
                Modifier.tap { vm.signOut() },
                color = c.muted,
                maxLines = 1,
            )
        }

        // Search.
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(c.deep)
                .border(1.dp, c.insetBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(14.dp).border(2.dp, c.dim, CircleShape))
            val style = mono(13.sp, 400).copy(color = c.text)
            BasicTextField(
                value = lib.search,
                onValueChange = vm::setSearch,
                textStyle = style,
                singleLine = true,
                cursorBrush = SolidColor(c.acc),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.submitSearch() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (lib.search.isEmpty()) QiText(stringResource(R.string.library_search_hint), style, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                },
            )
        }

        // My Wave.
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.accBg)
                .tap { vm.playMyWave() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.acc), contentAlignment = Alignment.Center) {
                PathIcon(IconPaths.PLAY, 20.dp, c.deep)
            }
            Column(Modifier.weight(1f)) {
                QiText(stringResource(R.string.library_my_wave), sans(16.sp, 600), color = c.acc)
                QiText(stringResource(R.string.library_my_wave_sub), mono(11.sp, 400, 1.sp), Modifier.padding(top = 3.dp), color = c.acc.copy(alpha = 0.85f))
            }
            QiText("∞", mono(20.sp, 400), color = c.acc)
        }

        // Sources.
        val pending = stringResource(R.string.library_loading).takeIf { lib.loading } ?: "…"
        Column(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface),
        ) {
            SourceRow(stringResource(R.string.library_liked), "${liked.size}") { vm.playLiked() }
            SourceRow(stringResource(R.string.library_playlists), lib.playlists?.size?.toString() ?: pending) { vm.openSub(LibrarySub.PLAYLISTS) }
            SourceRow(stringResource(R.string.library_artists), (lib.artists?.size?.toString() ?: pending) + " · " + stringResource(R.string.library_top_tracks)) { vm.openSub(LibrarySub.ARTISTS) }
            SourceRow(stringResource(R.string.library_albums), lib.albums?.size?.toString() ?: pending) { vm.openSub(LibrarySub.ALBUMS) }
            SourceRow(stringResource(R.string.library_stations), stringResource(R.string.library_by_type), last = true) { vm.openSub(LibrarySub.STATIONS) }
        }
        if (lib.error != null) {
            QiText(stringResource(R.string.library_error, lib.error.orEmpty()), mono(10.sp, 400, 1.sp), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), color = c.error)
        }

        // Current track.
        val curTitle = track?.title?.uppercase() ?: stringResource(R.string.library_none)
        QiText(
            stringResource(R.string.library_current_track) + " · " + curTitle,
            LabelStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp),
            color = c.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val isLiked = track != null && track.id in liked
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("♥ " + stringResource(if (isLiked) R.string.player_liked else R.string.player_like), Modifier.weight(1f), height = 48.dp, color = if (isLiked) c.acc else c.muted) {
                player.toggleLikeCurrent()
            }
            ActionButton("✕ " + stringResource(R.string.library_dislike), Modifier.weight(1f), height = 48.dp) { player.dislikeCurrent() }
            ActionButton("↗ " + stringResource(R.string.library_open), Modifier.weight(1f), height = 48.dp) {
                track?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.webUrl))) }
            }
        }
    }
}

@Composable
private fun SourceRow(name: String, meta: String, last: Boolean = false, onClick: () -> Unit) {
    val c = Qi.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .tap(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(c.border))
            QiText(name, sans(15.sp, 500), Modifier.weight(1f), color = c.text)
            QiText(meta, mono(11.sp, 400, 1.sp), color = c.dim)
            QiText("›", mono(14.sp, 400), color = c.dim)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(c.bg))
    }
}
