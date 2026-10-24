package io.github.kickoman.qiyaa.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.jam.AddAction
import io.github.kickoman.qiyaa.jam.GuestPhase
import io.github.kickoman.qiyaa.jam.JamGuestState
import io.github.kickoman.qiyaa.jam.JamParticipant
import io.github.kickoman.qiyaa.jam.JamQueueItem
import io.github.kickoman.qiyaa.jam.JamRoom
import io.github.kickoman.qiyaa.jam.JamStatus
import io.github.kickoman.qiyaa.jam.NowPlayingSource
import io.github.kickoman.qiyaa.jam.ParticipantKind
import io.github.kickoman.qiyaa.jam.positionMsAt
import io.github.kickoman.qiyaa.playback.JamTracks
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.ui.GuestTab
import io.github.kickoman.qiyaa.ui.JamGuestViewModel
import io.github.kickoman.qiyaa.ui.ListState
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.Divider
import io.github.kickoman.qiyaa.ui.components.IconWideButton
import io.github.kickoman.qiyaa.ui.components.InsetField
import io.github.kickoman.qiyaa.ui.components.LedDot
import io.github.kickoman.qiyaa.ui.components.LikeButton
import io.github.kickoman.qiyaa.ui.components.Note
import io.github.kickoman.qiyaa.ui.components.Panel
import io.github.kickoman.qiyaa.ui.components.PillToggle
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.SectionLabel
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.endText
import io.github.kickoman.qiyaa.ui.formatTime
import io.github.kickoman.qiyaa.ui.guestRefusedText
import io.github.kickoman.qiyaa.ui.render
import io.github.kickoman.qiyaa.ui.statusText
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import io.github.kickoman.qiyaa.yandex.PlaylistRef
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val LEAVE_CONFIRM_MS = 3_000L
private const val TICK_MS = 1_000L
private val COVER_SIZE = 64.dp
private val ADD_CELL_WIDTH = 104.dp

@Composable
fun JoinJamSection(onJoin: (String) -> Boolean) {
    val colors = Qi.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val join = { if (onJoin(text)) text = "" }
    SectionLabel(stringResource(R.string.jam_join_title))
    InsetField(
        value = text,
        onValueChange = { text = it },
        hint = stringResource(R.string.jam_join_hint),
        imeAction = ImeAction.Go,
        keyboardType = KeyboardType.Uri,
        onAction = join,
    )
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton(stringResource(R.string.jam_join_paste).uppercase(), Modifier.weight(1f)) {
            scope.launch {
                val clip = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                clip?.getItemAt(0)?.text?.let { text = it.toString().replace('\n', ' ').trim() }
            }
        }
        ActionButton(
            stringResource(R.string.jam_join_go).uppercase(),
            Modifier.weight(1f),
            background = colors.accentBackground,
            color = colors.accent,
            onClick = join,
        )
    }
}

@Composable
fun JamGuestScreen(
    viewModel: JamGuestViewModel,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    onJoinLink: (String) -> Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room = state.room
    val close = {
        viewModel.leave()
        onClose()
    }
    when (val phase = state.phase) {
        GuestPhase.None -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(stringResource(R.string.jam_title))
            JoinJamSection(onJoinLink)
        }

        GuestPhase.NeedName -> NameStep(viewModel, state, onCancel = close)

        GuestPhase.InRoom, GuestPhase.WaitingForRoom ->
            if (room != null) Room(viewModel, state, room, onAdd, onLeft = close) else NoRoom(state, close)

        GuestPhase.Joining -> NoRoom(state, close)

        else -> phase.endText()?.let { EndStep(state, stringResource(it), close) }
    }
}

@Composable
private fun GuestHeader(state: JamGuestState) {
    val hostName = state.room?.hostName
    val title =
        if (hostName != null) {
            stringResource(R.string.jam_guest_title, hostName)
        } else {
            stringResource(R.string.jam_title)
        }
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QiText(
            title,
            CaptionStyle,
            Modifier.weight(1f),
            color = Qi.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.phase.isActive) GuestStatus(state)
    }
}

@Composable
private fun GuestStatus(state: JamGuestState) {
    val colors = Qi.colors
    val (text, color) =
        when {
            state.connected -> R.string.jam_status_online to colors.accent

            state.connection == JamStatus.CONNECTING || state.connection == JamStatus.ONLINE ->
                R.string.jam_status_connecting to colors.muted

            else -> R.string.jam_status_offline to colors.error
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LedDot(color)
        QiText(stringResource(text).uppercase(), ReadoutStyle, color = color, maxLines = 1)
    }
}

@Composable
private fun NameStep(viewModel: JamGuestViewModel, state: JamGuestState, onCancel: () -> Unit) {
    val colors = Qi.colors
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GuestHeader(state)
        QiText(
            stringResource(R.string.jam_guest_invited),
            sans(14.sp, 400, lineHeight = 20.sp),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            color = colors.textSecondary,
        )
        QiText(
            state.serverUrl.orEmpty().removePrefix("https://"),
            mono(12.sp, 500),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            color = colors.accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        SectionLabel(stringResource(R.string.jam_guest_name))
        InsetField(
            value = ui.name,
            onValueChange = viewModel::setName,
            hint = "",
            imeAction = ImeAction.Go,
            onAction = viewModel::join,
        )
        ActionButton(
            stringResource(R.string.jam_join_go).uppercase(),
            Modifier.padding(16.dp).fillMaxWidth(),
            background = colors.accent,
            color = colors.deep,
            height = 48.dp,
            onClick = viewModel::join,
        )
        ActionButton(
            stringResource(R.string.jam_cancel).uppercase(),
            Modifier.padding(start = 16.dp, end = 16.dp).fillMaxWidth(),
            onClick = onCancel,
        )
    }
}

@Composable
private fun NoRoom(state: JamGuestState, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        GuestHeader(state)
        val status = state.statusText()
        if (status != null) {
            StatusLine(stringResource(status))
        } else {
            QiText(
                stringResource(R.string.jam_guest_joining).uppercase(),
                ReadoutStyle,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
                color = Qi.colors.muted,
            )
        }
        ActionButton(
            stringResource(R.string.jam_cancel).uppercase(),
            Modifier.padding(16.dp).fillMaxWidth(),
            onClick = onCancel,
        )
    }
}

@Composable
private fun EndStep(state: JamGuestState, text: String, onClose: () -> Unit) {
    val colors = Qi.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GuestHeader(state)
        QiText(
            text,
            sans(15.sp, 400, lineHeight = 21.sp),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            color = colors.text,
        )
        ActionButton(
            stringResource(R.string.jam_guest_close).uppercase(),
            Modifier.padding(16.dp).fillMaxWidth(),
            background = colors.accent,
            color = colors.deep,
            height = 48.dp,
            onClick = onClose,
        )
    }
}

@Composable
private fun Room(
    viewModel: JamGuestViewModel,
    state: JamGuestState,
    room: JamRoom,
    onAdd: () -> Unit,
    onLeft: () -> Unit,
) {
    val colors = Qi.colors
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    var serverNow by remember { mutableLongStateOf(viewModel.serverNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            serverNow = viewModel.serverNow()
            delay(TICK_MS)
        }
    }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(confirmLeave) {
        if (confirmLeave) {
            delay(LEAVE_CONFIRM_MS)
            confirmLeave = false
        }
    }
    val you = room.you.publicId
    val someone = stringResource(R.string.jam_guest_someone)
    val names = room.participants.associate { it.publicId to it.name }
    val max = room.settings.maxPendingPerGuest

    LazyColumn(Modifier.fillMaxSize()) {
        item { GuestHeader(state) }
        item { state.statusText()?.let { StatusLine(stringResource(it)) } }
        item {
            NowPlaying(
                viewModel,
                state,
                room,
                serverNow,
                likedIds,
                room.nowPlaying.addedBy?.let(names::get) ?: someone,
            )
        }
        item {
            val usable = state.connected && room.hostOnline
            ActionButton(
                stringResource(R.string.jam_add_tracks).uppercase(),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(),
                background = if (usable) colors.accent else colors.surface2,
                color = if (usable) colors.deep else colors.dimmer,
                height = 48.dp,
                onClick = onAdd,
            )
        }
        item {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                QiText(stringResource(R.string.jam_guest_queue).uppercase(), LabelStyle, color = colors.dim)
                QiText(
                    stringResource(R.string.jam_guest_yours, state.waitingOfYours, max).uppercase(),
                    LabelStyle,
                    color = colors.accent,
                )
            }
        }
        if (room.queue.isEmpty()) item { Note(stringResource(R.string.jam_guest_queue_empty)) }
        itemsIndexed(room.queue, key = { _, item -> item.itemId }) { index, item ->
            QueueRow(item, index, room.queue.size, item.addedBy == you, names[item.addedBy] ?: someone) {
                viewModel.remove(item.itemId)
            }
        }
        if (state.waitingOfYours >= max) {
            item { Note(pluralStringResource(R.plurals.jam_guest_limit_hint, max, max)) }
        }
        item { SectionLabel(stringResource(R.string.jam_guest_people, room.participants.size)) }
        itemsIndexed(room.participants, key = { _, person -> person.publicId }) { index, person ->
            PersonRow(person, index, room.participants.size, person.publicId == you)
        }
        item {
            val leave = if (confirmLeave) R.string.jam_guest_leave_confirm else R.string.jam_guest_leave
            ActionButton(
                stringResource(leave).uppercase(),
                Modifier.padding(16.dp).fillMaxWidth(),
                background = if (confirmLeave) colors.error else colors.surface2,
                color = if (confirmLeave) colors.deep else colors.error,
                height = 48.dp,
            ) {
                if (confirmLeave) onLeft() else confirmLeave = true
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    val colors = Qi.colors
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.deep)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LedDot(colors.error)
        QiText(text, mono(11.sp, 400, lineHeight = 16.sp), color = colors.error)
    }
}

@Composable
private fun NowPlaying(
    viewModel: JamGuestViewModel,
    state: JamGuestState,
    room: JamRoom,
    serverNow: Long,
    likedIds: Set<String>,
    adderName: String,
) {
    val colors = Qi.colors
    val playing = room.nowPlaying
    val track = playing.track
    SectionLabel(stringResource(R.string.jam_guest_now_playing))
    if (playing.source == NowPlayingSource.IDLE || track == null) {
        Note(stringResource(R.string.jam_guest_silence))
        return
    }
    val yours = playing.source == NowPlayingSource.ITEM && playing.addedBy == room.you.publicId
    val addedBy =
        when {
            playing.source == NowPlayingSource.WAVE -> stringResource(R.string.jam_guest_from_host)
            yours -> stringResource(R.string.jam_guest_added_by_you)
            else -> stringResource(R.string.jam_guest_added_by, adderName)
        }
    Panel {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(COVER_SIZE).clip(RoundedCornerShape(6.dp)).background(colors.surface2)) {
                val cover = if (track.isCatalog) JamTracks.fromJam(track).coverUrl else null
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                QiText(
                    track.title,
                    sans(15.sp, 500),
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                QiText(
                    track.artists.joinToString(", "),
                    mono(11.sp, 400),
                    Modifier.padding(top = 3.dp),
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                QiText(
                    addedBy,
                    HintStyle,
                    Modifier.padding(top = 6.dp),
                    color = if (yours) colors.accent else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QiText(
                formatTime(playing.positionMsAt(serverNow)) + " / " + formatTime(track.durationMs),
                mono(12.sp, 500, 1.sp),
                Modifier.weight(1f),
                color = colors.textSecondary,
                maxLines = 1,
            )
            if (track.isCatalog) {
                LikeButton(
                    track.id in likedIds,
                    Modifier.width(56.dp),
                    height = 40.dp,
                    onClick = viewModel::toggleLike,
                )
            }
            if (state.canSkip) {
                ActionButton(
                    stringResource(R.string.jam_guest_skip).uppercase(),
                    Modifier.width(116.dp),
                    height = 40.dp,
                    onClick = viewModel::skip,
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: JamQueueItem,
    index: Int,
    count: Int,
    yours: Boolean,
    adderName: String,
    onRemove: () -> Unit,
) {
    val colors = Qi.colors
    PanelItem(index, count) {
        ItemRow {
            Column(Modifier.weight(1f)) {
                QiText(
                    item.track.title,
                    sans(14.sp, 500),
                    color = if (yours) colors.accent else colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val who = if (yours) stringResource(R.string.jam_guest_you) else adderName
                QiText(
                    item.track.artists.joinToString(", ") + " · " + who,
                    mono(11.sp, 400),
                    Modifier.padding(top = 3.dp),
                    color = colors.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.pinned) {
                QiText(stringResource(R.string.jam_guest_next).uppercase(), LabelStyle, color = colors.accent)
            }
            if (yours) {
                IconWideButton(
                    stringResource(R.string.jam_guest_remove),
                    Modifier.width(40.dp),
                    height = 36.dp,
                    onClick = onRemove,
                ) {
                    QiText("×", mono(16.sp, 500), color = colors.muted)
                }
            }
        }
    }
}

@Composable
private fun PersonRow(person: JamParticipant, index: Int, count: Int, isYou: Boolean) {
    val colors = Qi.colors
    PanelItem(index, count) {
        ItemRow {
            LedDot(if (person.online) colors.accent else colors.dimmer)
            QiText(
                person.name,
                sans(14.sp, 500),
                Modifier.weight(1f),
                color = if (person.online) colors.text else colors.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val kind =
                when (person.kind) {
                    ParticipantKind.HOST -> R.string.jam_kind_host
                    ParticipantKind.WEB -> R.string.jam_kind_web
                    ParticipantKind.QIYAA -> R.string.jam_kind_qiyaa
                }
            val meta =
                stringResource(kind) + if (isYou) " · " + stringResource(R.string.jam_guest_you) else ""
            QiText(meta, HintStyle, color = if (isYou) colors.accent else colors.dim, maxLines = 1)
        }
    }
}

@Composable
fun JamGuestAddScreen(viewModel: JamGuestViewModel, onBack: () -> Unit) {
    val colors = Qi.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = ui.tab == GuestTab.PLAYLISTS && ui.playlist != null) { viewModel.closePlaylist() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.jam_add_tracks)) {
            QiText(
                stringResource(R.string.jam_done).uppercase(),
                mono(11.sp, 500, 1.sp),
                Modifier.tap(onClick = onBack),
                color = colors.muted,
            )
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((tab, label) in listOf(
                GuestTab.SEARCH to R.string.jam_guest_tab_search,
                GuestTab.LIKED to R.string.jam_guest_tab_liked,
                GuestTab.PLAYLISTS to R.string.jam_guest_tab_playlists,
            )) {
                PillToggle(stringResource(label).uppercase(), ui.tab == tab) { viewModel.setTab(tab) }
            }
        }
        AddStatus(state)
        Spacer(Modifier.height(12.dp))
        when (ui.tab) {
            GuestTab.SEARCH -> {
                InsetField(
                    value = ui.searchText,
                    onValueChange = viewModel::setSearchText,
                    hint = stringResource(R.string.jam_guest_search_hint),
                    imeAction = ImeAction.Search,
                    onAction = viewModel::search,
                )
                Tracks(ui.results, state, viewModel, loadingText = stringResource(R.string.jam_searching))
            }

            GuestTab.LIKED -> {
                val liked by viewModel.likedShown.collectAsStateWithLifecycle()
                InsetField(
                    value = ui.likedFilter,
                    onValueChange = viewModel::setLikedFilter,
                    hint = stringResource(R.string.jam_guest_filter_hint),
                )
                Tracks(liked, state, viewModel)
            }

            GuestTab.PLAYLISTS -> {
                val playlist = ui.playlist
                if (playlist == null) {
                    val playlists by viewModel.playlists.state.collectAsStateWithLifecycle()
                    Playlists(playlists, viewModel)
                } else {
                    QiText(
                        "‹ " + playlist.title,
                        sans(15.sp, 500),
                        Modifier
                            .fillMaxWidth()
                            .tap(onClick = viewModel::closePlaylist)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Tracks(ui.playlistTracks, state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun AddStatus(state: JamGuestState) {
    val room = state.room
    val status = state.statusText()
    when {
        status != null -> StatusLine(stringResource(status))

        room != null && state.waitingOfYours >= room.settings.maxPendingPerGuest -> {
            val max = room.settings.maxPendingPerGuest
            Note(pluralStringResource(R.plurals.jam_guest_limit_hint, max, max), Qi.colors.accent)
        }
    }
}

@Composable
private fun ColumnScope.Tracks(
    list: ListState<Track>,
    state: JamGuestState,
    viewModel: JamGuestViewModel,
    loadingText: String = stringResource(R.string.library_loading),
) {
    when (list) {
        ListState.Idle -> Unit

        ListState.Loading -> Note(loadingText)

        is ListState.Failed -> LoadFailed(list, viewModel)

        is ListState.Loaded ->
            if (list.items.isEmpty()) {
                Note(stringResource(R.string.jam_nothing_found))
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                    itemsIndexed(list.items) { index, track ->
                        TrackRow(track, index, list.items.size, state) { viewModel.add(track) }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
    }
}

@Composable
private fun ColumnScope.Playlists(list: ListState<PlaylistRef>, viewModel: JamGuestViewModel) {
    val colors = Qi.colors
    when (list) {
        ListState.Idle -> Unit

        ListState.Loading -> Note(stringResource(R.string.library_loading))

        is ListState.Failed -> LoadFailed(list, viewModel)

        is ListState.Loaded ->
            if (list.items.isEmpty()) {
                Note(stringResource(R.string.jam_nothing_found))
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    itemsIndexed(list.items) { index, playlist ->
                        PanelItem(index, list.items.size) {
                            ItemRow(Modifier.tap { viewModel.openPlaylist(playlist) }) {
                                QiText(
                                    playlist.title,
                                    sans(14.sp, 500),
                                    Modifier.weight(1f),
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                QiText(playlist.trackCount.toString(), HintStyle, color = colors.dim)
                                QiText("›", mono(14.sp, 400), color = colors.dim)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
    }
}

@Composable
private fun LoadFailed(list: ListState.Failed, viewModel: JamGuestViewModel) {
    val colors = Qi.colors
    Note(stringResource(R.string.library_error, list.error.render()), colors.error)
    QiText(
        stringResource(R.string.library_retry),
        LabelStyle,
        Modifier.tap(onClick = viewModel::retry).padding(16.dp),
        color = colors.accent,
    )
}

@Composable
private fun TrackRow(track: Track, index: Int, count: Int, state: JamGuestState, onAdd: () -> Unit) {
    val colors = Qi.colors
    val action = state.addAction(track.id)
    PanelItem(index, count) {
        ItemRow {
            Column(Modifier.weight(1f)) {
                QiText(
                    track.title,
                    sans(14.sp, 500),
                    color = colors.text,
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
            }
            Box(Modifier.width(ADD_CELL_WIDTH), contentAlignment = Alignment.CenterEnd) {
                AddCell(action, state.connected, onAdd)
            }
        }
        if (action is AddAction.Failed) {
            QiText(
                stringResource(guestRefusedText(action.reason)),
                mono(11.sp, 400, lineHeight = 16.sp),
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                color = colors.error,
            )
        }
    }
}

@Composable
private fun AddCell(action: AddAction?, connected: Boolean, onAdd: () -> Unit) {
    val colors = Qi.colors
    when (action) {
        AddAction.Yours -> AddLabel(stringResource(R.string.jam_guest_yours_queued), colors.accent)

        is AddAction.QueuedBy -> AddLabel(
            action.name?.let { stringResource(R.string.jam_guest_queued_by, it) }
                ?: stringResource(R.string.jam_guest_queued),
            colors.muted,
        )

        is AddAction.Limit -> AddLabel(stringResource(R.string.jam_guest_limit), colors.dim)

        AddAction.Sending -> AddButton(stringResource(R.string.jam_guest_sending), false) {}

        AddAction.Add, is AddAction.Failed -> AddButton(
            stringResource(R.string.jam_guest_add),
            connected,
            onAdd,
        )

        AddAction.HostOffline, null -> AddButton(stringResource(R.string.jam_guest_add), false, onAdd)
    }
}

@Composable
private fun AddButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Qi.colors
    ActionButton(
        text.uppercase(),
        Modifier.fillMaxWidth(),
        height = 36.dp,
        background = if (enabled) colors.accentBackground else colors.surface,
        color = if (enabled) colors.accent else colors.dimmer,
        onClick = onClick,
    )
}

@Composable
private fun AddLabel(text: String, color: Color) {
    QiText(
        text.uppercase(),
        mono(10.sp, 500, 1.sp, lineHeight = 14.sp),
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun PanelItem(index: Int, count: Int, content: @Composable ColumnScope.() -> Unit) {
    val top = if (index == 0) 8.dp else 0.dp
    val bottom = if (index == count - 1) 8.dp else 0.dp
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(top, top, bottom, bottom))
            .background(Qi.colors.surface),
    ) {
        if (index > 0) Divider()
        content()
    }
}

@Composable
private fun ItemRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
