package io.github.kickoman.qiyaa.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.kickoman.qiyaa.queue.QueueController

// The player the MediaSession sees: Next and Previous from the notification, the headset and
// the app's own MediaController all go to QueueController. The transport commands stay
// available at the end of a queue, because Next there still means something (WAVE-09, TR-04).
@UnstableApi
class QueueForwardingPlayer(player: Player, private val queue: QueueController) : ForwardingPlayer(player) {
    private val wrappers = HashMap<Player.Listener, Player.Listener>()

    override fun seekToNext() = queue.next()

    override fun seekToNextMediaItem() = queue.next()

    override fun seekToPrevious() = queue.previous()

    override fun seekToPreviousMediaItem() = queue.previous()

    override fun getAvailableCommands(): Player.Commands = withTransport(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun addListener(listener: Player.Listener) {
        val wrapper = TransportCommandsListener(listener)
        wrappers[listener] = wrapper
        super.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        wrappers.remove(listener)?.let { super.removeListener(it) }
    }

    private fun withTransport(commands: Player.Commands): Player.Commands {
        if (mediaItemCount == 0) return commands
        return commands.buildUpon().addAll(*TRANSPORT).build()
    }

    private inner class TransportCommandsListener(private val listener: Player.Listener) :
        Player.Listener by listener {
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            listener.onAvailableCommandsChanged(withTransport(availableCommands))
        }
    }

    private companion object {
        val TRANSPORT =
            intArrayOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )
    }
}
