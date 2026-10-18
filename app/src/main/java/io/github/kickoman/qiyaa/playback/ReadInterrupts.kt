package io.github.kickoman.qiyaa.playback

import java.io.InterruptedIOException
import okhttp3.Call

class ReadInterrupts(
    private val schedule: (delayMs: Long, check: () -> Unit) -> Unit,
    private val log: (String) -> Unit = {},
) {
    inner class Watch {
        private val slot = Slot()

        fun <T> during(call: Call?, block: () -> T): T = watched(slot, call, block)

        fun bind(call: Call) = bind(slot, call)
    }

    private class Slot {
        var thread: Thread? = null
        var call: Call? = null
    }

    private val lock = Any()
    private val reading = ArrayList<Slot>()
    private var checkPending = false

    fun watch(): Watch = Watch()

    private fun <T> watched(slot: Slot, call: Call?, block: () -> T): T {
        val thread = Thread.currentThread()
        if (thread.isInterrupted) {
            throw InterruptedIOException("The load on ${thread.name} was canceled before this read")
        }
        synchronized(lock) {
            slot.thread = thread
            slot.call = call
            reading += slot
            if (!checkPending) {
                checkPending = true
                schedule(CHECK_PERIOD_MS, ::check)
            }
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                reading -= slot
                slot.thread = null
                slot.call = null
            }
        }
    }

    private fun bind(slot: Slot, call: Call) {
        synchronized(lock) {
            if (slot.thread === Thread.currentThread()) slot.call = call
        }
    }

    private fun check() {
        try {
            val watched =
                synchronized(lock) {
                    reading.mapNotNull { slot -> slot.call?.let { call -> slot.thread?.let { it to call } } }
                }
            for ((thread, call) in watched) {
                if (thread.isInterrupted && !call.isCanceled()) {
                    call.cancel()
                    log("Canceled the HTTP call of a canceled load stuck in a read")
                }
            }
        } finally {
            synchronized(lock) {
                if (reading.isEmpty()) checkPending = false else schedule(CHECK_PERIOD_MS, ::check)
            }
        }
    }

    companion object {
        const val CHECK_PERIOD_MS = 100L
    }
}
