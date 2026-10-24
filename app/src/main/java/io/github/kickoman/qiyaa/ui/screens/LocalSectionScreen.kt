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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.local.LocalFolder
import io.github.kickoman.qiyaa.local.LocalPick
import io.github.kickoman.qiyaa.ui.AppViewModel
import io.github.kickoman.qiyaa.ui.LibrarySection
import io.github.kickoman.qiyaa.ui.ListState
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.render
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

private data class LocalItem(val key: String, val name: String, val count: Int, val pick: LocalPick)

@Composable
fun LocalSectionScreen(viewModel: AppViewModel, section: LibrarySection) {
    val colors = Qi.colors
    val rows by viewModel.localRows.state.collectAsStateWithLifecycle()
    val picks by viewModel.localPicks.collectAsStateWithLifecycle()
    BackHandler { viewModel.closeSection() }
    val unknownArtist = stringResource(R.string.local_unknown_artist)
    val unknownAlbum = stringResource(R.string.local_unknown_album)
    val root = stringResource(R.string.local_root)
    val title =
        stringResource(
            when (section) {
                LibrarySection.LOCAL_ARTISTS -> R.string.local_artists
                LibrarySection.LOCAL_FOLDERS -> R.string.local_folders
                else -> R.string.local_albums
            },
        )
    val items: List<LocalItem> =
        picks?.let { loaded ->
            when (section) {
                LibrarySection.LOCAL_ARTISTS -> loaded.artists.map {
                    val name = it.name.ifEmpty { unknownArtist }
                    LocalItem("artist:${it.name}", name, it.trackCount, LocalPick.Artist(it.name))
                }

                LibrarySection.LOCAL_FOLDERS -> loaded.folders.map {
                    val key = "folder:${it.folder.volume}/${it.folder.path.joinToString("/")}"
                    LocalItem(key, folderName(it.folder, root), it.trackCount, LocalPick.Folder(it.folder))
                }

                else -> loaded.albums.map {
                    val name = it.name.ifEmpty { unknownAlbum }
                    LocalItem("album:${it.id}", name, it.trackCount, LocalPick.Album(it.id))
                }
            }
        }.orEmpty()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).tap { viewModel.closeSection() }, contentAlignment = Alignment.Center) {
                QiText("‹", mono(18.sp, 400), color = colors.muted)
            }
            QiText(stringResource(R.string.library_sub_title, title), CaptionStyle, color = colors.muted)
        }
        when (val state = rows) {
            ListState.Idle, ListState.Loading ->
                QiText(
                    stringResource(R.string.library_loading),
                    HintStyle,
                    Modifier.padding(16.dp),
                    color = colors.dim,
                )

            is ListState.Failed ->
                QiText(state.error.render(), HintStyle, Modifier.padding(16.dp), color = colors.error)

            is ListState.Loaded ->
                if (items.isEmpty()) {
                    QiText(
                        stringResource(R.string.local_empty),
                        HintStyle,
                        Modifier.padding(16.dp),
                        color = colors.dim,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        items(items, key = LocalItem::key) { item ->
                            LocalRowItem(item) { viewModel.playLocal(item.pick, item.name) }
                        }
                    }
                }
        }
    }
}

@Composable
private fun LocalRowItem(item: LocalItem, onClick: () -> Unit) {
    val colors = Qi.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .tap(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QiText(
            item.name,
            sans(15.sp, 500),
            Modifier.weight(1f),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        QiText("${item.count}", mono(12.sp, 400), color = colors.muted)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.surface))
}

private fun folderName(folder: LocalFolder, root: String): String {
    val path = folder.path.joinToString("/").ifEmpty { root }
    val primary = folder.volume.isEmpty() || folder.volume == PRIMARY_VOLUME
    return if (primary) path else "${folder.volume} · $path"
}

private const val PRIMARY_VOLUME = "external_primary"
