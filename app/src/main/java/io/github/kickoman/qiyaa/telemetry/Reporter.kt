package io.github.kickoman.qiyaa.telemetry

import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class Reporter(
    version: String,
    private val store: TelemetryStore,
    private val post: suspend (String) -> Answer,
    private val scope: CoroutineScope,
    private val io: CoroutineContext,
    private val now: () -> Instant,
    private val uptimeMs: () -> Long,
    private val log: (String) -> Unit = {},
    val session: String = UUID.randomUUID().toString(),
) {
    private val version = EventCodec.version(version)
    private val lock = Any()
    private val sending = Mutex()
    private val files = Mutex()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val crashHandler = CrashHandler(store.crashFile(session), session, this.version, uptimeMs)

    private var machine = ""
    private var begun = false
    private var forgotten = false
    private val pending = ArrayList<String>()
    private val queue = ArrayList<Queued>()
    private var nextSequence = 0L
    private var inFlight: Set<Long> = emptySet()
    private var postJob: Job? = null
    private var blockedUntilMs = 0L
    private var failures = 0
    private var errors = 0
    private var errorsDropped = 0L
    private var tracks = 0L
    private var jams = 0L
    private val inUse = Stopwatch(uptimeMs)
    private val playing = Stopwatch(uptimeMs)
    private var queueChanged = false
    private var markerDue = false

    private class Queued(val sequence: Long, val line: String)

    private class Batch(val body: String, val sequences: Set<Long>)

    private class Earlier(val session: String, val lines: List<String>, val end: String?)

    private class Changes(val lines: List<String>?, val marker: Marker?)

    private val writer: Job = scope.launch(io) { for (signal in writes) persist() }

    val queued: Int get() = synchronized(lock) { queue.size + pending.size }

    fun install() {
        if (synchronized(lock) { !forgotten }) crashHandler.install()
    }

    suspend fun begin(start: StartFields, machine: String, exits: Map<String, ExitInfo>) {
        files.withLock {
            val earlier = withContext(io) { earlierRuns(exits) }
            val startLine = encode(Event.Start(now(), session, version, start))
            val (lines, marker) =
                synchronized(lock) {
                    if (forgotten || begun) return
                    this.machine = machine
                    val ends = earlier.flatMap { it.lines + listOfNotNull(it.end) }
                    val lines = ends + listOfNotNull(startLine) + pending
                    pending.clear()
                    for (line in lines) queue += Queued(nextSequence++, line)
                    trim()
                    begun = true
                    queue.map(Queued::line) to marker()
                }
            withContext(io) {
                try {
                    store.writeQueue(session, lines)
                    store.writeMarker(session, marker)
                    for (run in earlier) store.remove(run.session)
                } catch (ignored: IOException) {
                    // The earlier runs' files stay: the next run reports them again rather than losing them.
                }
            }
        }
    }

    fun recordError(area: ErrorArea, kind: String, httpStatus: Int? = null) {
        val counted =
            synchronized(lock) {
                when {
                    forgotten -> return
                    errors >= MAX_ERRORS_PER_SESSION -> {
                        errorsDropped++
                        false
                    }
                    else -> {
                        errors++
                        true
                    }
                }
            }
        if (counted) record(Event.Error(now(), session, version, area, kind, httpStatus))
    }

    fun recordFeature(name: FeatureName, value: String) {
        record(Event.Feature(now(), session, version, name, value))
    }

    fun countTrack() {
        synchronized(lock) { tracks++ }
    }

    fun countJam() {
        synchronized(lock) { jams++ }
    }

    fun setPlaying(on: Boolean) {
        synchronized(lock) { playing.set(on) }
    }

    fun setInUse(on: Boolean) {
        synchronized(lock) { inUse.set(on) }
    }

    fun saveMarker() {
        synchronized(lock) {
            if (!begun || forgotten) return
            markerDue = true
        }
        writes.trySend(Unit)
    }

    suspend fun send(): Answer? {
        if (!sending.tryLock()) return null
        try {
            var last: Answer? = null
            while (true) {
                val batch = takeBatch() ?: return last
                var answer: Answer? = null
                try {
                    answer = postBatch(batch)
                } finally {
                    if (answer == null) release()
                }
                val again = settle(batch, answer ?: return null)
                last = answer
                if (!again) return last
            }
        } finally {
            sending.unlock()
        }
    }

    suspend fun forget() {
        val post =
            synchronized(lock) {
                forgotten = true
                pending.clear()
                queue.clear()
                postJob
            }
        withContext(NonCancellable + io) {
            post?.cancelAndJoin()
            writes.close()
            writer.join()
            crashHandler.uninstall()
            files.withLock { store.removeAll() }
        }
    }

    private fun record(event: Event) {
        val line = encode(event) ?: return
        synchronized(lock) {
            if (forgotten) return
            if (!begun) {
                pending += line
                if (pending.size > MAX_QUEUED_EVENTS) pending.removeAt(0)
                return
            }
            queue += Queued(nextSequence++, line)
            trim()
            queueChanged = true
        }
        writes.trySend(Unit)
    }

    private fun encode(event: Event): String? = EventCodec.encode(event)?.toString()

    private fun trim() {
        var excess = queue.size - MAX_QUEUED_EVENTS
        if (excess <= 0) return
        val iterator = queue.iterator()
        while (excess > 0 && iterator.hasNext()) {
            if (iterator.next().sequence !in inFlight) {
                iterator.remove()
                excess--
            }
        }
    }

    private fun marker(): Marker = Marker(
        version,
        Counts(
            seconds = inUse.ms() / MS_PER_SECOND,
            tracks = tracks,
            playMinutes = playing.ms() / MS_PER_MINUTE,
            jams = jams,
            errorsDropped = errorsDropped,
        ),
    )

    private fun earlierRuns(exits: Map<String, ExitInfo>): List<Earlier> {
        val at = now()
        return store.sessions().filter { it != session }.map { run ->
            val end = RunEnd.event(run, store.readMarker(run), store.readCrash(run), exits[run], at)
            Earlier(run, store.readQueue(run), end?.let(::encode))
        }
    }

    private suspend fun persist() {
        files.withLock {
            val changes =
                synchronized(lock) {
                    if (!begun || forgotten) return
                    val lines = if (queueChanged) queue.map(Queued::line) else null
                    val marker = if (markerDue) marker() else null
                    queueChanged = false
                    markerDue = false
                    Changes(lines, marker)
                }
            try {
                changes.lines?.let { store.writeQueue(session, it) }
                changes.marker?.let { store.writeMarker(session, it) }
            } catch (ignored: IOException) {
                // The files keep their last state; the next change writes them again.
            }
        }
    }

    private fun takeBatch(): Batch? = synchronized(lock) {
        if (!begun || forgotten || queue.isEmpty() || uptimeMs() < blockedUntilMs) return null
        val lines = ArrayList<String>()
        val sequences = HashSet<Long>()
        var bytes = utf8Length(EventCodec.batch(machine, emptyList()))
        for (item in queue) {
            val size = utf8Length(item.line) + 1
            val full =
                lines.size == MAX_BATCH_EVENTS || (lines.isNotEmpty() && bytes + size > MAX_BATCH_BYTES)
            if (full) break
            lines += item.line
            sequences += item.sequence
            bytes += size
        }
        inFlight = sequences
        Batch(EventCodec.batch(machine, lines), sequences)
    }

    private suspend fun postBatch(batch: Batch): Answer? = coroutineScope {
        val call = async(io) { post(batch.body) }
        val tracked =
            synchronized(lock) {
                if (!forgotten) postJob = call
                !forgotten
            }
        if (!tracked) call.cancel()
        try {
            call.await()
        } catch (cancelled: CancellationException) {
            ensureActive()
            null
        }
    }

    private fun release() {
        synchronized(lock) {
            inFlight = emptySet()
            postJob = null
        }
    }

    private fun settle(batch: Batch, answer: Answer): Boolean {
        val events = batch.sequences.size
        val (again, message) =
            synchronized(lock) {
                inFlight = emptySet()
                postJob = null
                if (forgotten) return false
                val message =
                    when (answer) {
                        is Answer.Accepted -> {
                            remove(batch)
                            "HTTP ${answer.status}, $events events sent"
                        }
                        is Answer.Dropped -> {
                            remove(batch)
                            "HTTP ${answer.status}, $events events dropped"
                        }
                        is Answer.RetryAfter -> {
                            blockedUntilMs = uptimeMs() + answer.seconds * MS_PER_SECOND
                            "HTTP 429, $events events kept, next try in ${answer.seconds} s"
                        }
                        is Answer.Failed -> {
                            failures++
                            val seconds = Backoff.seconds(failures)
                            blockedUntilMs = uptimeMs() + seconds * MS_PER_SECOND
                            "${answer.status?.let { "HTTP $it" } ?: "no answer"}, $events events kept, " +
                                "next try in $seconds s"
                        }
                    }
                (answer is Answer.Accepted && queue.isNotEmpty()) to message
            }
        log(message)
        if (answer is Answer.Accepted || answer is Answer.Dropped) writes.trySend(Unit)
        return again
    }

    private fun remove(batch: Batch) {
        queue.removeAll { it.sequence in batch.sequences }
        queueChanged = true
        failures = 0
        blockedUntilMs = 0
    }

    private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private class Stopwatch(private val clock: () -> Long) {
        private var since: Long? = null
        private var totalMs = 0L

        fun set(on: Boolean) {
            val start = since
            if (on && start == null) {
                since = clock()
            } else if (!on && start != null) {
                totalMs += clock() - start
                since = null
            }
        }

        fun ms(): Long = totalMs + (since?.let { clock() - it } ?: 0)
    }

    companion object {
        const val MAX_QUEUED_EVENTS = 500
        const val MAX_BATCH_EVENTS = 50
        const val MAX_BATCH_BYTES = 60 * 1024
        const val MAX_ERRORS_PER_SESSION = 50
        private const val MS_PER_SECOND = 1_000L
        private const val MS_PER_MINUTE = 60_000L
    }
}
