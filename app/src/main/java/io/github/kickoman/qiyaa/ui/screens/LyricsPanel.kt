package io.github.kickoman.qiyaa.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.lyrics.Lyrics
import io.github.kickoman.qiyaa.lyrics.LyricsFrom
import io.github.kickoman.qiyaa.lyrics.LyricsKind
import io.github.kickoman.qiyaa.lyrics.LyricsPhase
import io.github.kickoman.qiyaa.lyrics.LyricsView
import io.github.kickoman.qiyaa.track.TrackKey
import io.github.kickoman.qiyaa.track.TrackSource
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class LyricsPlace(val current: TrackKey?, val positionMs: Long, val canSeek: Boolean)

@Composable
fun LyricsPanel(
    view: LyricsView,
    place: LyricsPlace,
    canSearchThirdParty: Boolean,
    onSeek: (Long) -> Unit,
    onCover: () -> Unit,
    onSearchThirdParty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val lyrics = view.lyrics
            key(view.track?.key, lyrics) {
                when {
                    view.phase == LyricsPhase.SHOWN && lyrics?.kind == LyricsKind.SYNCED ->
                        SyncedLines(lyrics, currentLine(lyrics, view, place), place.canSeek, onSeek)

                    view.phase == LyricsPhase.SHOWN && lyrics != null -> PlainLines(lyrics, onCover)

                    else ->
                        LyricsStatus(
                            view.phase,
                            hasTrack = place.current != null,
                            canSearchThirdParty,
                            onCover,
                            onSearchThirdParty,
                        )
                }
            }
        }
        LyricsFooter(view, onCover)
    }
}

@Composable
fun LrclibConsentDialog(onAccept: () -> Unit, onCancel: () -> Unit) {
    val colors = Qi.colors
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(18.dp),
        ) {
            QiText(
                stringResource(R.string.lyrics_lrclib_title).uppercase(),
                LabelStyle,
                color = colors.accent,
            )
            QiText(
                stringResource(R.string.lyrics_lrclib_consent),
                sans(15.sp, 400, lineHeight = 21.sp),
                Modifier.padding(top = 12.dp),
                color = colors.text,
            )
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    stringResource(R.string.lyrics_lrclib_cancel).uppercase(),
                    Modifier.weight(1f),
                    background = colors.surface2,
                    onClick = onCancel,
                )
                ActionButton(
                    stringResource(R.string.lyrics_lrclib_accept).uppercase(),
                    Modifier.weight(1f),
                    background = colors.accent,
                    color = colors.deep,
                    onClick = onAccept,
                )
            }
        }
    }
}

fun currentLine(lyrics: Lyrics, view: LyricsView, place: LyricsPlace): Int {
    if (view.track?.key != place.current) return -1
    return lyrics.lines.indexOfLast { (it.ms ?: Long.MAX_VALUE) <= place.positionMs }
}

@Composable
private fun SyncedLines(lyrics: Lyrics, current: Int, canSeek: Boolean, onSeek: (Long) -> Unit) {
    val colors = Qi.colors
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var resume: Job? = null
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    resume?.cancel()
                    follow = false
                }

                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    resume?.cancel()
                    resume =
                        launch {
                            snapshotFlow { listState.isScrollInProgress }.first { !it }
                            delay(FOLLOW_AGAIN_MS)
                            follow = true
                        }
                }
            }
        }
    }
    LaunchedEffect(current, follow) {
        if (!follow) return@LaunchedEffect
        if (current < 0) {
            listState.animateScrollToItem(0)
            return@LaunchedEffect
        }
        val layout = listState.layoutInfo
        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
        val size = layout.visibleItemsInfo.firstOrNull { it.index == current }?.size ?: 0
        listState.animateScrollToItem(current, -(viewport - size) / 2)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val time = line.ms
            QiText(
                line.text.ifEmpty { PAUSE_SIGN },
                sans(15.sp, if (index == current) 600 else 400, lineHeight = 21.sp),
                Modifier
                    .fillMaxWidth()
                    .tap(enabled = canSeek && time != null) { time?.let(onSeek) }
                    .then(if (index == current) Modifier.semantics { selected = true } else Modifier)
                    .padding(vertical = 6.dp),
                color = if (index == current) colors.accent else colors.muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PlainLines(lyrics: Lyrics, onCover: () -> Unit) {
    val colors = Qi.colors
    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(onCover) { detectTapGestures { onCover() } }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (line in lyrics.lines) {
            QiText(
                line.text.ifEmpty { " " },
                sans(15.sp, 400, lineHeight = 21.sp),
                Modifier.fillMaxWidth(),
                color = colors.text,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LyricsStatus(
    phase: LyricsPhase,
    hasTrack: Boolean,
    canSearchThirdParty: Boolean,
    onCover: () -> Unit,
    onSearchThirdParty: () -> Unit,
) {
    val colors = Qi.colors
    val text =
        when (phase) {
            LyricsPhase.IDLE -> if (hasTrack) null else R.string.lyrics_nothing
            LyricsPhase.LOADING -> R.string.lyrics_loading
            LyricsPhase.MISSING, LyricsPhase.SHOWN -> R.string.lyrics_missing
            LyricsPhase.INSTRUMENTAL -> R.string.lyrics_instrumental
            LyricsPhase.FAILED -> R.string.lyrics_failed
            LyricsPhase.NO_ACCOUNT -> R.string.lyrics_no_account
        }
    Column(
        Modifier.fillMaxSize().pointerInput(onCover) { detectTapGestures { onCover() } }.padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        text?.let { QiText(stringResource(it), HintStyle, color = colors.dim, textAlign = TextAlign.Center) }
        val noText =
            phase == LyricsPhase.MISSING || phase == LyricsPhase.NO_ACCOUNT || phase == LyricsPhase.FAILED
        if (noText && canSearchThirdParty) {
            ActionButton(
                stringResource(R.string.lyrics_search_lrclib).uppercase(),
                Modifier.padding(top = 14.dp),
                background = colors.surface2,
                onClick = onSearchThirdParty,
            )
        }
    }
}

@Composable
private fun LyricsFooter(view: LyricsView, onCover: () -> Unit) {
    val colors = Qi.colors
    val from =
        when (view.from) {
            LyricsFrom.CATALOG -> if (view.track?.source ==
                TrackSource.LOCAL
            ) {
                null
            } else {
                R.string.lyrics_from_yandex
            }

            LyricsFrom.LRC_FILE -> R.string.lyrics_from_lrc

            LyricsFrom.FILE_TAGS -> R.string.lyrics_from_tags

            LyricsFrom.THIRD_PARTY -> R.string.lyrics_from_lrclib

            null -> null
        }
    val label =
        listOfNotNull(
            from?.let { stringResource(it) },
            stringResource(R.string.lyrics_better).takeIf { view.better },
        ).joinToString(" · ")
    val description = stringResource(R.string.lyrics_back_to_cover)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tap(onClick = onCover)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QiText(
            label.uppercase(),
            mono(10.sp, 500, 1.sp),
            Modifier.weight(1f),
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            PathIcon(IconPaths.COVER, 20.dp, colors.muted, strokeWidth = IconPaths.OUTLINE)
        }
    }
}

private const val FOLLOW_AGAIN_MS = 5_000L
private const val PAUSE_SIGN = "♪"
