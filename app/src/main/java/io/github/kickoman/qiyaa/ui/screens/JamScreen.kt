package io.github.kickoman.qiyaa.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.jam.JamOrder
import io.github.kickoman.qiyaa.jam.JamParticipant
import io.github.kickoman.qiyaa.jam.JamRoom
import io.github.kickoman.qiyaa.jam.JamStatus
import io.github.kickoman.qiyaa.jam.ParticipantKind
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.playback.JamHostState
import io.github.kickoman.qiyaa.ui.JamViewModel
import io.github.kickoman.qiyaa.ui.ListState
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.LedDot
import io.github.kickoman.qiyaa.ui.components.PillToggle
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.QrCodeImage
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.formatTime
import io.github.kickoman.qiyaa.ui.render
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import io.github.kickoman.qiyaa.yandex.Track
import kotlinx.coroutines.delay

private const val END_CONFIRM_MS = 3_000L
private val ROW_BUTTON_WIDTH = 76.dp

@Composable
fun JamScreen(viewModel: JamViewModel, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.jam_title)) {
            if (state.phase == JamHostPhase.ACTIVE) ConnectionStatus(state)
            QiText(
                stringResource(R.string.jam_settings_open).uppercase(),
                mono(11.sp, 500, 1.sp),
                Modifier.padding(start = 14.dp).tap(onClick = onSettings),
                color = Qi.colors.muted,
            )
        }
        when (state.phase) {
            JamHostPhase.NONE -> StartJam(viewModel, onSettings)
            JamHostPhase.CREATING -> Connecting(viewModel)
            JamHostPhase.ACTIVE -> ActiveJam(viewModel, state)
        }
    }
}

@Composable
private fun ConnectionStatus(state: JamHostState) {
    val colors = Qi.colors
    val (text, color) =
        when {
            state.connected -> R.string.jam_status_online to colors.accent
            state.connection == JamStatus.CONNECTING -> R.string.jam_status_connecting to colors.muted
            else -> R.string.jam_status_offline to colors.error
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LedDot(color)
        QiText(stringResource(text).uppercase(), ReadoutStyle, color = color, maxLines = 1)
    }
}

@Composable
private fun StartJam(viewModel: JamViewModel, onSettings: () -> Unit) {
    val colors = Qi.colors
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val server by viewModel.settings.jamServer.collectAsStateWithLifecycle()
    QiText(
        stringResource(R.string.jam_intro),
        sans(14.sp, 400, lineHeight = 20.sp),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        color = colors.textSecondary,
    )
    SectionLabel(stringResource(R.string.jam_your_name))
    InsetField(
        value = ui.hostName,
        onValueChange = viewModel::setHostName,
        hint = "",
        imeAction = ImeAction.Go,
        onAction = viewModel::start,
    )
    if (server.isBlank()) {
        QiText(
            stringResource(R.string.jam_not_configured),
            HintStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            color = colors.error,
        )
        ActionButton(
            stringResource(R.string.jam_settings_title).uppercase(),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth(),
            onClick = onSettings,
        )
    }
    ActionButton(
        stringResource(R.string.jam_start).uppercase(),
        Modifier.padding(16.dp).fillMaxWidth(),
        background = colors.accent,
        color = colors.deep,
        height = 48.dp,
        onClick = viewModel::start,
    )
}

@Composable
private fun Connecting(viewModel: JamViewModel) {
    val colors = Qi.colors
    QiText(
        stringResource(R.string.jam_connecting).uppercase(),
        ReadoutStyle,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
        color = colors.muted,
    )
    ActionButton(
        stringResource(R.string.jam_cancel).uppercase(),
        Modifier.padding(16.dp).fillMaxWidth(),
        onClick = viewModel::cancelStart,
    )
}

@Composable
private fun ActiveJam(viewModel: JamViewModel, state: JamHostState) {
    val colors = Qi.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val joinUrl = state.joinUrl.orEmpty()
    val room = state.room
    val enabled = state.connected

    if (joinUrl.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
            QrCodeImage(joinUrl, Modifier.fillMaxWidth(0.72f))
        }
        QiText(
            joinUrl,
            mono(11.sp, 400),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            color = colors.dim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val shareText = stringResource(R.string.jam_share_text, joinUrl)
        val shareTitle = stringResource(R.string.jam_share)
        val copied = stringResource(R.string.jam_copied)
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                shareTitle.uppercase(),
                Modifier.weight(2f),
                background = colors.accent,
                color = colors.deep,
            ) {
                val send = Intent(
                    Intent.ACTION_SEND,
                ).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                context.startActivity(Intent.createChooser(send, shareTitle))
            }
            ActionButton(stringResource(R.string.jam_copy).uppercase(), Modifier.weight(1f)) {
                clipboard.setText(AnnotatedString(joinUrl))
                viewModel.say(copied)
            }
        }
    }

    AddTracks(viewModel, enabled)
    if (room != null) Guests(viewModel, room, enabled)
    if (room != null) RoomSettings(viewModel, room, enabled)

    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(confirmEnd) {
        if (confirmEnd) {
            delay(END_CONFIRM_MS)
            confirmEnd = false
        }
    }
    ActionButton(
        stringResource(if (confirmEnd) R.string.jam_end_confirm else R.string.jam_end).uppercase(),
        Modifier.padding(16.dp).fillMaxWidth(),
        background = if (confirmEnd) colors.error else colors.surface2,
        color = if (confirmEnd) colors.deep else colors.error,
        height = 48.dp,
    ) {
        if (confirmEnd) viewModel.end() else confirmEnd = true
    }
}

@Composable
private fun AddTracks(viewModel: JamViewModel, enabled: Boolean) {
    val colors = Qi.colors
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    SectionLabel(stringResource(R.string.jam_add_tracks))
    InsetField(
        value = ui.searchText,
        onValueChange = viewModel::setSearchText,
        hint = stringResource(R.string.jam_search_hint),
        imeAction = ImeAction.Search,
        onAction = { viewModel.search() },
    )
    when (val results = ui.results) {
        ListState.Idle -> Unit
        ListState.Loading -> Note(stringResource(R.string.jam_searching))
        is ListState.Failed -> Note(
            stringResource(R.string.jam_search_failed, results.error.render()),
            colors.error,
        )
        is ListState.Loaded ->
            if (results.items.isEmpty()) {
                Note(stringResource(R.string.jam_nothing_found))
            } else {
                Panel {
                    results.items.forEachIndexed { index, track ->
                        ResultRow(track, enabled, last = index == results.items.lastIndex, viewModel)
                    }
                }
            }
    }
}

@Composable
private fun ResultRow(track: Track, enabled: Boolean, last: Boolean, viewModel: JamViewModel) {
    val colors = Qi.colors
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        val textColor = if (enabled) colors.text else colors.dimmer
        ActionButton(
            stringResource(R.string.jam_next).uppercase(),
            Modifier.width(ROW_BUTTON_WIDTH),
            height = 36.dp,
            color = textColor,
        ) { viewModel.playNext(track) }
        ActionButton(
            stringResource(R.string.jam_add).uppercase(),
            Modifier.width(ROW_BUTTON_WIDTH),
            height = 36.dp,
            background = if (enabled) colors.accentBackground else colors.surface,
            color = if (enabled) colors.accent else colors.dimmer,
        ) { viewModel.add(track) }
    }
    if (!last) Divider()
}

@Composable
private fun Guests(viewModel: JamViewModel, room: JamRoom, enabled: Boolean) {
    val colors = Qi.colors
    SectionLabel(
        stringResource(
            R.string.jam_guests,
            room.participants.count {
                it.kind != ParticipantKind.HOST
            },
        ),
    )
    Panel {
        room.participants.forEachIndexed { index, participant ->
            GuestRow(participant, enabled, viewModel)
            if (index != room.participants.lastIndex) Divider()
        }
    }
    if (!room.hostOnline && enabled) Note(stringResource(R.string.jam_status_offline), colors.error)
}

@Composable
private fun GuestRow(participant: JamParticipant, enabled: Boolean, viewModel: JamViewModel) {
    val colors = Qi.colors
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LedDot(if (participant.online) colors.accent else colors.dimmer)
        QiText(
            participant.name,
            sans(14.sp, 500),
            Modifier.weight(1f),
            color = if (participant.online) colors.text else colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val kind =
            when (participant.kind) {
                ParticipantKind.HOST -> R.string.jam_kind_host
                ParticipantKind.WEB -> R.string.jam_kind_web
                ParticipantKind.QIYAA -> R.string.jam_kind_qiyaa
            }
        val meta =
            stringResource(kind) +
                if (participant.pending >
                    0
                ) {
                    " · " + stringResource(R.string.jam_waiting, participant.pending)
                } else {
                    ""
                }
        QiText(meta, HintStyle, color = colors.dim, maxLines = 1)
        if (participant.kind != ParticipantKind.HOST) {
            ActionButton(
                stringResource(R.string.jam_kick).uppercase(),
                Modifier.width(ROW_BUTTON_WIDTH),
                height = 32.dp,
                color = if (enabled) colors.muted else colors.dimmer,
            ) { viewModel.kick(participant.publicId) }
        }
    }
}

@Composable
private fun RoomSettings(viewModel: JamViewModel, room: JamRoom, enabled: Boolean) {
    val settings = room.settings
    SectionLabel(stringResource(R.string.jam_room_settings))
    Panel {
        SettingRow(stringResource(R.string.jam_order)) {
            for ((order, label) in listOf(
                JamOrder.ROUND_ROBIN to R.string.jam_order_round_robin,
                JamOrder.FIFO to R.string.jam_order_fifo,
            )) {
                PillToggle(stringResource(label).uppercase(), settings.order == order) {
                    if (settings.order != order) viewModel.setOrder(order)
                }
            }
        }
        Divider()
        SettingRow(stringResource(R.string.jam_guests_can_skip)) {
            OnOff(settings.guestsCanSkip, R.string.jam_on, R.string.jam_off, viewModel::setGuestsCanSkip)
        }
        Divider()
        SettingRow(stringResource(R.string.jam_join)) {
            OnOff(settings.joinOpen, R.string.jam_join_open, R.string.jam_join_closed, viewModel::setJoinOpen)
        }
    }
    ActionButton(
        stringResource(R.string.jam_new_link).uppercase(),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth(),
        color = if (enabled) Qi.colors.text else Qi.colors.dimmer,
        onClick = viewModel::rotateLink,
    )
}

@Composable
fun JamSettingsScreen(viewModel: JamViewModel, onDone: () -> Unit) {
    val colors = Qi.colors
    val server by viewModel.settings.jamServer.collectAsStateWithLifecycle()
    val feedback by viewModel.settings.jamWaveFeedback.collectAsStateWithLifecycle()
    val share by viewModel.settings.jamShareAudio.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.jam_settings_title))
        SectionLabel(stringResource(R.string.jam_server))
        InsetField(
            value = server,
            onValueChange = viewModel::setServer,
            hint = stringResource(R.string.jam_server_hint),
            keyboardType = KeyboardType.Uri,
        )
        SectionLabel(stringResource(R.string.jam_wave_feedback))
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnOff(feedback, R.string.jam_on, R.string.jam_off, viewModel::setWaveFeedback)
        }
        Note(stringResource(R.string.jam_wave_feedback_note))
        SectionLabel(stringResource(R.string.jam_share_audio))
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnOff(share, R.string.jam_on, R.string.jam_off, viewModel::setShareAudio)
        }
        Note(stringResource(R.string.jam_share_audio_note))
        ActionButton(
            stringResource(R.string.jam_done).uppercase(),
            Modifier.padding(16.dp).fillMaxWidth(),
            background = colors.accent,
            color = colors.deep,
            onClick = onDone,
        )
    }
}

/** HOST-23: asked over any screen while a stored jam waits. */
@Composable
fun JamContinueDialog(onContinue: () -> Unit, onEnd: () -> Unit) {
    val colors = Qi.colors
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).tap {
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(18.dp),
        ) {
            QiText(stringResource(R.string.jam_continue_title).uppercase(), LabelStyle, color = colors.accent)
            QiText(
                stringResource(R.string.jam_continue_text),
                sans(15.sp, 400, lineHeight = 21.sp),
                Modifier.padding(top = 12.dp),
                color = colors.text,
            )
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    stringResource(R.string.jam_continue_no).uppercase(),
                    Modifier.weight(1f),
                    background = colors.surface2,
                    onClick = onEnd,
                )
                ActionButton(
                    stringResource(R.string.jam_continue_yes).uppercase(),
                    Modifier.weight(1f),
                    background = colors.accent,
                    color = colors.deep,
                    onClick = onContinue,
                )
            }
        }
    }
}

@Composable
private fun OnOff(value: Boolean, onText: Int, offText: Int, onChange: (Boolean) -> Unit) {
    PillToggle(stringResource(onText).uppercase(), value) { if (!value) onChange(true) }
    PillToggle(stringResource(offText).uppercase(), !value) { if (value) onChange(false) }
}

@Composable
private fun SectionLabel(text: String) {
    QiText(
        text.uppercase(),
        LabelStyle,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        color = Qi.colors.dim,
    )
}

@Composable
private fun Note(text: String, color: Color = Qi.colors.dim) {
    QiText(
        text,
        mono(11.sp, 400, lineHeight = 17.sp),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        color = color,
    )
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Qi.colors.surface),
        content = content,
    )
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Qi.colors.background))
}

@Composable
private fun SettingRow(label: String, pills: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QiText(label, sans(14.sp, 500), Modifier.weight(1f), color = Qi.colors.text, maxLines = 2)
        pills()
    }
}

@Composable
private fun InsetField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    onAction: () -> Unit = {},
) {
    val colors = Qi.colors
    val style = mono(13.sp, 400).copy(color = colors.text)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.deep)
            .border(1.dp, colors.insetBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onAction() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) {
                    QiText(hint, style, color = colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            },
        )
    }
}
