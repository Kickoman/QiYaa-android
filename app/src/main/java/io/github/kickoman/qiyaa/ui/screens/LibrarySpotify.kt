package io.github.kickoman.qiyaa.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.LibrarySection
import io.github.kickoman.qiyaa.ui.SpotifyAccess
import io.github.kickoman.qiyaa.ui.SpotifyViewModel
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono

@Composable
internal fun SpotifySources(
    spotify: SpotifyViewModel,
    onOpenSection: (LibrarySection) -> Unit,
    onSearch: (String) -> Unit,
) {
    val colors = Qi.colors
    val ui by spotify.ui.collectAsStateWithLifecycle()
    val access = ui.access
    LaunchedEffect(access) { if (access == SpotifyAccess.LOGGED_IN) spotify.loadLists() }

    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QiText(
            stringResource(R.string.library_spotify),
            LabelStyle,
            Modifier.weight(1f),
            color = colors.dim,
            maxLines = 1,
        )
        val action = mono(11.sp, 500, 1.sp)
        if (ui.canLogOut) {
            val logOut = Modifier.tap(onClick = spotify::logout)
            val word = stringResource(R.string.spotify_log_out)
            if (ui.username.isNotEmpty()) {
                QiText(
                    ui.username,
                    action,
                    logOut.weight(1f, fill = false),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QiText(
                if (ui.username.isEmpty()) word else " · $word",
                action,
                logOut,
                color = colors.muted,
                maxLines = 1,
            )
        } else {
            QiText(
                stringResource(R.string.spotify_log_in),
                action,
                Modifier.tap(onClick = spotify::openLogin),
                color = colors.accent,
                maxLines = 1,
            )
        }
    }

    when (access) {
        SpotifyAccess.LOGGED_IN -> SpotifyPicks(spotify, onOpenSection, onSearch)

        SpotifyAccess.CONNECTING -> SpotifyNote(stringResource(R.string.spotify_connecting), colors.muted)

        SpotifyAccess.LOG_IN -> {
            LoginRow(stringResource(R.string.spotify_log_in_row), spotify::openLogin)
            SpotifyNote(stringResource(R.string.spotify_premium_note), colors.muted)
        }

        SpotifyAccess.EXPIRED -> {
            LoginRow(stringResource(R.string.spotify_log_in_again), spotify::openLogin)
            SpotifyNote(stringResource(R.string.spotify_expired), colors.error)
        }

        SpotifyAccess.PREMIUM_REQUIRED -> {
            LoginRow(stringResource(R.string.spotify_log_in_row), spotify::openLogin)
            SpotifyNote(stringResource(R.string.spotify_premium_required), colors.error)
            SpotifyNote(stringResource(R.string.spotify_premium_note), colors.muted)
        }

        SpotifyAccess.REFUSED -> {
            LoginRow(stringResource(R.string.spotify_log_in_again), spotify::openLogin)
            SpotifyNote(stringResource(R.string.error_spotify_login_refused), colors.error)
        }
    }
}

@Composable
private fun SpotifyPicks(
    spotify: SpotifyViewModel,
    onOpenSection: (LibrarySection) -> Unit,
    onSearch: (String) -> Unit,
) {
    val colors = Qi.colors
    val ui by spotify.ui.collectAsStateWithLifecycle()
    val forYou by spotify.forYou.state.collectAsStateWithLifecycle()
    val playlists by spotify.playlists.state.collectAsStateWithLifecycle()
    val albums by spotify.albums.state.collectAsStateWithLifecycle()
    val artists by spotify.artists.state.collectAsStateWithLifecycle()
    LibrarySearchField(stringResource(R.string.spotify_search_hint), onSearch)
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface),
    ) {
        SourceRow(stringResource(R.string.spotify_liked_songs), "", onClick = spotify::playLiked)
        SourceRow(stringResource(R.string.library_for_you), listMeta(forYou)) {
            onOpenSection(LibrarySection.SPOTIFY_FOR_YOU)
        }
        SourceRow(stringResource(R.string.library_playlists), listMeta(playlists)) {
            onOpenSection(LibrarySection.SPOTIFY_PLAYLISTS)
        }
        SourceRow(stringResource(R.string.library_albums), listMeta(albums)) {
            onOpenSection(LibrarySection.SPOTIFY_ALBUMS)
        }
        SourceRow(
            stringResource(R.string.library_artists),
            listMeta(artists) + " · " + stringResource(R.string.library_top_tracks),
            last = true,
        ) {
            onOpenSection(LibrarySection.SPOTIFY_ARTISTS)
        }
    }
}

@Composable
private fun LoginRow(text: String, onClick: () -> Unit) {
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Qi.colors.surface),
    ) {
        SourceRow(text, "", last = true, onClick = onClick)
    }
}

@Composable
private fun SpotifyNote(text: String, color: Color) {
    QiText(text, HintStyle, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), color = color)
}
