package io.github.kickoman.qiyaa.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.data.AppLanguage
import io.github.kickoman.qiyaa.jam.ParticipantKind
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.playback.JamHostState
import io.github.kickoman.qiyaa.ui.AppViewModel
import io.github.kickoman.qiyaa.ui.LibrarySection
import io.github.kickoman.qiyaa.ui.ListState
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.IconWideButton
import io.github.kickoman.qiyaa.ui.components.LanguageSwitch
import io.github.kickoman.qiyaa.ui.components.LikeButton
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans

@Composable
fun LibraryScreen(
    viewModel: AppViewModel,
    player: PlayerViewModel,
    jam: JamHostState,
    onJam: () -> Unit,
    onJamSearch: ((String) -> Unit)?,
) {
    val colors = Qi.colors
    val context = LocalContext.current
    val account by viewModel.account.collectAsStateWithLifecycle()
    val liked by viewModel.likedIds.collectAsStateWithLifecycle()
    val libraryUi by viewModel.libraryUi.collectAsStateWithLifecycle()
    val forYou by viewModel.forYou.state.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.state.collectAsStateWithLifecycle()
    val artists by viewModel.artists.state.collectAsStateWithLifecycle()
    val albums by viewModel.albums.state.collectAsStateWithLifecycle()
    val playerUi by player.ui.collectAsStateWithLifecycle()
    val language by viewModel.settings.language.collectAsStateWithLifecycle()
    val track = playerUi.current

    LaunchedEffect(account.uid) { if (account.isValid) viewModel.loadLibraryLists() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.library_title)) {
            val who = account.login.ifEmpty { account.displayName }
            QiText(
                (if (who.isEmpty()) "" else "$who · ") + stringResource(R.string.library_sign_out),
                mono(11.sp, 500, 1.sp),
                Modifier.tap { viewModel.signOut() },
                color = colors.muted,
                maxLines = 1,
            )
        }

        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.deep)
                .border(1.dp, colors.insetBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(14.dp).border(2.dp, colors.dim, CircleShape))
            val style = mono(13.sp, 400).copy(color = colors.text)
            BasicTextField(
                value = libraryUi.searchText,
                onValueChange = viewModel::setSearchText,
                textStyle = style,
                singleLine = true,
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        // HOST-21: during a jam, a search finds tracks for the jam instead of a new queue.
                        if (onJamSearch !=
                            null
                        ) {
                            onJamSearch(libraryUi.searchText)
                        } else {
                            viewModel.submitSearch()
                        }
                    },
                ),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (libraryUi.searchText.isEmpty()) {
                        QiText(
                            stringResource(R.string.library_search_hint),
                            style,
                            color = colors.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
        }

        Row(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 14.dp)
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentBackground)
                .tap { viewModel.playMyWave() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                PathIcon(IconPaths.PLAY, 20.dp, colors.deep)
            }
            Column(Modifier.weight(1f)) {
                QiText(stringResource(R.string.library_my_wave), sans(16.sp, 600), color = colors.accent)
                QiText(
                    stringResource(R.string.library_my_wave_sub),
                    HintStyle,
                    Modifier.padding(top = 3.dp),
                    color = colors.accent.copy(alpha = 0.85f),
                )
            }
            QiText("∞", mono(20.sp, 400), color = colors.accent)
        }

        val loadingText = stringResource(R.string.library_loading)
        val failedText = stringResource(R.string.library_row_error)
        fun countOrStatus(state: ListState<*>): String = when (state) {
            ListState.Idle -> "…"
            ListState.Loading -> loadingText
            is ListState.Loaded -> state.items.size.toString()
            is ListState.Failed -> failedText
        }
        Column(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface),
        ) {
            val jamMeta =
                when (jam.phase) {
                    JamHostPhase.NONE -> stringResource(R.string.jam_library_off)
                    JamHostPhase.CREATING -> "…"
                    JamHostPhase.ACTIVE -> stringResource(
                        R.string.jam_library_on,
                        jam.room?.participants?.count { it.kind != ParticipantKind.HOST } ?: 0,
                    )
                }
            SourceRow(stringResource(R.string.jam_library_row), jamMeta, onClick = onJam)
            SourceRow(stringResource(R.string.library_liked), "${liked.size}") { viewModel.playLiked() }
            SourceRow(stringResource(R.string.library_for_you), countOrStatus(forYou)) {
                viewModel.openSection(LibrarySection.FOR_YOU)
            }
            SourceRow(stringResource(R.string.library_wheel), stringResource(R.string.library_wheel_meta)) {
                viewModel.openSection(LibrarySection.WHEEL)
            }
            SourceRow(stringResource(R.string.library_playlists), countOrStatus(playlists)) {
                viewModel.openSection(LibrarySection.PLAYLISTS)
            }
            SourceRow(
                stringResource(R.string.library_artists),
                countOrStatus(artists) + " · " + stringResource(R.string.library_top_tracks),
            ) { viewModel.openSection(LibrarySection.ARTISTS) }
            SourceRow(stringResource(R.string.library_albums), countOrStatus(albums)) {
                viewModel.openSection(LibrarySection.ALBUMS)
            }
            SourceRow(
                stringResource(R.string.library_stations),
                stringResource(R.string.library_by_type),
                last = true,
            ) {
                viewModel.openSection(LibrarySection.STATIONS)
            }
        }

        val currentTitle = track?.title?.uppercase() ?: stringResource(R.string.library_none)
        QiText(
            stringResource(R.string.library_current_track) + " · " + currentTitle,
            LabelStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp),
            color = colors.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val isLiked = track != null && track.id in liked
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LikeButton(isLiked, Modifier.weight(1f), height = 48.dp, onClick = player::toggleLikeCurrent)
            IconWideButton(
                stringResource(R.string.library_dislike),
                Modifier.weight(1f),
                height = 48.dp,
                onClick = player::dislikeCurrent,
            ) {
                PathIcon(IconPaths.THUMB_DOWN, 22.dp, colors.muted, strokeWidth = IconPaths.OUTLINE)
            }
            IconWideButton(
                stringResource(
                    R.string.library_open,
                ),
                Modifier.weight(1f),
                height = 48.dp,
                onClick = {
                    track?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.webUrl))) }
                },
            ) {
                PathIcon(IconPaths.OPEN_OUTSIDE, 22.dp, colors.muted, strokeWidth = IconPaths.OUTLINE)
            }
        }

        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // With the English word, so that a language one cannot read can still be undone.
            val title = stringResource(R.string.language_title)
            QiText(
                if (language == AppLanguage.ENGLISH) title else "$title · LANGUAGE",
                LabelStyle,
                color = colors.dim,
            )
            LanguageSwitch(language, viewModel::setLanguage)
        }
    }
}

@Composable
private fun SourceRow(name: String, meta: String, last: Boolean = false, onClick: () -> Unit) {
    val colors = Qi.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .tap(onClick = onClick)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(colors.border))
            QiText(name, sans(15.sp, 500), Modifier.weight(1f), color = colors.text)
            QiText(meta, HintStyle, color = colors.dim)
            QiText("›", mono(14.sp, 400), color = colors.dim)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.background))
    }
}
