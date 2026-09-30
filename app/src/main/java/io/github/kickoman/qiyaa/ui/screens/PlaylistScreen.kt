package io.github.kickoman.qiyaa.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.queue.JamSlot
import io.github.kickoman.qiyaa.ui.JamViewModel
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.formatTime
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
fun PlaylistScreen(viewModel: PlayerViewModel, jam: JamViewModel, onAdd: () -> Unit) {
    val colors = Qi.colors
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val queue by viewModel.queue.state.collectAsStateWithLifecycle()
    val jamState by jam.state.collectAsStateWithLifecycle()
    // HOST-34: who added each jam item, by `addedBy` among the participants.
    val names = jamState.room?.participants?.associate { it.publicId to it.name }.orEmpty()
    val waveMark = stringResource(R.string.jam_wave_mark)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.playlist_title)) {
            QiText(
                stringResource(
                    R.string.playlist_loaded,
                    queueTitle(queue.title, queue.isWave),
                    queue.tracks.size,
                ),
                mono(11.sp, 500, 1.sp),
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.deep)
                .border(1.dp, colors.insetBorder, RoundedCornerShape(6.dp)),
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                itemsIndexed(queue.tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
                    val selected = index in queue.selected
                    val current = index == ui.index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(if (selected) colors.surface else colors.deep)
                            .tap { viewModel.playIndex(index) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.width(32.dp).height(52.dp).tap { viewModel.queue.toggleSelected(index) },
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            QiText(
                                "${index + 1}",
                                mono(12.sp, 400),
                                color = if (selected) colors.text else colors.dim,
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            QiText(
                                track.title,
                                sans(14.sp, 500),
                                color = if (current) colors.accent else colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val mark =
                                when (val slot = queue.jamSlots?.getOrNull(index)) {
                                    is JamSlot.Item -> names[slot.addedBy]?.let {
                                        stringResource(R.string.jam_added_by, it)
                                    }
                                    JamSlot.Wave -> waveMark
                                    else -> null
                                }
                            Row(
                                Modifier.padding(top = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                QiText(
                                    track.artistLine,
                                    mono(11.sp, 400),
                                    Modifier.weight(1f, fill = false),
                                    color = colors.dim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (mark != null) {
                                    QiText(mark, mono(11.sp, 500), color = colors.accentDark, maxLines = 1)
                                }
                            }
                        }
                        QiText(formatTime(track.durationMs), mono(12.sp, 400), color = colors.dim)
                    }
                }
                if (queue.tracks.isEmpty()) {
                    item {
                        QiText(
                            stringResource(R.string.playlist_empty),
                            mono(12.sp, 400, lineHeight = 19.sp),
                            Modifier.padding(24.dp, 16.dp),
                            color = colors.dim,
                        )
                    }
                }
                if (queue.loadingMore) {
                    item {
                        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                            QiText(
                                stringResource(R.string.playlist_loading_more),
                                ReadoutStyle,
                                color = colors.dimmer,
                            )
                        }
                    }
                }
            }
        }

        val selectedMs = queue.tracks.filterIndexed { index, _ ->
            index in queue.selected
        }.sumOf { it.durationMs }
        val totalMs = queue.tracks.sumOf { it.durationMs }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row {
                QiText(stringResource(R.string.playlist_selected) + " ", HintStyle, color = colors.dim)
                QiText("${queue.selected.size}", HintStyle, color = colors.text)
                QiText(" · ", HintStyle, color = colors.dim)
                QiText(formatTime(selectedMs), HintStyle, color = colors.text)
                QiText(" / ", HintStyle, color = colors.dim)
                QiText(formatTime(totalMs), HintStyle, color = colors.text)
            }
            QiText(stringResource(R.string.playlist_tap_hint), HintStyle, color = colors.dim)
        }

        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                stringResource(R.string.playlist_add),
                Modifier.weight(1f),
                background = colors.accent,
                color = colors.deep,
                onClick = onAdd,
            )
            ActionButton(
                stringResource(R.string.playlist_rem),
                Modifier.weight(1f),
                background = colors.surface2,
                color = if (queue.selected.isEmpty()) colors.dimmer else colors.text,
            ) {
                if (queue.jamSlots != null && jam.isActive) {
                    jam.removeFromJam(queue.selected)
                } else {
                    viewModel.queue.removeIndices(queue.selected)
                }
            }
            val allSelected = queue.tracks.isNotEmpty() && queue.selected.size == queue.tracks.size
            ActionButton(
                stringResource(if (allSelected) R.string.playlist_none else R.string.playlist_all),
                Modifier.weight(1f),
                background = colors.surface2,
            ) { viewModel.queue.selectAllOrNone() }
            ActionButton(
                stringResource(R.string.playlist_clear),
                Modifier.weight(1f),
                background = colors.surface2,
            ) {
                viewModel.queue.clear()
            }
        }
    }
}
