package io.github.kickoman.qiyaa.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** The saved play queue as text; the format belongs to `queue/QueueSnapshotCodec`. */
class QueueFile(context: Context) {
    private val file = AtomicFile(File(context.filesDir, NAME))

    fun read(): String? = try {
        file.readFully().toString(Charsets.UTF_8)
    } catch (ignored: IOException) {
        null
    }

    fun write(text: String) {
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failed: IOException) {
            file.failWrite(stream)
            throw failed
        }
    }

    companion object {
        const val NAME = "queue.json"
    }
}
