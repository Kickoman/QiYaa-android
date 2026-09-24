package io.github.kickoman.qiyaa.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.AppViewModel
import io.github.kickoman.qiyaa.ui.LibrarySub
import io.github.kickoman.qiyaa.ui.components.Chip
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.yandex.Library

private data class ChipItem(val id: String, val label: String, val onClick: () -> Unit)
private data class Group(val name: String, val items: List<ChipItem>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibrarySubScreen(vm: AppViewModel, sub: LibrarySub) {
    val c = Qi.colors
    val lib by vm.lib.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val q by vm.queue.state.collectAsStateWithLifecycle()
    BackHandler { vm.closeSub() }

    val subTitle = stringResource(
        when (sub) {
            LibrarySub.STATIONS -> R.string.library_sub_stations
            LibrarySub.PLAYLISTS -> R.string.library_sub_playlists
            LibrarySub.ARTISTS -> R.string.library_sub_artists
            LibrarySub.ALBUMS -> R.string.library_sub_albums
        },
    )
    val groupNames = mapOf(
        "personal" to stringResource(R.string.library_group_personal),
        "genre" to stringResource(R.string.library_group_genre),
        "mood" to stringResource(R.string.library_group_mood),
        "activity" to stringResource(R.string.library_group_activity),
        "epoch" to stringResource(R.string.library_group_epoch),
        "local" to stringResource(R.string.library_group_local),
        "author" to stringResource(R.string.library_group_author),
    )
    val order = listOf("personal", "genre", "mood", "activity", "epoch", "local", "author")

    val loaded: Boolean
    val groups: List<Group> = when (sub) {
        LibrarySub.STATIONS -> {
            val st = lib.stations
            loaded = st != null
            (st ?: emptyList()).groupBy { Library.stationGroupKey(it.type) }.entries
                .sortedBy { (k, _) -> order.indexOf(k).let { if (it < 0) order.size else it } }
                .map { (k, list) -> Group(groupNames[k] ?: k.uppercase(), list.map { s -> ChipItem(s.id, s.name) { vm.playStation(s) } }) }
        }
        LibrarySub.PLAYLISTS -> {
            val pl = lib.playlists
            loaded = pl != null
            val (mine, saved) = (pl ?: emptyList()).partition { it.ownerUid == account.uid }
            listOf(
                Group(stringResource(R.string.library_group_mine), mine.map { p -> ChipItem("playlist:${p.ownerUid}:${p.kind}", "${p.title} (${p.trackCount})") { vm.playPlaylist(p) } }),
                Group(stringResource(R.string.library_group_saved), saved.map { p -> ChipItem("playlist:${p.ownerUid}:${p.kind}", "${p.title} (${p.trackCount})") { vm.playPlaylist(p) } }),
            ).filter { it.items.isNotEmpty() }
        }
        LibrarySub.ARTISTS -> {
            val a = lib.artists
            loaded = a != null
            listOf(Group(stringResource(R.string.library_group_liked_top), (a ?: emptyList()).map { x -> ChipItem("artist:${x.id}", x.name) { vm.playArtist(x) } }))
        }
        LibrarySub.ALBUMS -> {
            val a = lib.albums
            loaded = a != null
            listOf(Group(stringResource(R.string.library_group_liked), (a ?: emptyList()).map { x -> ChipItem("album:${x.id}", x.name) { vm.playAlbum(x) } }))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).tap { vm.closeSub() }, contentAlignment = Alignment.Center) {
                QiText("‹", mono(18.sp, 400), color = c.muted)
            }
            QiText(stringResource(R.string.library_sub_title, subTitle), CaptionStyle, color = c.muted)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)) {
            when {
                lib.error != null && !loaded -> QiText(stringResource(R.string.library_error, lib.error.orEmpty()), mono(11.sp, 400, 1.sp), Modifier.padding(top = 14.dp), color = c.error)
                !loaded -> QiText(stringResource(R.string.library_loading), mono(11.sp, 400, 1.5.sp), Modifier.padding(top = 14.dp), color = c.dimmer)
                groups.isEmpty() -> QiText(stringResource(R.string.library_empty), mono(11.sp, 400, 1.5.sp), Modifier.padding(top = 14.dp), color = c.dimmer)
            }
            for (g in groups) {
                Column(Modifier.padding(top = 14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        QiText(g.name, LabelStyle, color = c.dim)
                        QiText("${g.items.size}", LabelStyle, color = c.dim)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in g.items) Chip(item.label, active = item.id == q.activeSourceId, onClick = item.onClick)
                    }
                }
            }
        }
    }
}
