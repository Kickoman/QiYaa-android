package io.github.kickoman.qiyaa.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerCommandsTest {
    @Test
    fun `the playlist edits are exactly the commands that change the playlist or its metadata`() {
        assertEquals(
            setOf(
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_SET_MEDIA_ITEM,
                Player.COMMAND_SET_PLAYLIST_METADATA,
            ),
            ControllerCommands.PLAYLIST_EDITS.toSet(),
        )
    }

    @Test
    fun `a new Media3 player command fails here until it is sorted into the playlist edits or not`() {
        val commands =
            Player::class.java.fields
                .filter { it.name.startsWith("COMMAND_") && it.type == Int::class.javaPrimitiveType }
                .map { it.getInt(null) }
                .toSet() - Player.COMMAND_INVALID

        assertEquals((1..35).toSet(), commands)
    }
}
