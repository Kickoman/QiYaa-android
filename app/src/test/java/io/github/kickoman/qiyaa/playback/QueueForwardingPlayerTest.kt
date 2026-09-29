package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.kickoman.qiyaa.support.QueueHarness
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueForwardingPlayerTest {
    @Test
    fun `a listener of the session gets every player event that a plain ForwardingPlayer passes on`() =
        runTest {
            val queue = QueueHarness(this, attached = false).controller
            val plain = FakePlayer()
            val ours = FakePlayer()
            val expected = deliveries(ForwardingPlayer(plain.proxy), plain)
            val actual = deliveries(QueueForwardingPlayer(ours.proxy, queue), ours)

            assertEquals(emptyList<String>(), expected.filterValues { it.isEmpty() }.keys.toList())
            assertEquals(expected, actual)
        }

    @Test
    fun `a removed listener is taken off the player`() = runTest {
        val player = FakePlayer()
        val forwarding = QueueForwardingPlayer(player.proxy, QueueHarness(this, attached = false).controller)
        val listener = recorder(ArrayList())
        forwarding.addListener(listener)
        forwarding.removeListener(listener)
        assertTrue(player.listeners.isEmpty())
    }

    private class FakePlayer {
        val listeners = ArrayList<Player.Listener>()
        val proxy: Player =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) {
                    self,
                    method,
                    args,
                ->
                when (method.name) {
                    "addListener" -> listeners += args[0] as Player.Listener
                    "removeListener" -> listeners.remove(args[0])
                }
                objectMethod(self, method, args) ?: defaultOf(method.returnType)
            } as Player
    }

    // For every Player.Listener callback that the player sends: which callbacks reach a listener
    // added to `forwarding`.
    private fun deliveries(forwarding: Player, player: FakePlayer): Map<String, List<String>> {
        val received = ArrayList<Method>()
        forwarding.addListener(recorder(received))
        val registered = player.listeners.single()
        return Player.Listener::class.java.methods.associate { event ->
            received.clear()
            event.invoke(registered, *event.parameterTypes.map { sample(it, forwarding) }.toTypedArray())
            event.toGenericString() to received.map { it.toGenericString() }
        }
    }

    private fun recorder(received: MutableList<Method>): Player.Listener = Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader,
        arrayOf(Player.Listener::class.java),
    ) {
            self,
            method,
            args,
        ->
        objectMethod(self, method, args) ?: run {
            received += method
            null
        }
    } as Player.Listener

    private companion object {
        fun objectMethod(self: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "equals" -> if (method.parameterCount == 1) self === args?.get(0) else null
            "hashCode" -> if (method.parameterCount == 0) System.identityHashCode(self) else null
            "toString" -> if (method.parameterCount == 0) "fake" else null
            else -> null
        }

        fun defaultOf(type: Class<*>): Any? = when (type) {
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Boolean::class.javaPrimitiveType -> false
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }

        fun sample(type: Class<*>, player: Player): Any = when (type) {
            Int::class.javaPrimitiveType -> 1
            Long::class.javaPrimitiveType -> 1L
            Boolean::class.javaPrimitiveType -> true
            Float::class.javaPrimitiveType -> 0.5f
            Player::class.java -> player
            Player.Events::class.java -> Player.Events(FlagSet.Builder().build())
            Player.PositionInfo::class.java ->
                Player.PositionInfo(null, 0, MediaItem.EMPTY, null, 0, 0, 0, C.INDEX_UNSET, C.INDEX_UNSET)
            PlaybackException::class.java ->
                PlaybackException("test", null, PlaybackException.ERROR_CODE_UNSPECIFIED)
            Metadata::class.java -> Metadata()
            List::class.java -> emptyList<Any>()
            else -> type.fields.first { Modifier.isStatic(it.modifiers) && it.type == type }.get(null)!!
        }
    }
}
