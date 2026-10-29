package io.github.kickoman.qiyaa.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.data.AppLanguage
import io.github.kickoman.qiyaa.data.AudioQuality
import io.github.kickoman.qiyaa.data.ShuffleChoice
import io.github.kickoman.qiyaa.jam.ParticipantKind
import io.github.kickoman.qiyaa.local.LocalPick
import io.github.kickoman.qiyaa.local.LocalPicks
import io.github.kickoman.qiyaa.playback.JamHostPhase
import io.github.kickoman.qiyaa.playback.JamHostState
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.ui.AppViewModel
import io.github.kickoman.qiyaa.ui.LibrarySection
import io.github.kickoman.qiyaa.ui.ListState
import io.github.kickoman.qiyaa.ui.LocalAccess
import io.github.kickoman.qiyaa.ui.PlayerViewModel
import io.github.kickoman.qiyaa.ui.SearchSource
import io.github.kickoman.qiyaa.ui.SpotifyViewModel
import io.github.kickoman.qiyaa.ui.components.IconPaths
import io.github.kickoman.qiyaa.ui.components.IconWideButton
import io.github.kickoman.qiyaa.ui.components.LanguageSwitch
import io.github.kickoman.qiyaa.ui.components.LikeButton
import io.github.kickoman.qiyaa.ui.components.OnOffPills
import io.github.kickoman.qiyaa.ui.components.PathIcon
import io.github.kickoman.qiyaa.ui.components.PillToggle
import io.github.kickoman.qiyaa.ui.components.QiText
import io.github.kickoman.qiyaa.ui.components.ScreenHeader
import io.github.kickoman.qiyaa.ui.components.tap
import io.github.kickoman.qiyaa.ui.theme.HintStyle
import io.github.kickoman.qiyaa.ui.theme.LabelStyle
import io.github.kickoman.qiyaa.ui.theme.Qi
import io.github.kickoman.qiyaa.ui.theme.mono
import io.github.kickoman.qiyaa.ui.theme.sans
import io.github.kickoman.qiyaa.ui.webUrl
import io.github.kickoman.qiyaa.yandex.signedIn

@Composable
fun LibraryScreen(
    viewModel: AppViewModel,
    player: PlayerViewModel,
    spotify: SpotifyViewModel,
    jam: JamHostState,
    guestOf: String?,
    onJam: () -> Unit,
    onSearch: (String, SearchSource) -> Unit,
) {
    val colors = Qi.colors
    val account by viewModel.account.collectAsStateWithLifecycle()
    val liked by viewModel.likedIds.collectAsStateWithLifecycle()
    val playerUi by player.ui.collectAsStateWithLifecycle()
    val language by viewModel.settings.language.collectAsStateWithLifecycle()
    val quality by viewModel.settings.quality.collectAsStateWithLifecycle()
    val qualityMetered by viewModel.settings.qualityMetered.collectAsStateWithLifecycle()
    val continueWithVibe by viewModel.settings.continueWithVibe.collectAsStateWithLifecycle()
    val shuffleAlgorithm by viewModel.settings.shuffleAlgorithm.collectAsStateWithLifecycle()
    val telemetryEnabled by viewModel.settings.telemetryEnabled.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val signedIn = sessionState.signedIn
    val track = playerUi.current

    LaunchedEffect(account.uid) { if (account.isValid) viewModel.loadLibraryLists() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.library_title)) {
            if (!signedIn) LanguageSwitch(language, viewModel::setLanguage)
        }

        val jamMeta =
            when {
                guestOf != null -> stringResource(R.string.jam_library_guest, guestOf)

                jam.phase == JamHostPhase.NONE -> stringResource(R.string.jam_library_off)

                jam.phase == JamHostPhase.CREATING -> "…"

                else -> stringResource(
                    R.string.jam_library_on,
                    jam.room?.participants?.count { it.kind != ParticipantKind.HOST } ?: 0,
                )
            }
        Column(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface),
        ) {
            SourceRow(stringResource(R.string.jam_library_row), jamMeta, last = true, onClick = onJam)
        }

        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QiText(
                stringResource(R.string.library_yandex),
                LabelStyle,
                Modifier.weight(1f),
                color = colors.dim,
            )
            val who = account.login.ifEmpty { account.displayName }
            QiText(
                if (signedIn) {
                    (if (who.isEmpty()) "" else "$who · ") + stringResource(R.string.library_sign_out)
                } else {
                    stringResource(R.string.library_sign_in)
                },
                mono(11.sp, 500, 1.sp),
                Modifier.tap { if (signedIn) viewModel.signOut() else viewModel.openLogin() },
                color = if (signedIn) colors.muted else colors.accent,
                maxLines = 1,
            )
        }

        if (signedIn) {
            YandexSources(viewModel, player, track, liked) { onSearch(it, SearchSource.YANDEX) }
        } else {
            Column(
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surface),
            ) {
                SourceRow(stringResource(R.string.library_sign_in_row), "", last = true) {
                    viewModel.openLogin()
                }
            }
            QiText(
                stringResource(R.string.library_sign_in_note),
                HintStyle,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                color = colors.muted,
            )
        }

        SpotifySources(spotify, onOpenSection = viewModel::openSection) { onSearch(it, SearchSource.SPOTIFY) }

        LocalFiles(viewModel)

        QiText(
            stringResource(R.string.shuffle_title),
            LabelStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            color = colors.dim,
        )
        ChoiceRow(null, shuffleAlgorithm, SHUFFLE_CHOICES, viewModel::setShuffleAlgorithm)
        QiText(
            stringResource(R.string.shuffle_note),
            HintStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
            color = colors.dim,
        )

        QiText(
            stringResource(R.string.vibe_continue_title),
            LabelStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            color = colors.dim,
        )
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QiText(
                stringResource(R.string.vibe_continue_new_queues),
                sans(14.sp, 500),
                Modifier.weight(1f),
                color = colors.text,
            )
            OnOffPills(continueWithVibe, onChange = viewModel::setContinueWithVibe)
        }
        QiText(
            stringResource(R.string.vibe_continue_note),
            HintStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
            color = colors.dim,
        )

        LyricsServiceBlock(viewModel)

        QiText(
            stringResource(R.string.quality_title),
            LabelStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
            color = colors.dim,
        )
        ChoiceRow(stringResource(R.string.quality_wifi), quality, QUALITY_CHOICES, viewModel::setQuality)
        ChoiceRow(
            stringResource(R.string.quality_mobile),
            qualityMetered,
            QUALITY_CHOICES,
            viewModel::setQualityMetered,
        )
        QiText(
            stringResource(R.string.quality_note),
            HintStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
            color = colors.dim,
        )

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

        if (viewModel.telemetryAvailable) {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QiText(
                    stringResource(R.string.telemetry_title),
                    LabelStyle,
                    Modifier.weight(1f),
                    color = colors.dim,
                )
                OnOffPills(telemetryEnabled, onChange = viewModel::setTelemetryEnabled)
            }
            QiText(
                stringResource(R.string.telemetry_note),
                HintStyle,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                color = colors.dim,
            )
        }
    }
}

private val QUALITY_CHOICES = listOf(
    AudioQuality.STANDARD to R.string.quality_standard,
    AudioQuality.ECONOMY to R.string.quality_economy,
)

private val SHUFFLE_CHOICES = listOf(
    ShuffleChoice.WITHOUT_REPEATS to R.string.shuffle_without_repeats,
    ShuffleChoice.RANDOM_TRACK to R.string.shuffle_random_track,
)

@Composable
private fun YandexSources(
    viewModel: AppViewModel,
    player: PlayerViewModel,
    track: Track?,
    liked: Set<String>,
    onSearch: (String) -> Unit,
) {
    val colors = Qi.colors
    val context = LocalContext.current
    val libraryUi by viewModel.libraryUi.collectAsStateWithLifecycle()
    val forYou by viewModel.forYou.state.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.state.collectAsStateWithLifecycle()
    val artists by viewModel.artists.state.collectAsStateWithLifecycle()
    val albums by viewModel.albums.state.collectAsStateWithLifecycle()
    LibrarySearchField(stringResource(R.string.library_search_hint), onSearch)

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

    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface),
    ) {
        SourceRow(stringResource(R.string.library_liked), "${liked.size}") { viewModel.playLiked() }
        SourceRow(stringResource(R.string.library_for_you), listMeta(forYou)) {
            viewModel.openSection(LibrarySection.FOR_YOU)
        }
        SourceRow(stringResource(R.string.library_wheel), stringResource(R.string.library_wheel_meta)) {
            viewModel.openSection(LibrarySection.WHEEL)
        }
        SourceRow(stringResource(R.string.library_playlists), listMeta(playlists)) {
            viewModel.openSection(LibrarySection.PLAYLISTS)
        }
        SourceRow(
            stringResource(R.string.library_artists),
            listMeta(artists) + " · " + stringResource(R.string.library_top_tracks),
        ) { viewModel.openSection(LibrarySection.ARTISTS) }
        SourceRow(stringResource(R.string.library_albums), listMeta(albums)) {
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
    val likeable = track?.isYandex == true
    val isLiked = likeable && track.id in liked
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LikeButton(
            isLiked,
            Modifier.weight(1f),
            height = 48.dp,
            enabled = likeable,
            onClick = player::toggleLikeCurrent,
        )
        IconWideButton(
            stringResource(R.string.library_dislike),
            Modifier.weight(1f),
            height = 48.dp,
            enabled = likeable,
            onClick = player::dislikeCurrent,
        ) {
            PathIcon(IconPaths.THUMB_DOWN, 22.dp, colors.muted, strokeWidth = IconPaths.OUTLINE)
        }
        IconWideButton(
            stringResource(
                R.string.library_open,
            ),
            Modifier.weight(1f),
            enabled = track?.webUrl != null,
            height = 48.dp,
            onClick = {
                track?.webUrl?.let { url -> context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            },
        ) {
            PathIcon(IconPaths.OPEN_OUTSIDE, 22.dp, colors.muted, strokeWidth = IconPaths.OUTLINE)
        }
    }
}

@Composable
internal fun LibrarySearchField(hint: String, onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    SearchField(text, hint, onChange = { text = it }) {
        val query = text.trim()
        if (query.isNotEmpty()) {
            text = ""
            onSearch(query)
        }
    }
}

@Composable
internal fun SearchField(text: String, hint: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    val colors = Qi.colors
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
            value = text,
            onValueChange = onChange,
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    QiText(hint, style, color = colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inner()
            },
        )
    }
}

@Composable
internal fun listMeta(state: ListState<*>): String = when (state) {
    ListState.Idle -> "…"
    ListState.Loading -> stringResource(R.string.library_loading)
    is ListState.Loaded -> state.items.size.toString()
    is ListState.Failed -> stringResource(R.string.library_row_error)
}

@Composable
private fun <T> ChoiceRow(label: String?, current: T, choices: List<Pair<T, Int>>, onPick: (T) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (label != null) QiText(label, sans(14.sp, 500), Modifier.weight(1f), color = Qi.colors.text)
        FlowRow(
            if (label == null) Modifier.weight(1f) else Modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((choice, text) in choices) {
                PillToggle(stringResource(text).uppercase(), current == choice) {
                    if (current != choice) onPick(choice)
                }
            }
        }
    }
}

@Composable
internal fun SourceRow(name: String, meta: String, last: Boolean = false, onClick: () -> Unit) {
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

@Composable
private fun LocalFiles(viewModel: AppViewModel) {
    val colors = Qi.colors
    val context = LocalContext.current
    val activity = LocalActivity.current
    val access by viewModel.localAccess.collectAsStateWithLifecycle()
    val rows by viewModel.localRows.state.collectAsStateWithLifecycle()
    val picks by viewModel.localPicks.collectAsStateWithLifecycle()
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val rationale =
                activity?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, viewModel.localPermission)
                }
            viewModel.onLocalPermission(granted, rationale == true)
        }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshLocal()
        onPauseOrDispose {}
    }

    QiText(
        stringResource(R.string.library_local),
        LabelStyle,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp),
        color = colors.dim,
    )
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface),
    ) {
        when (access) {
            LocalAccess.GRANTED -> {
                val loading = stringResource(R.string.library_loading)
                val failed = stringResource(R.string.library_row_error)
                val loaded = picks
                fun meta(count: (LocalPicks) -> Int): String = when (rows) {
                    ListState.Idle, ListState.Loading -> loading
                    is ListState.Failed -> failed
                    is ListState.Loaded -> loaded?.let { count(it).toString() } ?: loading
                }
                val allTitle = stringResource(R.string.local_all)
                SourceRow(allTitle, meta { it.trackCount }) { viewModel.playLocal(LocalPick.All, allTitle) }
                SourceRow(stringResource(R.string.local_albums), meta { it.albums.size }) {
                    viewModel.openSection(LibrarySection.LOCAL_ALBUMS)
                }
                SourceRow(stringResource(R.string.local_artists), meta { it.artists.size }) {
                    viewModel.openSection(LibrarySection.LOCAL_ARTISTS)
                }
                SourceRow(stringResource(R.string.local_folders), meta { it.folders.size }, last = true) {
                    viewModel.openSection(LibrarySection.LOCAL_FOLDERS)
                }
            }

            LocalAccess.ASK, LocalAccess.ASK_AGAIN ->
                SourceRow(stringResource(R.string.local_allow), "", last = true) {
                    ask.launch(viewModel.localPermission)
                }

            LocalAccess.SETTINGS ->
                SourceRow(stringResource(R.string.local_settings), "", last = true) {
                    val details = "package:${context.packageName}".toUri()
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details))
                }
        }
    }
    val note =
        when (access) {
            LocalAccess.GRANTED -> null
            LocalAccess.ASK -> R.string.local_allow_note
            LocalAccess.ASK_AGAIN -> R.string.local_allow_again_note
            LocalAccess.SETTINGS -> R.string.local_settings_note
        }
    note?.let {
        QiText(
            stringResource(it),
            HintStyle,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            color = colors.muted,
        )
    }
}

@Composable
private fun LyricsServiceBlock(viewModel: AppViewModel) {
    val colors = Qi.colors
    val thirdParty by viewModel.settings.lyricsThirdParty.collectAsStateWithLifecycle()
    var consent by remember { mutableStateOf(false) }
    if (consent) {
        LrclibConsentDialog(
            onAccept = {
                consent = false
                viewModel.setLyricsThirdParty(true)
            },
            onCancel = { consent = false },
        )
    }
    QiText(
        stringResource(R.string.lyrics_title),
        LabelStyle,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
        color = colors.dim,
    )
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QiText(
            stringResource(R.string.lyrics_lrclib_title),
            sans(14.sp, 500),
            Modifier.weight(1f),
            color = colors.text,
        )
        OnOffPills(thirdParty) { on -> if (on) consent = true else viewModel.setLyricsThirdParty(false) }
    }
    QiText(
        stringResource(R.string.lyrics_lrclib_note),
        HintStyle,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        color = colors.dim,
    )
}
