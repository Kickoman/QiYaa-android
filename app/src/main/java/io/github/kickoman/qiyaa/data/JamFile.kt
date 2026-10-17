package io.github.kickoman.qiyaa.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException

class JamFile(context: Context, name: String = HOST) {
    private val file = AtomicFile(File(context.filesDir, name))

    fun read(): String? = try {
        file.readFully().toString(Charsets.UTF_8)
    } catch (ignored: IOException) {
        null
    }

    fun write(text: String?) {
        if (text == null) {
            file.delete()
            return
        }
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
        const val HOST = "jam.json"
        const val GUEST = "jam-guest.json"
    }
}
