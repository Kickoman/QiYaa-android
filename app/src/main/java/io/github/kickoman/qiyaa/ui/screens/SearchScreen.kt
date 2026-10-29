package io.github.kickoman.qiyaa.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.queue.AddPlacement
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.ui.Found
import io.github.kickoman.qiyaa.ui.FoundPick
import io.github.kickoman.qiyaa.ui.ListState
import io.github.kickoman.qiyaa.ui.SearchOpened
import io.github.kickoman.qiyaa.ui.SearchPart
import io.github.kickoman.qiyaa.ui.SearchPhase
import io.github.kickoman.qiyaa.ui.SearchSource
import io.github.kickoman.qiyaa.ui.SearchTarget
import io.github.kickoman.qiyaa.ui.SearchUi
import io.github.kickoman.qiyaa.ui.SearchViewModel
import io.github.kickoman.qiyaa.ui.SpotifyAccess
import io.github.kickoman.qiyaa.ui.components.Chip
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.formatTime
import io.github.kickoman.qiyaa.ui.render
import io.github.kickoman.qiyaa.ui.theme.ButtonStyle
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
fun SearchScreen(
    search: SearchViewModel,
    current: Track?,
    yandexSignedIn: Boolean,
    spotifyAccess: SpotifyAccess,
    onYandexLogin: () -> Unit,
    onSpotifyLogin: () -> Unit,
    onLeave: () -> Unit,
) {
    val colors = Qi.colors
    val ui by search.ui.collectAsStateWithLifecycle()
    val leave = { if (!search.back()) onLeave() }
    BackHandler(onBack = leave)
    val canSearch =
        when (ui.source) {
            SearchSource.YANDEX -> yandexSignedIn
            SearchSource.SPOTIFY -> spotifyAccess == SpotifyAccess.LOGGED_IN
        }
    LaunchedEffect(canSearch) {
        if (canSearch && ui.phase == SearchPhase.Idle && ui.query.isNotEmpty()) search.retry()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).tap(onClick = leave), contentAlignment = Alignment.Center) {
                QiText("‹", mono(18.sp, 400), color = colors.muted)
            }
            QiText(stringResource(R.string.search_title), CaptionStyle, color = colors.muted)
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip(stringResource(R.string.search_yandex), ui.source == SearchSource.YANDEX) {
                search.setSource(SearchSource.YANDEX)
            }
            Chip(stringResource(R.string.library_spotify), ui.source == SearchSource.SPOTIFY) {
                search.setSource(SearchSource.SPOTIFY)
            }
        }
        SearchField(
            ui.text,
            stringResource(R.string.search_hint),
            onChange = search::setText,
            onSearch = search::search,
        )
        val opened = ui.opened
        when {
            !canSearch -> LoginNote(ui.source, spotifyAccess, onYandexLogin, onSpotifyLogin)
            opened != null -> OpenedPick(opened, search, current, ui)
            else -> SearchBody(ui, search, current)
        }
    }
}

@Composable
private fun LoginNote(
    source: SearchSource,
    spotifyAccess: SpotifyAccess,
    onYandexLogin: () -> Unit,
    onSpotifyLogin: () -> Unit,
) {
    val colors = Qi.colors
    if (source == SearchSource.SPOTIFY && spotifyAccess == SpotifyAccess.CONNECTING) {
        Hint(stringResource(R.string.spotify_connecting), Modifier.padding(16.dp))
        return
    }
    val name =
        stringResource(
            if (source ==
                SearchSource.YANDEX
            ) {
                R.string.search_yandex
            } else {
                R.string.library_spotify
            },
        )
    Column(Modifier.padding(16.dp)) {
        QiText(stringResource(R.string.search_log_in, name), HintStyle, color = colors.dim)
        QiText(
            stringResource(R.string.search_log_in_action).uppercase(),
            ButtonStyle,
            Modifier.padding(top = 10.dp).tap(
                onClick = if (source ==
                    SearchSource.YANDEX
                ) {
                    onYandexLogin
                } else {
                    onSpotifyLogin
                },
            ),
            color = colors.accent,
        )
    }
}

@Composable
private fun SearchBody(ui: SearchUi, search: SearchViewModel, current: Track?) {
    val colors = Qi.colors
    when (val phase = ui.phase) {
        SearchPhase.Idle -> Hint(stringResource(R.string.search_intro), Modifier.padding(16.dp))

        SearchPhase.Searching -> Hint(stringResource(R.string.search_searching), Modifier.padding(16.dp))

        SearchPhase.NothingFound -> Hint(stringResource(R.string.search_nothing), Modifier.padding(16.dp))

        is SearchPhase.Failed -> Column(Modifier.padding(16.dp)) {
            QiText(
                stringResource(R.string.search_failed, phase.error.render()),
                HintStyle,
                color = colors.error,
            )
            QiText(
                stringResource(R.string.search_retry).uppercase(),
                ButtonStyle,
                Modifier.padding(top = 10.dp).tap(onClick = search::retry),
                color = colors.accent,
            )
        }

        is SearchPhase.Results -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            results(phase.found, ui, search, current)
        }
    }
}

private fun LazyListScope.results(found: Found, ui: SearchUi, search: SearchViewModel, current: Track?) {
    val best = found.bestTrack
    if (best != null || found.bestArtist != null || found.bestAlbum != null) {
        item(key = "best") {
            PartTitle(stringResource(R.string.search_best), total = 1, part = SearchPart.BEST, ui, search)
            best?.let { TrackRow(null, it, it.key == current?.key, ui.added[it.key], search) }
            found.bestArtist?.let { ArtistRow(it, search) }
            found.bestAlbum?.let { AlbumRow(it, search) }
        }
    }
    part(SearchPart.TRACKS, found.tracks, ui, search) { index, track ->
        TrackRow(index + 1, track, track.key == current?.key, ui.added[track.key], search)
    }
    part(SearchPart.ARTISTS, found.artists, ui, search) { _, artist -> ArtistRow(artist, search) }
    part(SearchPart.ALBUMS, found.albums, ui, search) { _, album -> AlbumRow(album, search) }
}

private fun <T> LazyListScope.part(
    part: SearchPart,
    items: List<T>,
    ui: SearchUi,
    search: SearchViewModel,
    row: @Composable (Int, T) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = part.name) {
        val title =
            when (part) {
                SearchPart.BEST -> R.string.search_best
                SearchPart.TRACKS -> R.string.search_tracks
                SearchPart.ARTISTS -> R.string.search_artists
                SearchPart.ALBUMS -> R.string.search_albums
            }
        PartTitle(stringResource(title, items.size), items.size, part, ui, search)
    }
    val shown = if (part in ui.expanded) items else items.take(part.first)
    itemsIndexed(shown, key = { index, _ -> "${part.name}:$index" }) { index, item -> row(index, item) }
}

@Composable
private fun PartTitle(title: String, total: Int, part: SearchPart, ui: SearchUi, search: SearchViewModel) {
    val colors = Qi.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QiText(title.uppercase(), LabelStyle, Modifier.weight(1f), color = colors.muted)
        if (total > part.first) {
            val more = part !in ui.expanded
            QiText(
                stringResource(if (more) R.string.search_all else R.string.search_fewer).uppercase(),
                LabelStyle,
                Modifier.tap { search.toggle(part) }.padding(vertical = 4.dp),
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun OpenedPick(opened: SearchOpened, search: SearchViewModel, current: Track?, ui: SearchUi) {
    val colors = Qi.colors
    val pick = opened.pick
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        QiText(
            "‹ " + stringResource(R.string.search_back),
            LabelStyle,
            Modifier.padding(top = 12.dp).tap { search.back() },
            color = colors.accent,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                QiText(
                    pick.name,
                    sans(16.sp, 500),
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                QiText(
                    when (pick.kind) {
                        FoundPick.Kind.ARTIST -> stringResource(R.string.search_popular)
                        FoundPick.Kind.ALBUM -> albumLine(pick)
                    },
                    mono(11.sp, 400),
                    Modifier.padding(top = 3.dp),
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val tracks = (opened.tracks as? ListState.Loaded)?.items.orEmpty()
            if (tracks.isNotEmpty()) {
                SquareButton(stringResource(R.string.search_play_all), icon = {
                    PathIcon(IconPaths.PLAY, 18.dp, colors.accent)
                }, onClick = search::playAll)
                if (pick.kind == FoundPick.Kind.ALBUM) {
                    SquareButton(stringResource(R.string.search_add)) {
                        search.chooseAdd(SearchTarget.OfAlbum(pick))
                    }
                }
            }
        }
        when (val tracks = opened.tracks) {
            ListState.Idle, ListState.Loading -> Hint(
                stringResource(R.string.library_loading),
                Modifier.padding(vertical = 16.dp),
            )

            is ListState.Failed -> Column(Modifier.padding(vertical = 16.dp)) {
                QiText(
                    stringResource(R.string.search_failed, tracks.error.render()),
                    HintStyle,
                    color = colors.error,
                )
                QiText(
                    stringResource(R.string.search_retry).uppercase(),
                    ButtonStyle,
                    Modifier.padding(top = 10.dp).tap(onClick = search::reopen),
                    color = colors.accent,
                )
            }

            is ListState.Loaded ->
                if (tracks.items.isEmpty()) {
                    Hint(stringResource(R.string.search_no_tracks), Modifier.padding(vertical = 16.dp))
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                        itemsIndexed(tracks.items, key = { index, track ->
                            "$index:${track.id}"
                        }) { index, track ->
                            TrackRow(index + 1, track, track.key == current?.key, ui.added[track.key], search)
                        }
                    }
                }
        }
    }
}

@Composable
private fun TrackRow(
    number: Int?,
    track: Track,
    current: Boolean,
    added: AddPlacement?,
    search: SearchViewModel,
) {
    val colors = Qi.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).tap { search.play(track) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (number != null) {
            QiText("$number.", mono(11.sp, 400), Modifier.width(26.dp), color = colors.dim, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            QiText(
                track.title,
                sans(14.sp, 500),
                color = if (current) colors.accent else colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QiText(
                track.artistLine + " · " + formatTime(track.durationMs),
                mono(11.sp, 400),
                Modifier.padding(top = 3.dp),
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            added?.let {
                val note = if (it == AddPlacement.NEXT) R.string.search_plays_next else R.string.search_at_end
                QiText(
                    stringResource(note),
                    mono(11.sp, 400),
                    Modifier.padding(top = 3.dp),
                    color = colors.accent,
                )
            }
        }
        SquareButton(stringResource(R.string.search_add)) { search.chooseAdd(SearchTarget.OfTrack(track)) }
    }
}

@Composable
private fun ArtistRow(artist: FoundPick, search: SearchViewModel) {
    PickRow(artist, stringResource(R.string.search_artist), search)
}

@Composable
private fun AlbumRow(album: FoundPick, search: SearchViewModel) {
    PickRow(album, albumLine(album), search)
}

@Composable
private fun albumLine(album: FoundPick): String {
    val parts = listOfNotNull(
        album.artists.joinToString(", ").takeIf { it.isNotEmpty() },
        album.year?.toString(),
        stringResource(R.string.search_single).takeIf { album.single },
    )
    return parts.joinToString(" · ")
}

@Composable
private fun PickRow(pick: FoundPick, line: String, search: SearchViewModel) {
    val colors = Qi.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).tap { search.openPick(pick) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            QiText(
                pick.name,
                sans(14.sp, 500),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QiText(
                line,
                mono(11.sp, 400),
                Modifier.padding(top = 3.dp),
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SquareButton(stringResource(R.string.search_play), icon = {
            PathIcon(IconPaths.PLAY, 18.dp, colors.accent)
        }, onClick = { search.playPick(pick) })
        if (pick.kind == FoundPick.Kind.ALBUM) {
            SquareButton(stringResource(R.string.search_add)) { search.chooseAdd(SearchTarget.OfAlbum(pick)) }
        }
    }
}

@Composable
private fun SquareButton(
    description: String,
    icon: @Composable () -> Unit = { QiText("+", mono(20.sp, 400), color = Qi.colors.accent) },
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Qi.colors.surface)
            .tap(onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    QiText(text, HintStyle, modifier, color = Qi.colors.dim)
}

@Composable
fun SearchAddSheet(target: SearchTarget, onAdd: (AddPlacement) -> Unit, onClose: () -> Unit) {
    val colors = Qi.colors
    val (title, line) =
        when (target) {
            is SearchTarget.OfTrack -> target.track.title to target.track.artistLine
            is SearchTarget.OfAlbum -> target.album.name to target.album.artists.joinToString(", ")
        }
    val paneLabel = stringResource(R.string.search_add)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(colors.deep.copy(alpha = 0.7f)).tap(onClick = onClose))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(colors.surface)
                .pointerInput(Unit) { detectTapGestures() }
                .semantics { paneTitle = paneLabel }
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.handle),
            )
            QiText(
                title,
                sans(14.sp, 500),
                Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp),
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QiText(
                line,
                mono(11.sp, 400),
                Modifier.padding(start = 12.dp, end = 12.dp, top = 3.dp, bottom = 8.dp),
                color = colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SheetRow(stringResource(R.string.search_add_next)) { onAdd(AddPlacement.NEXT) }
            SheetRow(stringResource(R.string.search_add_end)) { onAdd(AddPlacement.END) }
        }
    }
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .tap(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QiText(label, ButtonStyle, color = Qi.colors.text, maxLines = 1)
    }
}
