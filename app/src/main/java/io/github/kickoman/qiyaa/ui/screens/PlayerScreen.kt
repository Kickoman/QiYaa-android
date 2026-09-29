package io.github.kickoman.qiyaa.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.balanceLabel
import io.github.kickoman.qiyaa.ui.components.ActionButton
import io.github.kickoman.qiyaa.ui.components.HorizontalSlider
import io.github.kickoman.qiyaa.ui.components.IconActionButton
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.RoundButton
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.formatReadout
import io.github.kickoman.qiyaa.ui.formatTime
import io.github.kickoman.qiyaa.ui.theme.CaptionStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.visualizer.VisualizerStrip
import io.github.kickoman.qiyaa.yandex.Track
import kotlin.math.roundToInt

private val QueueLabelStyle = mono(11.sp, 500, 1.sp)
private val TransportLabelStyle = mono(10.sp, 500, 1.sp)

fun marqueeText(index: Int, track: Track): String =
    "${index + 1}. ${track.artistLine} — ${track.title} (${formatTime(track.durationMs)})"

@Composable
fun queueTitle(title: String, isWave: Boolean): String =
    title.ifEmpty { stringResource(R.string.player_no_queue) }.uppercase() + if (isWave) " ∞" else ""

@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    val colors = Qi.colors
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val queue by viewModel.queue.state.collectAsStateWithLifecycle()
    val liked by viewModel.library.likedIds.collectAsStateWithLifecycle()
    val visualizerMode by viewModel.settings.visualizerMode.collectAsStateWithLifecycle()
    val remaining by viewModel.settings.timeRemaining.collectAsStateWithLifecycle()
    val volume by viewModel.settings.volume.collectAsStateWithLifecycle()
    val balance by viewModel.settings.balance.collectAsStateWithLifecycle()
    val bitrate by viewModel.audioBus.bitrateKbps.collectAsStateWithLifecycle()
    val sampleRate by viewModel.audioBus.sampleRate.collectAsStateWithLifecycle()
    val channels by viewModel.audioBus.channels.collectAsStateWithLifecycle()

    val track = ui.current
    val positionLabel = if (track !=
        null
    ) {
        "${ui.index + 1} / ${ui.count}"
    } else {
        stringResource(R.string.player_no_position)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            QiText(
                stringResource(R.string.brand),
                CaptionStyle,
                Modifier.tap {
                    viewModel.cycleTheme()
                },
                color = colors.muted,
            )
            QiText(
                "$positionLabel · ${queueTitle(queue.title, queue.isWave)}",
                QueueLabelStyle,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(10.dp)),
        ) {
            StripedPlaceholder(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                QiText(stringResource(R.string.player_cover_placeholder), ReadoutStyle, color = colors.dim)
            }
            if (track?.coverUrl != null) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.deep.copy(alpha = 0f), colors.deep.copy(alpha = 0.95f)),
                        ),
                    ),
            )
            VisualizerStrip(
                mode = visualizerMode,
                playing = ui.isPlaying,
                audioBus = viewModel.audioBus,
                offLabel = stringResource(R.string.player_vis_off),
                modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                    .fillMaxWidth()
                    .height(36.dp),
                onTap = viewModel::cycleVisualizer,
            )
        }

        val marquee =
            when {
                track != null -> marqueeText(ui.index, track)
                ui.count > 0 -> stringResource(R.string.player_stopped)
                else -> stringResource(R.string.player_nothing)
            }
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.deep)
                .border(1.dp, colors.insetBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QiText(
                marquee.uppercase(),
                mono(13.sp, 500, 1.sp),
                Modifier.weight(1f),
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            QiText(
                formatReadout(bitrate, sampleRate, channels),
                TransportLabelStyle,
                color = colors.dim,
                maxLines = 1,
            )
        }

        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            val fraction = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f
            HorizontalSlider(
                value = fraction,
                onChange = viewModel::previewPosition,
                onChangeFinished = viewModel::seekToFraction,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val timeMain =
                    when {
                        track == null -> "00:00"
                        remaining -> "-" + formatTime(ui.durationMs - ui.positionMs)
                        else -> formatTime(ui.positionMs)
                    }
                QiText(
                    timeMain,
                    mono(
                        28.sp,
                        500,
                    ).copy(color = colors.accent, shadow = Shadow(colors.accentBackground, blurRadius = 12f)),
                    Modifier.tap { viewModel.toggleRemaining() },
                )
                QiText(
                    if (track !=
                        null
                    ) {
                        formatTime(ui.durationMs)
                    } else {
                        "--:--"
                    },
                    mono(12.sp, 400),
                    color = colors.dim,
                )
            }
        }

        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundButton(
                52.dp,
                if (ui.shuffle && !queue.isWave) colors.accentBackground else colors.surface,
                onClick = viewModel::toggleShuffle,
            ) {
                QiText(
                    stringResource(R.string.player_shuffle),
                    TransportLabelStyle,
                    color =
                    when {
                        queue.isWave -> colors.dimmer
                        ui.shuffle -> colors.accent
                        else -> colors.muted
                    },
                )
            }
            RoundButton(56.dp, colors.surface2, onClick = viewModel::previous) {
                PathIcon(IconPaths.PREVIOUS, 22.dp, colors.text)
            }
            Box(Modifier.size(104.dp).glow(colors.accentBackground), contentAlignment = Alignment.Center) {
                RoundButton(76.dp, colors.accent, onClick = viewModel::togglePlay) {
                    PathIcon(
                        if (ui.isPlaying ||
                            ui.isBuffering
                        ) {
                            IconPaths.PAUSE
                        } else {
                            IconPaths.PLAY
                        },
                        30.dp,
                        colors.deep,
                    )
                }
            }
            RoundButton(56.dp, colors.surface2, onClick = viewModel::next) {
                PathIcon(IconPaths.NEXT, 22.dp, colors.text)
            }
            RoundButton(
                52.dp,
                if (ui.repeat && !queue.isWave) colors.accentBackground else colors.surface,
                onClick = viewModel::toggleRepeat,
            ) {
                QiText(
                    stringResource(R.string.player_repeat),
                    TransportLabelStyle,
                    color =
                    when {
                        queue.isWave -> colors.dimmer
                        ui.repeat -> colors.accent
                        else -> colors.muted
                    },
                )
            }
        }

        val isLiked = track != null && track.id in liked
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                "♥ " + stringResource(if (isLiked) R.string.player_liked else R.string.player_like),
                Modifier.weight(1f),
                color = if (isLiked) colors.accent else colors.muted,
            ) { viewModel.toggleLikeCurrent() }
            ActionButton(
                "✕ " + stringResource(R.string.player_dislike_skip),
                Modifier.weight(1f),
                color = colors.muted,
            ) {
                viewModel.dislikeCurrent()
            }
            IconActionButton(onClick = viewModel::stop) { PathIcon(IconPaths.STOP, 20.dp, colors.muted) }
        }

        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(2f)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    QiText(stringResource(R.string.player_volume), ReadoutStyle, color = colors.dim)
                    QiText("$volume", ReadoutStyle, color = colors.text)
                }
                HorizontalSlider(
                    value = volume / AudioBus.MAX_VOLUME.toFloat(),
                    onChange = { viewModel.setVolume((it * AudioBus.MAX_VOLUME).roundToInt()) },
                    height = 20.dp,
                    accent = colors.accentDark,
                )
            }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    QiText(stringResource(R.string.player_balance), ReadoutStyle, color = colors.dim)
                    QiText(balanceLabel(balance), ReadoutStyle, color = colors.text)
                }
                HorizontalSlider(
                    value = (balance + AudioBus.MAX_BALANCE) / (2f * AudioBus.MAX_BALANCE),
                    onChange = {
                        viewModel.setBalance(
                            (it * 2 * AudioBus.MAX_BALANCE).roundToInt() - AudioBus.MAX_BALANCE,
                        )
                    },
                    height = 20.dp,
                    accent = colors.accentDark,
                )
            }
        }
    }
}

private fun Modifier.glow(color: Color): Modifier =
    this.then(Modifier.background(Brush.radialGradient(listOf(color, color, color.copy(alpha = 0f)))))

@Composable
private fun StripedPlaceholder(modifier: Modifier) {
    val colors = Qi.colors
    Canvas(modifier) {
        val step = 10.dp.toPx()
        val width = size.width
        val height = size.height
        var offset = -height
        while (offset < width + height) {
            drawLine(colors.surface2, Offset(offset, 0f), Offset(offset + height, height), strokeWidth = step)
            offset += step * 2
        }
    }
}
