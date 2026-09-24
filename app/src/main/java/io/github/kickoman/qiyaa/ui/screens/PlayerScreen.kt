package io.github.kickoman.qiyaa.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.balanceLabel
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.HSlider
import io.github.kickoman.qiyaa.ui.components.IconActionButton
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ReadoutStyle
import io.github.kickoman.qiyaa.ui.components.RoundButton
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.fmtTime
import io.github.kickoman.qiyaa.ui.formatReadout
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.vis.VisualizerStrip

@Composable
fun PlayerScreen(vm: PlayerViewModel) {
    val c = Qi.colors
    val ui by vm.ui.collectAsStateWithLifecycle()
    val queue by vm.queue.state.collectAsStateWithLifecycle()
    val liked by vm.library.likedIds.collectAsStateWithLifecycle()
    val visMode by vm.settings.visMode.collectAsStateWithLifecycle()
    val remaining by vm.settings.timeRemaining.collectAsStateWithLifecycle()
    val volume by vm.settings.volume.collectAsStateWithLifecycle()
    val balance by vm.settings.balance.collectAsStateWithLifecycle()
    val bitrate by vm.audioBus.bitrateKbps.collectAsStateWithLifecycle()
    val sampleRate by vm.audioBus.sampleRate.collectAsStateWithLifecycle()
    val channels by vm.audioBus.channels.collectAsStateWithLifecycle()

    val track = ui.current
    val queueTitle = (queue.title.ifEmpty { stringResource(R.string.player_no_queue) }).uppercase() + if (queue.isWave) " ∞" else ""
    val posLabel = if (track != null) "${ui.index + 1} / ${ui.count}" else stringResource(R.string.player_no_position)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Header drawn by hand so the brand tap (theme cycling) is a separate target.
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            QiText(stringResource(R.string.brand), CaptionStyle, Modifier.tap { vm.cycleTheme() }, color = c.muted)
            QiText("$posLabel · $queueTitle", mono(11.sp, 500, 1.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Cover + visualizer.
        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(c.surface)
                .border(1.dp, c.border, RoundedCornerShape(10.dp)),
        ) {
            StripedPlaceholder(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                QiText(stringResource(R.string.player_cover_placeholder), mono(11.sp, 400, 1.5.sp), color = c.dim)
            }
            if (track?.coverUrl != null) {
                AsyncImage(model = track.coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Brush.verticalGradient(listOf(c.deep.copy(alpha = 0f), c.deep.copy(alpha = 0.95f)))),
            )
            VisualizerStrip(
                mode = visMode,
                playing = ui.isPlaying,
                bus = vm.audioBus,
                offLabel = stringResource(R.string.player_vis_off),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                    .fillMaxWidth()
                    .height(36.dp),
                onTap = vm::cycleVis,
            )
        }

        // Marquee readout.
        val marquee = when {
            track != null -> "${ui.index + 1}. ${track.artistLine} — ${track.title} (${fmtTime(track.durationMs)})"
            ui.count > 0 -> stringResource(R.string.player_stopped)
            else -> stringResource(R.string.player_nothing)
        }
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(c.deep)
                .border(1.dp, c.insetBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QiText(marquee.uppercase(), mono(13.sp, 500, 1.sp), Modifier.weight(1f), color = c.acc, maxLines = 1, overflow = TextOverflow.Ellipsis)
            QiText(formatReadout(bitrate, sampleRate, channels), mono(10.sp, 500, 1.sp), color = c.dim, maxLines = 1)
        }

        // Seek + time.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            val frac = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f
            HSlider(value = frac, onChange = vm::previewPosition, onChangeFinished = vm::seekToFraction)
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                val timeMain = when {
                    track == null -> "00:00"
                    remaining -> "-" + fmtTime(ui.durationMs - ui.positionMs)
                    else -> fmtTime(ui.positionMs)
                }
                QiText(
                    timeMain,
                    mono(28.sp, 500).copy(color = c.acc, shadow = Shadow(c.accBg, blurRadius = 12f)),
                    Modifier.tap { vm.toggleRemaining() },
                )
                QiText(if (track != null) fmtTime(ui.durationMs) else "--:--", mono(12.sp, 400), color = c.dim)
            }
        }

        // Transport.
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundButton(52.dp, if (ui.shuffle) c.accBg else c.surface, onClick = vm::toggleShuffle) {
                QiText(stringResource(R.string.player_shuffle), mono(10.sp, 500, 1.sp), color = if (ui.shuffle) c.acc else c.muted)
            }
            RoundButton(56.dp, c.surface2, onClick = vm::prev) { PathIcon(IconPaths.PREV, 22.dp, c.text) }
            // box-shadow 0 0 28px accbg: a radial halo behind the 76dp accent circle.
            Box(Modifier.size(104.dp).glow(c.accBg), contentAlignment = Alignment.Center) {
                RoundButton(76.dp, c.acc, onClick = vm::togglePlay) {
                    PathIcon(if (ui.isPlaying || ui.isBuffering) IconPaths.PAUSE else IconPaths.PLAY, 30.dp, c.deep)
                }
            }
            RoundButton(56.dp, c.surface2, onClick = vm::next) { PathIcon(IconPaths.NEXT, 22.dp, c.text) }
            RoundButton(52.dp, if (ui.repeat) c.accBg else c.surface, onClick = vm::toggleRepeat) {
                QiText(stringResource(R.string.player_repeat), mono(10.sp, 500, 1.sp), color = if (ui.repeat) c.acc else c.muted)
            }
        }

        // Like / dislike / stop.
        val isLiked = track != null && track.id in liked
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(
                "♥ " + stringResource(if (isLiked) R.string.player_liked else R.string.player_like),
                Modifier.weight(1f),
                color = if (isLiked) c.acc else c.muted,
            ) { vm.toggleLikeCurrent() }
            ActionButton("✕ " + stringResource(R.string.player_dislike_skip), Modifier.weight(1f), color = c.muted) { vm.dislikeCurrent() }
            IconActionButton(onClick = vm::stop) { PathIcon(IconPaths.STOP, 20.dp, c.muted) }
        }

        // Volume / balance.
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(2f)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    QiText(stringResource(R.string.player_volume), ReadoutStyle, color = c.dim)
                    QiText("$volume", ReadoutStyle, color = c.text)
                }
                HSlider(value = volume / 100f, onChange = { vm.setVolume(Math.round(it * 100)) }, height = 20.dp, accent = c.acc2)
            }
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    QiText(stringResource(R.string.player_balance), ReadoutStyle, color = c.dim)
                    QiText(balanceLabel(balance), ReadoutStyle, color = c.text)
                }
                HSlider(value = (balance + 100) / 200f, onChange = { vm.setBalance(Math.round(it * 200) - 100) }, height = 20.dp, accent = c.acc2)
            }
        }
    }
}

/** Soft LED glow behind the play button (box-shadow 0 0 28px accbg). */
private fun Modifier.glow(color: androidx.compose.ui.graphics.Color): Modifier = this.then(
    Modifier.background(Brush.radialGradient(listOf(color, color, color.copy(alpha = 0f)))),
)

/** The mock's `repeating-linear-gradient(135deg, #1c1e23 0 10px, #22242a 10px 20px)`. */
@Composable
private fun StripedPlaceholder(modifier: Modifier) {
    val c = Qi.colors
    Canvas(modifier) {
        val step = 10.dp.toPx()
        val w = size.width
        val h = size.height
        var d = -h
        while (d < w + h) {
            drawLine(c.surface2, Offset(d, 0f), Offset(d + h, h), strokeWidth = step)
            d += step * 2
        }
    }
}
