package io.github.kickoman.qiyaa.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.AppViewModel
import io.github.kickoman.qiyaa.ui.LibrarySection
import io.github.kickoman.qiyaa.ui.components.Chip
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.sourceId
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import io.github.kickoman.qiyaa.yandex.Library
import io.github.kickoman.qiyaa.yandex.PlaylistRef

private data class ChipItem(
    val id: String,
    val label: String,
    val description: String? = null,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

private data class ChipGroup(val name: String, val items: List<ChipItem>)

private val STATION_GROUP_ORDER = listOf("personal", "genre", "mood", "activity", "epoch", "local", "author")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibrarySectionScreen(viewModel: AppViewModel, section: LibrarySection) {
    val colors = Qi.colors
    val libraryUi by viewModel.libraryUi.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val queue by viewModel.queue.state.collectAsStateWithLifecycle()
    BackHandler { viewModel.closeSection() }

    val sectionTitle =
        stringResource(
            when (section) {
                LibrarySection.FOR_YOU -> R.string.library_sub_for_you
                LibrarySection.WHEEL -> R.string.library_sub_wheel
                LibrarySection.STATIONS -> R.string.library_sub_stations
                LibrarySection.PLAYLISTS -> R.string.library_sub_playlists
                LibrarySection.ARTISTS -> R.string.library_sub_artists
                LibrarySection.ALBUMS -> R.string.library_sub_albums
            },
        )
    val stationGroupNames =
        mapOf(
            "personal" to stringResource(R.string.library_group_personal),
            "genre" to stringResource(R.string.library_group_genre),
            "mood" to stringResource(R.string.library_group_mood),
            "activity" to stringResource(R.string.library_group_activity),
            "epoch" to stringResource(R.string.library_group_epoch),
            "local" to stringResource(R.string.library_group_local),
            "author" to stringResource(R.string.library_group_author),
        )

    val loaded: Boolean
    val groups: List<ChipGroup> =
        when (section) {
            LibrarySection.STATIONS -> {
                val stations = libraryUi.stations
                loaded = stations != null
                stations.orEmpty()
                    .groupBy { Library.stationGroupKey(it.type) }
                    .entries
                    .sortedBy { (key, _) ->
                        STATION_GROUP_ORDER.indexOf(key).let {
                            if (it <
                                0
                            ) {
                                STATION_GROUP_ORDER.size
                            } else {
                                it
                            }
                        }
                    }
                    .map { (key, list) ->
                        ChipGroup(
                            stationGroupNames[key] ?: key.uppercase(),
                            list.map { station ->
                                ChipItem(station.id, station.name) { viewModel.playStation(station) }
                            },
                        )
                    }
            }
            LibrarySection.WHEEL -> {
                val waves = libraryUi.wheel
                loaded = waves != null
                val items = waves.orEmpty().map { wave ->
                    ChipItem(wave.seeds.first(), wave.name, description = wave.description) {
                        viewModel.playWheelWave(wave)
                    }
                }
                val name =
                    if (libraryUi.wheelMatchesCurrent) {
                        R.string.library_group_wheel_current
                    } else {
                        R.string.library_group_wheel_my
                    }
                listOf(ChipGroup(stringResource(name), items)).filter { it.items.isNotEmpty() }
            }
            LibrarySection.FOR_YOU -> {
                val forYou = libraryUi.forYou
                loaded = forYou != null
                val items = forYou.orEmpty().map { playlistChip(it, viewModel) }
                listOf(ChipGroup(stringResource(R.string.library_group_for_you), items))
            }
            LibrarySection.PLAYLISTS -> {
                val playlists = libraryUi.playlists
                loaded = playlists != null
                val (mine, saved) = playlists.orEmpty().partition { it.ownerUid == account.uid }
                listOf(
                    ChipGroup(
                        stringResource(R.string.library_group_mine),
                        mine.map {
                            playlistChip(it, viewModel)
                        },
                    ),
                    ChipGroup(
                        stringResource(R.string.library_group_saved),
                        saved.map {
                            playlistChip(it, viewModel)
                        },
                    ),
                ).filter { it.items.isNotEmpty() }
            }
            LibrarySection.ARTISTS -> {
                val artists = libraryUi.artists
                loaded = artists != null
                val items = artists.orEmpty().map { artist ->
                    ChipItem("artist:${artist.id}", artist.name) { viewModel.playArtist(artist) }
                }
                listOf(ChipGroup(stringResource(R.string.library_group_liked_top), items))
            }
            LibrarySection.ALBUMS -> {
                val albums = libraryUi.albums
                loaded = albums != null
                val items = albums.orEmpty().map { album ->
                    ChipItem("album:${album.id}", album.name) { viewModel.playAlbum(album) }
                }
                listOf(ChipGroup(stringResource(R.string.library_group_liked), items))
            }
        }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).tap { viewModel.closeSection() }, contentAlignment = Alignment.Center) {
                QiText("‹", mono(18.sp, 400), color = colors.muted)
            }
            QiText(
                stringResource(R.string.library_sub_title, sectionTitle),
                CaptionStyle,
                color = colors.muted,
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            val error = libraryUi.error
            when {
                error != null && !loaded ->
                    QiText(
                        stringResource(R.string.library_error, error),
                        HintStyle,
                        Modifier.padding(top = 14.dp),
                        color = colors.error,
                    )
                !loaded ->
                    QiText(
                        stringResource(R.string.library_loading),
                        ReadoutStyle,
                        Modifier.padding(top = 14.dp),
                        color = colors.dimmer,
                    )
                groups.isEmpty() ->
                    QiText(
                        stringResource(R.string.library_empty),
                        ReadoutStyle,
                        Modifier.padding(top = 14.dp),
                        color = colors.dimmer,
                    )
            }
            val showsPlaylists = section == LibrarySection.PLAYLISTS || section == LibrarySection.FOR_YOU
            if (showsPlaylists && loaded && groups.isNotEmpty()) {
                QiText(
                    stringResource(R.string.library_similar_hint),
                    HintStyle,
                    Modifier.padding(top = 14.dp),
                    color = colors.dimmer,
                )
            }
            for (group in groups) {
                Column(Modifier.padding(top = 14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        QiText(group.name, LabelStyle, color = colors.dim)
                        QiText("${group.items.size}", LabelStyle, color = colors.dim)
                    }
                    if (group.items.any { it.description != null }) {
                        for (item in group.items) {
                            DescribedRow(item, active = item.id == queue.activeSourceId)
                        }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (item in group.items) {
                                Chip(
                                    item.label,
                                    active = item.id == queue.activeSourceId,
                                    onLongClick = item.onLongClick,
                                    onClick = item.onClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DescribedRow(item: ChipItem, active: Boolean) {
    val colors = Qi.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.accentBackground else colors.surface)
            .tap(onClick = item.onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        QiText(item.label, sans(15.sp, 500), color = if (active) colors.accent else colors.text)
        if (!item.description.isNullOrEmpty()) {
            QiText(item.description, HintStyle, Modifier.padding(top = 2.dp), color = colors.dim)
        }
    }
}

private fun playlistChip(playlist: PlaylistRef, viewModel: AppViewModel): ChipItem = ChipItem(
    playlist.sourceId,
    "${playlist.title} (${playlist.trackCount})",
    onLongClick = { viewModel.playSimilar(playlist) },
) {
    viewModel.playPlaylist(playlist)
}
