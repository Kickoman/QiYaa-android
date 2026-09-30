package io.github.kickoman.qiyaa.jam

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface JamStore {
    fun read(): String?

    fun write(text: String?)
}

data class JamSession(
    val roomId: String,
    val hostSecret: String,
    val joinUrl: String,
    val snapshot: JsonObject?,
    val outbox: List<String>,
)

class JamSessionStore(private val store: JamStore) {
    fun load(): JamSession? = JamSessionCodec.decode(store.read())

    fun save(session: JamSession) = store.write(JamSessionCodec.encode(session))

    fun clear() = store.write(null)
}

object JamSessionCodec {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SessionFile(
        val version: Int,
        val roomId: String,
        val hostSecret: String,
        val joinUrl: String,
        val snapshot: JsonElement? = null,
        val outbox: List<String> = emptyList(),
    )

    fun encode(session: JamSession): String = json.encodeToString(
        SessionFile.serializer(),
        SessionFile(
            VERSION,
            session.roomId,
            session.hostSecret,
            session.joinUrl,
            session.snapshot,
            session.outbox,
        ),
    )

    fun decode(text: String?): JamSession? {
        if (text.isNullOrEmpty()) return null
        val file =
            try {
                json.decodeFromString(SessionFile.serializer(), text)
            } catch (ignored: SerializationException) {
                return null // a broken file is "no stored jam"
            } catch (ignored: IllegalArgumentException) {
                return null // the same, for a value of the wrong shape
            }
        if (file.version != VERSION) return null
        return JamSession(
            file.roomId,
            file.hostSecret,
            file.joinUrl,
            file.snapshot as? JsonObject,
            file.outbox,
        )
    }
}
