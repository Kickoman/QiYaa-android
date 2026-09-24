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
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.fmtTime
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
fun PlaylistScreen(vm: PlayerViewModel, onAdd: () -> Unit) {
    val c = Qi.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val q by vm.queue.state.collectAsStateWithLifecycle()
    val queueTitle = (q.title.ifEmpty { stringResource(R.string.player_no_queue) }).uppercase() + if (q.isWave) " ∞" else ""

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.playlist_title)) {
            QiText(stringResource(R.string.playlist_loaded, queueTitle, q.tracks.size), mono(11.sp, 500, 1.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(c.deep)
                .border(1.dp, c.insetBorder, RoundedCornerShape(6.dp)),
        ) {
            LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                itemsIndexed(q.tracks, key = { i, t -> "$i:${t.id}" }) { i, t ->
                    val selected = i in q.selected
                    val current = i == ui.index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(if (selected) c.surface else c.deep)
                            .tap { vm.playIndex(i) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(32.dp).height(52.dp).tap { vm.queue.toggleSelected(i) }, contentAlignment = Alignment.CenterEnd) {
                            QiText("${i + 1}", mono(12.sp, 400), color = if (selected) c.text else c.dim)
                        }
                        Column(Modifier.weight(1f)) {
                            QiText(t.title, sans(14.sp, 500), color = if (current) c.acc else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            QiText(t.artistLine, mono(11.sp, 400), Modifier.padding(top = 3.dp), color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        QiText(fmtTime(t.durationMs), mono(12.sp, 400), color = c.dim)
                    }
                }
                if (q.tracks.isEmpty()) {
                    item {
                        QiText(stringResource(R.string.playlist_empty), mono(12.sp, 400, lineHeight = 19.sp), Modifier.padding(24.dp, 16.dp), color = c.dim)
                    }
                }
                if (q.isWave) {
                    item {
                        Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                            QiText(stringResource(R.string.playlist_loading_more), mono(11.sp, 400, 1.5.sp), color = c.dimmer)
                        }
                    }
                }
            }
        }

        val selTime = q.tracks.filterIndexed { i, _ -> i in q.selected }.sumOf { it.durationMs }
        val total = q.tracks.sumOf { it.durationMs }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val s = mono(11.sp, 400, 1.sp)
            Row {
                QiText(stringResource(R.string.playlist_selected) + " ", s, color = c.dim)
                QiText("${q.selected.size}", s, color = c.text)
                QiText(" · ", s, color = c.dim)
                QiText(fmtTime(selTime), s, color = c.text)
                QiText(" / ", s, color = c.dim)
                QiText(fmtTime(total), s, color = c.text)
            }
            QiText(stringResource(R.string.playlist_tap_hint), s, color = c.dim)
        }

        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.playlist_add), Modifier.weight(1f), bg = c.acc, color = c.deep, onClick = onAdd)
            ActionButton(stringResource(R.string.playlist_rem), Modifier.weight(1f), bg = c.surface2, color = if (q.selected.isEmpty()) c.dimmer else c.text) {
                vm.queue.removeIndices(q.selected)
            }
            val allSelected = q.tracks.isNotEmpty() && q.selected.size == q.tracks.size
            ActionButton(stringResource(if (allSelected) R.string.playlist_none else R.string.playlist_all), Modifier.weight(1f), bg = c.surface2) {
                vm.queue.selectAllOrNone()
            }
            ActionButton(stringResource(R.string.playlist_clear), Modifier.weight(1f), bg = c.surface2) { vm.queue.clear() }
        }
    }
}
