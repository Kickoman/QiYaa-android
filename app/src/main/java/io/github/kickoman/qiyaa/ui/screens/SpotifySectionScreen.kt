package io.github.kickoman.qiyaa.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.spotify.SpotifyItem
import io.github.kickoman.qiyaa.ui.ListState
import io.github.kickoman.qiyaa.ui.SpotifyList
import io.github.kickoman.qiyaa.ui.SpotifyViewModel
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.render
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
fun SpotifySectionScreen(viewModel: SpotifyViewModel, list: SpotifyList, onClose: () -> Unit) {
    val colors = Qi.colors
    val state by viewModel.list(list).state.collectAsStateWithLifecycle()
    val queue by viewModel.queueState.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    LaunchedEffect(list) { viewModel.load(list) }
    val title =
        stringResource(
            when (list) {
                SpotifyList.FOR_YOU -> R.string.library_sub_for_you
                SpotifyList.PLAYLISTS -> R.string.library_sub_playlists
                SpotifyList.ALBUMS -> R.string.library_sub_albums
                SpotifyList.ARTISTS -> R.string.library_sub_artists
            },
        )
    val spotify = stringResource(R.string.library_spotify)
    val heading = stringResource(R.string.library_sub_title, "$spotify · $title")

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).tap(onClick = onClose), contentAlignment = Alignment.Center) {
                QiText("‹", mono(18.sp, 400), color = colors.muted)
            }
            QiText(
                heading,
                CaptionStyle,
                Modifier.weight(1f),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            (state as? ListState.Loaded)?.let { QiText("${it.items.size}", LabelStyle, color = colors.dim) }
        }
        when (val current = state) {
            ListState.Idle, ListState.Loading ->
                QiText(
                    stringResource(R.string.library_loading),
                    HintStyle,
                    Modifier.padding(16.dp),
                    color = colors.dim,
                )

            is ListState.Failed ->
                Column(Modifier.padding(16.dp).tap { viewModel.load(list) }) {
                    QiText(
                        stringResource(R.string.library_error, current.error.render().uppercase()),
                        HintStyle,
                        color = colors.error,
                    )
                    QiText(
                        stringResource(R.string.library_retry),
                        HintStyle,
                        Modifier.padding(top = 6.dp),
                        color = colors.accent,
                    )
                }

            is ListState.Loaded ->
                if (current.items.isEmpty()) {
                    QiText(
                        stringResource(R.string.library_empty),
                        HintStyle,
                        Modifier.padding(16.dp),
                        color = colors.dim,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        val key = { index: Int, item: SpotifyItem -> "$index:${item.uri}" }
                        itemsIndexed(current.items, key = key) { _, item ->
                            SpotifyRow(item, active = item.uri == queue.activeSourceId) {
                                viewModel.play(list, item)
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun SpotifyRow(item: SpotifyItem, active: Boolean, onClick: () -> Unit) {
    val colors = Qi.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .tap(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QiText(
            item.name,
            sans(15.sp, 500),
            Modifier.weight(1f),
            color = if (active) colors.accent else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surface))
}
