package io.github.kickoman.qiyaa.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
fun EqScreen(vm: PlayerViewModel) {
    val c = Qi.colors
    val eq by vm.settings.eq.collectAsStateWithLifecycle()
    val auto by vm.settings.eqAuto.collectAsStateWithLifecycle()
    val preset by vm.eqPreset.collectAsStateWithLifecycle()
    val graphAlpha = if (eq.enabled) 1f else 0.35f

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.eq_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillToggle(stringResource(R.string.eq_on), eq.enabled) { vm.setEqEnabled(!eq.enabled) }
                PillToggle(stringResource(R.string.eq_auto), auto) { vm.setEqAuto(!auto) }
            }
        }

        // Preset row.
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface)
                .tap { vm.openPresets() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QiText(stringResource(R.string.eq_preset), LabelStyle, color = c.dim)
            QiText(preset, sans(15.sp, 600), Modifier.weight(1f), color = c.text, maxLines = 1)
            QiText(stringResource(R.string.eq_builtin_count), mono(11.sp, 400, 1.sp), color = c.dim)
            QiText("›", mono(14.sp, 400), color = c.dim)
        }

        // Response curve.
        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .fillMaxWidth()
                .height(88.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(c.deep)
                .border(1.dp, c.insetBorder, RoundedCornerShape(6.dp))
                .alpha(graphAlpha),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val h = size.height
                val w = size.width
                drawLine(c.border, Offset(0f, h / 2), Offset(w, h / 2), 1f)
                drawLine(c.insetBorder, Offset(0f, h / 4), Offset(w, h / 4), 1f)
                drawLine(c.insetBorder, Offset(0f, h * 3 / 4), Offset(w, h * 3 / 4), 1f)
                val path = Path()
                val n = EqSettings.BANDS
                for (i in 0 until n) {
                    val x = w * (10f + i * (344f / 9f)) / 364f
                    val y = h * (44f - (eq.bandsDb[i] / 12.0).toFloat() * 36f) / 88f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, c.acc, style = Stroke(width = 2.dp.toPx()))
            }
            QiText("+12", mono(10.sp, 400, 1.sp), Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp), color = c.dimmer)
            QiText("-12", mono(10.sp, 400, 1.sp), Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp), color = c.dimmer)
        }

        // Faders: PRE + 10 bands.
        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .weight(1f)
                .alpha(graphAlpha),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val labels = listOf(stringResource(R.string.eq_pre)) + EqSettings.BAND_LABELS
            for (i in 0..EqSettings.BANDS) {
                val db = if (i == 0) eq.preampDb else eq.bandsDb[i - 1]
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.height(20.dp).tap { if (i == 0) vm.setPreamp(0.0) else vm.setBand(i - 1, 0.0) }, contentAlignment = Alignment.Center) {
                        QiText(formatDb(db), mono(11.sp, 500), color = if (kotlin.math.abs(db) < 0.05) c.dim else c.acc)
                    }
                    VerticalFader(
                        value = db,
                        onChange = { v -> if (i == 0) vm.setPreamp(v) else vm.setBand(i - 1, v) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        thumbColor = if (i == 0) c.muted else c.acc,
                        enabled = eq.enabled,
                    )
                    QiText(labels[i], mono(9.sp, 500, 0.5.sp), color = c.dim)
                }
            }
        }

        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            QiText(stringResource(R.string.eq_hint_left), mono(10.sp, 400, 1.5.sp), color = c.dimmer)
            QiText(stringResource(R.string.eq_hint_right), mono(10.sp, 400, 1.5.sp), color = c.dimmer)
        }
    }
}

/** Bottom sheet with the 17 built-in presets; drawn over the whole app. */
@Composable
fun PresetsSheet(vm: PlayerViewModel) {
    val c = Qi.colors
    val current by vm.eqPreset.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(c.deep.copy(alpha = 0.7f)).tap { vm.closePresets() })
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(maxHeight * 0.78f)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(c.surface),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.handle))
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QiText(stringResource(R.string.eq_presets_title), LabelStyle, color = c.dim)
                QiText(stringResource(R.string.eq_reset_flat), LabelStyle, Modifier.tap { vm.resetFlat() }, color = c.acc)
            }
            LazyColumn(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                items(EqPresets.builtin, key = { it.name }) { p ->
                    val active = p.name == current
                    val color = if (active) c.acc else c.text
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) c.accBg else c.surface)
                            .tap { vm.applyPreset(p.name) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(Modifier.width(40.dp).height(18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (v in p.raw) {
                                val pct = ((v - 1) / 63f).coerceIn(0.02f, 1f)
                                Box(Modifier.weight(1f).fillMaxHeight(pct).background(color.copy(alpha = 0.7f)))
                            }
                        }
                        QiText(p.name, sans(14.sp, 500), Modifier.weight(1f), color = color, maxLines = 1)
                        QiText("●", mono(11.sp, 500), Modifier.alpha(if (active) 1f else 0f), color = color)
                    }
                }
            }
        }
    }
}
