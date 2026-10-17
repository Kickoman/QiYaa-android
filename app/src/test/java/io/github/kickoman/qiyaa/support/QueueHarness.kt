package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.QueueController
import io.github.kickoman.qiyaa.queue.QueueEvent
import io.github.kickoman.qiyaa.yandex.Track
import io.github.kickoman.qiyaa.yandex.WaveBatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class QueueHarness(scope: TestScope, attached: Boolean = true, val store: FakeQueueStore = FakeQueueStore()) {
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
