package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.Continuation
import io.github.kickoman.qiyaa.queue.ContinuationKey
import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.QueueEvent
import io.github.kickoman.qiyaa.track.Track
import io.github.kickoman.qiyaa.yandex.ErrorKind
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class QueueHarness(
    scope: TestScope,
    attached: Boolean = true,
    val store: FakeQueueStore = FakeQueueStore(),
    var continueWithVibe: Boolean = false,
    random: Random = Random(0),
    errorKindOf: (Throwable) -> ErrorKind = ErrorKind::of,
    continuationOf: (ContinuationKey) -> Continuation? = { null },
    autoplayFrom: (Track) -> Continuation? = { null },
) {
    private var playIds = 0
    val source = FakeMusicSource()
    val network = MutableStateFlow(true)
    val logged = ArrayList<String>()
    val controller =
        QueueController(
            source = source,
            connectivity = network,
            scope = scope.backgroundScope,
            io = StandardTestDispatcher(scope.testScheduler),
            newPlayId = { "play-${++playIds}" },
            clock = { scope.testScheduler.currentTime },
            store = store,
            logFailure = { stage, failed -> logged += "$stage ${failed.message}" },
            continueWithVibeByDefault = { continueWithVibe },
            random = random,
            errorKindOf = errorKindOf,
            continuationOf = continuationOf,
            autoplayFrom = autoplayFrom,
        )
    val engine = FakeEngine(controller).also { if (attached) controller.attach(it) }
    val events = ArrayList<QueueEvent>()

    init {
        scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.events.collect {
                events +=
                    it
            }
        }
    }

    companion object {
        fun track(id: String, available: Boolean = true) =
            Track(id = id, title = "T$id", available = available)

        fun tracks(vararg ids: String) = ids.map { track(it) }

        fun likes(scope: TestScope, vararg ids: String, continueWithVibe: Boolean = true): QueueHarness {
            val harness = QueueHarness(scope, continueWithVibe = continueWithVibe)
            harness.controller.setQueue(tracks(*ids), "Likes", isWave = false, autoplay = true, null)
            return harness
        }

        fun wave(scope: TestScope, vararg ids: String): QueueHarness {
            val harness = QueueHarness(scope)
            harness.source.onStartWave = { WaveBatch("S1", "B1", tracks(*ids)) }
            harness.source.onMoreWave = { _, _ -> CompletableDeferred<WaveBatch>().await() }
            harness.controller.playWave(listOf("user:onyourwave"), "My Wave")
            scope.runCurrent()
            return harness
        }
    }
}
