package io.github.kickoman.qiyaa.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.audio.EqPresets
import io.github.kickoman.qiyaa.audio.EqSettings
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.components.PillToggle
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.VerticalFader
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.formatDb
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.ReadoutStyle
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import kotlin.math.abs

private val BAND_LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")
private const val DISABLED_ALPHA = 0.35f
private const val ZERO_DB_EPSILON = 0.05
private const val MIN_PRESET_BAR = 0.02f

@Composable
fun EqScreen(viewModel: PlayerViewModel) {
    val colors = Qi.colors
    val eq by viewModel.settings.eq.collectAsStateWithLifecycle()
    val auto by viewModel.settings.eqAuto.collectAsStateWithLifecycle()
    val preset by viewModel.eqPreset.collectAsStateWithLifecycle()
    val graphAlpha = if (eq.enabled) 1f else DISABLED_ALPHA

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.eq_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillToggle(stringResource(R.string.eq_on), eq.enabled) { viewModel.setEqEnabled(!eq.enabled) }
                PillToggle(stringResource(R.string.eq_auto), auto) { viewModel.setEqAuto(!auto) }
            }
        }

        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .tap { viewModel.openPresets() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QiText(stringResource(R.string.eq_preset), LabelStyle, color = colors.dim)
            QiText(preset, sans(15.sp, 600), Modifier.weight(1f), color = colors.text, maxLines = 1)
            QiText(stringResource(R.string.eq_builtin_count), mono(11.sp, 400, 1.sp), color = colors.dim)
            QiText("›", mono(14.sp, 400), color = colors.dim)
        }

        ResponseCurve(eq, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).alpha(graphAlpha))

        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .weight(1f)
                .alpha(graphAlpha),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val labels = listOf(stringResource(R.string.eq_pre)) + BAND_LABELS
            for (column in 0..EqSettings.BAND_COUNT) {
                val isPreamp = column == 0
                val band = column - 1
                val db = if (isPreamp) eq.preampDb else eq.bandsDb[band]
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.height(20.dp).tap {
                            if (isPreamp) viewModel.setPreamp(0.0) else viewModel.setBand(band, 0.0)
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        QiText(
                            formatDb(db),
                            mono(11.sp, 500),
                            color = if (abs(db) <
                                ZERO_DB_EPSILON
                            ) {
                                colors.dim
                            } else {
                                colors.accent
                            },
                        )
                    }
                    VerticalFader(
                        value = db,
                        onChange = { if (isPreamp) viewModel.setPreamp(it) else viewModel.setBand(band, it) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        thumbColor = if (isPreamp) colors.muted else colors.accent,
                        enabled = eq.enabled,
                    )
                    QiText(labels[column], mono(9.sp, 500, 0.5.sp), color = colors.dim)
                }
            }
        }

        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            QiText(stringResource(R.string.eq_hint_left), ReadoutStyle, color = colors.dimmer)
            QiText(stringResource(R.string.eq_hint_right), ReadoutStyle, color = colors.dimmer)
        }
    }
}

// The curve joins the band values at the mock's x positions (10 + i * 344 / 9 of 364) and y = 44 ∓ 36 of 88.
@Composable
private fun ResponseCurve(eq: EqSettings, modifier: Modifier) {
    val colors = Qi.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.deep)
            .border(1.dp, colors.insetBorder, RoundedCornerShape(6.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val height = size.height
            val width = size.width
            drawLine(colors.border, Offset(0f, height / 2), Offset(width, height / 2), 1f)
            drawLine(colors.insetBorder, Offset(0f, height / 4), Offset(width, height / 4), 1f)
            drawLine(colors.insetBorder, Offset(0f, height * 3 / 4), Offset(width, height * 3 / 4), 1f)
            val path = Path()
            for (band in 0 until EqSettings.BAND_COUNT) {
                val x = width * (10f + band * (344f / 9f)) / 364f
                val y = height * (44f - (eq.bandsDb[band] / EqSettings.MAX_DB).toFloat() * 36f) / 88f
                if (band == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colors.accent, style = Stroke(width = 2.dp.toPx()))
        }
        QiText(
            "+12",
            mono(10.sp, 400, 1.sp),
            Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp),
            color = colors.dimmer,
        )
        QiText(
            "-12",
            mono(10.sp, 400, 1.sp),
            Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp),
            color = colors.dimmer,
        )
    }
}

@Composable
fun PresetsSheet(viewModel: PlayerViewModel) {
    val colors = Qi.colors
    val current by viewModel.eqPreset.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(colors.deep.copy(alpha = 0.7f)).tap {
                viewModel.closePresets()
            },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * 0.78f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(colors.surface),
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
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QiText(stringResource(R.string.eq_presets_title), LabelStyle, color = colors.dim)
                QiText(
                    stringResource(R.string.eq_reset_flat),
                    LabelStyle,
                    Modifier.tap {
                        viewModel.resetFlat()
                    },
                    color = colors.accent,
                )
            }
            LazyColumn(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                items(EqPresets.builtin, key = { it.name }) { preset ->
                    val active = preset.name == current
                    val color = if (active) colors.accent else colors.text
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.accentBackground else colors.surface)
                            .tap { viewModel.applyPreset(preset.name) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            Modifier.width(40.dp).height(18.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            for (level in preset.eqfLevels) {
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight(eqfFraction(level))
                                        .background(color.copy(alpha = 0.7f)),
                                )
                            }
                        }
                        QiText(
                            preset.name,
                            sans(14.sp, 500),
                            Modifier.weight(1f),
                            color = color,
                            maxLines = 1,
                        )
                        QiText("●", mono(11.sp, 500), Modifier.alpha(if (active) 1f else 0f), color = color)
                    }
                }
            }
        }
    }
}

private fun eqfFraction(level: Int): Float {
    val span = (EqPresets.EQF_MAX - EqPresets.EQF_MIN).toFloat()
    return ((level - EqPresets.EQF_MIN) / span).coerceIn(MIN_PRESET_BAR, 1f)
}
