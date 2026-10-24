package io.github.kickoman.qiyaa.lyrics

import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.track.TrackKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class LyricsPhase { IDLE, LOADING, SHOWN, MISSING, INSTRUMENTAL, FAILED, NO_ACCOUNT }

data class LyricsView(
    val track: Track? = null,
    val phase: LyricsPhase = LyricsPhase.IDLE,
    val lyrics: Lyrics? = null,
    val from: LyricsFrom? = null,
    val better: Boolean = false,
)

enum class LyricsShown {
    SOURCE_SYNCED,
    THIRD_PARTY_SYNCED,
    SOURCE_TEXT,
    THIRD_PARTY_TEXT,
    INSTRUMENTAL,
    NONE,
}

data class LyricsLookup(val shown: LyricsShown, val thirdPartyFailed: Boolean)

class LyricsFinder(
    private val scope: CoroutineScope,
    private val source: LyricsSource,
    private val thirdParty: LyricsSource?,
    currentTrack: Flow<Track?>,
    active: Flow<Boolean>,
    thirdPartyEnabled: Flow<Boolean>,
    connectivity: Flow<Boolean>,
    signedIn: Flow<Boolean>,
    private val answerWaitMs: Long = ANSWER_WAIT_MS,
    private val sourceSilentMs: Long = SOURCE_SILENT_MS,
) {
    private val mutableView = MutableStateFlow(LyricsView())
    val view: StateFlow<LyricsView> = mutableView.asStateFlow()

    private val mutableLookups = MutableSharedFlow<LyricsLookup>(extraBufferCapacity = LOOKUP_BUFFER)
    val lookups: SharedFlow<LyricsLookup> = mutableLookups.asSharedFlow()

    private class Remembered(val key: TrackKey, var source: LyricsAnswer?, var thirdParty: LyricsAnswer?)

    private var isActive = false
    private var thirdPartyOn = false
    private var current: Track? = null
    private var track: Track? = null
    private var sourceAnswer: LyricsAnswer? = null
    private var thirdPartyAnswer: LyricsAnswer? = null
    private var sourcePending = false
    private var thirdPartyPending = false
    private var sourceSilent = false
    private var sourceRequest = 0L
    private var thirdPartyRequest = 0L
    private var sourceJob: Job? = null
    private var silenceJob: Job? = null
    private var thirdPartyJob: Job? = null
    private var lookupOpen = false
    private var lookupAsked = false
    private val memory = ArrayDeque<Remembered>()

    init {
        scope.launch {
            currentTrack.collect {
                current = it
                follow()
            }
        }
        scope.launch { active.collect(::setActive) }
        scope.launch { thirdPartyEnabled.collect(::setThirdPartyEnabled) }
        scope.launch { connectivity.distinctUntilChanged().drop(1).filter { it }.collect { retryFailed() } }
        scope.launch { signedIn.distinctUntilChanged().drop(1).collect { forget() } }
    }

    private fun setActive(on: Boolean) {
        if (on == isActive) return
        isActive = on
        if (on) follow() else stop()
    }

    private fun setThirdPartyEnabled(on: Boolean) {
        if (on == thirdPartyOn) return
        thirdPartyOn = on
        if (!on) {
            cancelThirdParty()
            thirdPartyAnswer = null
        } else if (isActive && track != null && !lookupOpen) {
            lookupOpen = true
            lookupAsked = false
        }
        maybeAskThirdParty()
        update()
        maybeFinish()
    }

    private fun retryFailed() {
        if (!isActive || track == null) return
        val sourceFailed = !sourcePending && sourceAnswer == LyricsAnswer.Failed
        val thirdPartyFailed = thirdPartyAnswer == LyricsAnswer.Failed
        if (!sourceFailed && !thirdPartyFailed) return
        if (!lookupOpen) {
            lookupOpen = true
            lookupAsked = false
        }
        if (thirdPartyFailed) thirdPartyAnswer = null
        if (sourceFailed) {
            sourceAnswer = null
            askSource()
        }
        maybeAskThirdParty()
        update()
        maybeFinish()
    }

    private fun forget() {
        memory.clear()
        val again = track
        if (isActive && again != null) {
            finishLookup()
            start(again)
        }
    }

    private fun follow() {
        if (!isActive) return
        val next = current
        if (next == null) {
            stop()
            return
        }
        if (track?.key == next.key) return
        finishLookup()
        start(next)
    }

    private fun start(next: Track) {
        drop()
        track = next
        lookupOpen = true
        lookupAsked = false
        val kept = rememberedFor(next.key)?.source
        if (kept != null) sourceAnswer = kept else askSource()
        maybeAskThirdParty()
        update()
        maybeFinish()
    }

    private fun askSource() {
        val asked = track ?: return
        sourcePending = true
        sourceSilent = false
        lookupAsked = true
        val request = ++sourceRequest
        sourceJob?.cancel()
        silenceJob?.cancel()
        sourceJob =
            scope.launch {
                val answer = waitFor { source.lyrics(asked) }
                if (answer == null) sourceTimedOut(request) else sourceAnswered(request, answer)
            }
        silenceJob =
            scope.launch {
                delay(sourceSilentMs)
                if (request != sourceRequest || !sourcePending) return@launch
                sourceSilent = true
                maybeAskThirdParty()
                update()
            }
    }

    private fun maybeAskThirdParty() {
        val asked = track
        val service = thirdParty
        if (!isActive || asked == null || service == null || !thirdPartyOn) return
        if (thirdPartyPending || thirdPartyAnswer != null) return
        if (sourceAnswer.isText(LyricsKind.SYNCED) || (sourceAnswer == null && !sourceSilent)) return
        rememberedFor(asked.key)?.thirdParty?.let { kept ->
            thirdPartyAnswer = if (kept is LyricsAnswer.Missing) kept.copy(serverError = false) else kept
            return
        }
        thirdPartyPending = true
        lookupAsked = true
        val request = ++thirdPartyRequest
        thirdPartyJob?.cancel()
        thirdPartyJob =
            scope.launch {
                val answer = waitFor { service.lyrics(asked) }
                if (answer == null) thirdPartyTimedOut(request) else thirdPartyAnswered(request, answer)
            }
    }

    private fun sourceAnswered(request: Long, answer: LyricsAnswer) {
        if (request != sourceRequest || !sourcePending) return
        sourcePending = false
        silenceJob?.cancel()
        remember(answer, fromSource = true)
        sourceAnswer = answer
        if (answer.isText(LyricsKind.SYNCED)) cancelThirdParty()
        maybeAskThirdParty()
        update()
        maybeFinish()
    }

    private fun thirdPartyAnswered(request: Long, answer: LyricsAnswer) {
        if (request != thirdPartyRequest || !thirdPartyPending) return
        thirdPartyPending = false
        val fromService = if (answer is LyricsAnswer.Text) {
            answer.copy(
                from = LyricsFrom.THIRD_PARTY,
            )
        } else {
            answer
        }
        remember(fromService, fromSource = false)
        thirdPartyAnswer = fromService
        update()
        maybeFinish()
    }

    private fun sourceTimedOut(request: Long) {
        if (request != sourceRequest || !sourcePending) return
        sourceRequest++
        sourcePending = false
        silenceJob?.cancel()
        sourceAnswer = LyricsAnswer.Missing()
        maybeAskThirdParty()
        update()
        maybeFinish()
    }

    private fun thirdPartyTimedOut(request: Long) {
        if (request != thirdPartyRequest || !thirdPartyPending) return
        cancelThirdParty()
        thirdPartyAnswer = LyricsAnswer.Missing()
        update()
        maybeFinish()
    }

    private fun cancelThirdParty() {
        if (!thirdPartyPending) return
        thirdPartyPending = false
        thirdPartyRequest++
        thirdPartyJob?.cancel()
    }

    private fun remember(answer: LyricsAnswer, fromSource: Boolean) {
        val key = track?.key ?: return
        if (answer == LyricsAnswer.Failed || answer == LyricsAnswer.NoAccount) return
        val entry = rememberedFor(key)?.also(memory::remove) ?: Remembered(key, null, null)
        if (fromSource) entry.source = answer else entry.thirdParty = answer
        memory.addFirst(entry)
        while (memory.size > REMEMBERED_TRACKS) memory.removeLast()
    }

    private fun rememberedFor(key: TrackKey): Remembered? = memory.firstOrNull { it.key == key }

    private fun rank(): LyricsShown = when {
        sourceAnswer.isText(LyricsKind.SYNCED) -> LyricsShown.SOURCE_SYNCED
        thirdPartyAnswer.isText(LyricsKind.SYNCED) -> LyricsShown.THIRD_PARTY_SYNCED
        sourceAnswer.isText(LyricsKind.PLAIN) -> LyricsShown.SOURCE_TEXT
        thirdPartyAnswer.isText(LyricsKind.PLAIN) -> LyricsShown.THIRD_PARTY_TEXT
        else -> LyricsShown.NONE
    }

    private fun update() {
        val looked = track
        val best = rank()
        mutableView.value =
            when {
                !isActive || looked == null -> LyricsView()

                best != LyricsShown.NONE -> {
                    val fromSource = best == LyricsShown.SOURCE_SYNCED || best == LyricsShown.SOURCE_TEXT
                    val text = (if (fromSource) sourceAnswer else thirdPartyAnswer) as LyricsAnswer.Text
                    val better =
                        (sourcePending && best != LyricsShown.SOURCE_SYNCED) ||
                            (
                                thirdPartyPending &&
                                    (best == LyricsShown.SOURCE_TEXT || best == LyricsShown.THIRD_PARTY_TEXT)
                                )
                    LyricsView(looked, LyricsPhase.SHOWN, text.lyrics, text.from, better)
                }

                thirdPartyAnswer == LyricsAnswer.Instrumental -> LyricsView(looked, LyricsPhase.INSTRUMENTAL)

                sourcePending || thirdPartyPending -> LyricsView(looked, LyricsPhase.LOADING)

                sourceAnswer == LyricsAnswer.NoAccount -> LyricsView(looked, LyricsPhase.NO_ACCOUNT)

                sourceAnswer == LyricsAnswer.Failed -> LyricsView(looked, LyricsPhase.FAILED)

                else -> LyricsView(looked, LyricsPhase.MISSING)
            }
    }

    private fun maybeFinish() {
        if (lookupOpen && !sourcePending && !thirdPartyPending) finishLookup()
    }

    private fun finishLookup() {
        if (!lookupOpen) return
        lookupOpen = false
        if (!lookupAsked) return
        val best = rank()
        val shown =
            when {
                best != LyricsShown.NONE -> best
                thirdPartyAnswer == LyricsAnswer.Instrumental -> LyricsShown.INSTRUMENTAL
                else -> LyricsShown.NONE
            }
        val answer = thirdPartyAnswer
        val failed = answer == LyricsAnswer.Failed || (answer is LyricsAnswer.Missing && answer.serverError)
        mutableLookups.tryEmit(LyricsLookup(shown, failed))
    }

    private fun drop() {
        sourceRequest++
        sourcePending = false
        sourceSilent = false
        sourceJob?.cancel()
        silenceJob?.cancel()
        cancelThirdParty()
        sourceAnswer = null
        thirdPartyAnswer = null
        track = null
    }

    private fun stop() {
        finishLookup()
        drop()
        update()
    }

    private suspend fun waitFor(ask: suspend () -> LyricsAnswer): LyricsAnswer? {
        val reply = scope.async { ask() }
        try {
            return withTimeoutOrNull(answerWaitMs) { reply.await() }
        } finally {
            reply.cancel()
        }
    }

    private fun LyricsAnswer?.isText(kind: LyricsKind): Boolean =
        this is LyricsAnswer.Text && lyrics.kind == kind

    companion object {
        const val ANSWER_WAIT_MS = 8_000L
        const val SOURCE_SILENT_MS = 1_000L
        const val REMEMBERED_TRACKS = 10
        private const val LOOKUP_BUFFER = 16
    }
}
