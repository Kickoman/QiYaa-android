package io.github.kickoman.qiyaa.jam

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class GuestRoom(val serverUrl: String, val roomId: String)

data class GuestMembership(
    val room: GuestRoom,
    val participantId: String,
    val name: String,
    val joinSecret: String,
    val firstJoinedAt: Long,
    val joined: Boolean = false,
    val ended: Boolean = false,
)

data class GuestMemory(
    val current: GuestRoom? = null,
    val memberships: List<GuestMembership> = emptyList(),
    val lastName: String? = null,
) {
    fun membership(room: GuestRoom): GuestMembership? = memberships.firstOrNull { it.room == room }

    fun with(membership: GuestMembership): GuestMemory {
        val index = memberships.indexOfFirst { it.room == membership.room }
        val updated =
            if (index < 0) {
                listOf(membership) + memberships
            } else {
                memberships.toMutableList().also { it[index] = membership }
            }
        return copy(memberships = updated)
    }

    fun without(room: GuestRoom): GuestMemory = copy(
        current = current.takeUnless { it == room },
        memberships = memberships.filterNot { it.room == room },
    )

    fun alive(now: Long): GuestMemory {
        val kept =
            memberships
                .filter { now - it.firstJoinedAt < JamGuestStore.MEMBERSHIP_LIFE_MS }
                .sortedByDescending { it.firstJoinedAt }
                .take(JamGuestStore.MAX_MEMBERSHIPS)
        return copy(current = current?.takeIf { room -> kept.any { it.room == room } }, memberships = kept)
    }
}

class JamGuestStore(private val store: JamStore) {
    fun load(now: Long): GuestMemory = (JamGuestCodec.decode(store.read()) ?: GuestMemory()).alive(now)

    fun save(memory: GuestMemory) = store.write(JamGuestCodec.encode(memory))

    fun clear() = store.write(null)

    companion object {
        const val MEMBERSHIP_LIFE_MS = 12 * 60 * 60 * 1_000L
        const val MAX_MEMBERSHIPS = 20
    }
}

object JamGuestCodec {
    const val VERSION = 1
    private const val PROBE_ID = "probe"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RoomEntry(val serverUrl: String, val roomId: String)

    @Serializable
    private data class MembershipEntry(
        val serverUrl: String,
        val roomId: String,
        val participantId: String,
        val name: String,
        val joinSecret: String,
        val firstJoinedAt: Long,
        val joined: Boolean = false,
        val ended: Boolean = false,
    )

    @Serializable
    private data class GuestFile(
        val version: Int,
        val current: RoomEntry? = null,
        val lastName: String? = null,
        val rooms: List<MembershipEntry> = emptyList(),
    )

    fun encode(memory: GuestMemory): String = json.encodeToString(
        GuestFile.serializer(),
        GuestFile(
            version = VERSION,
            current = memory.current?.let { RoomEntry(it.serverUrl, it.roomId) },
            lastName = memory.lastName,
            rooms =
            memory.memberships.map {
                MembershipEntry(
                    it.room.serverUrl,
                    it.room.roomId,
                    it.participantId,
                    it.name,
                    it.joinSecret,
                    it.firstJoinedAt,
                    it.joined,
                    it.ended,
                )
            },
        ),
    )

    fun decode(text: String?): GuestMemory? {
        if (text.isNullOrEmpty()) return null
        val file =
            try {
                json.decodeFromString(GuestFile.serializer(), text)
            } catch (ignored: IllegalArgumentException) {
                return null
            }
        if (file.version != VERSION) return null
        val memberships =
            file.rooms
                .map {
                    GuestMembership(
                        GuestRoom(it.serverUrl, it.roomId),
                        it.participantId,
                        it.name,
                        it.joinSecret,
                        it.firstJoinedAt,
                        it.joined,
                        it.ended,
                    )
                }.filter(::joinable)
                .distinctBy { it.room }
        val current = file.current?.let { GuestRoom(it.serverUrl, it.roomId) }
        return GuestMemory(
            current = current?.takeIf { room -> memberships.any { it.room == room } },
            memberships = memberships,
            lastName = file.lastName,
        )
    }

    private fun joinable(membership: GuestMembership): Boolean {
        val room = membership.room
        if (JamLink.find("${room.serverUrl}/j/${room.roomId}")?.serverUrl != room.serverUrl) return false
        val probe =
            Join(PROBE_ID, room.roomId, membership.joinSecret, membership.participantId, membership.name)
        return JamCodec.decodeClient(JamCodec.encode(probe)) is Decoded.Message
    }
}
