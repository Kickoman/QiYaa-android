package io.github.kickoman.qiyaa.support

import io.github.kickoman.qiyaa.queue.FailureKind

enum class NetworkLoss(val hit: (FakeEngine) -> Unit) {
    ERR_01_ERROR_THEN_STOPPED({ engine ->
        engine.fail(FailureKind.NETWORK)
        engine.stopPlaying()
    }),
    ERR_03_STOPPED_THEN_ERROR({ engine ->
        engine.stopPlaying()
        engine.fail(FailureKind.NETWORK)
    }),
}
