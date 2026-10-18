package io.github.kickoman.qiyaa.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

@UnstableApi
object ControllerCommands {
    val PLAYLIST_EDITS: List<Int> =
        listOf(
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_SET_PLAYLIST_METADATA,
        )

    fun withoutPlaylistEdits(commands: Player.Commands): Player.Commands =
        PLAYLIST_EDITS.fold(commands.buildUpon()) { builder, command -> builder.remove(command) }.build()
}
