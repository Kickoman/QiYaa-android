package io.github.kickoman.qiyaa.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import io.github.kickoman.qiyaa.R

@UnstableApi
object NotificationButtons {
    const val ACTION_LIKE = "io.github.kickoman.qiyaa.LIKE"
    const val ACTION_DISLIKE = "io.github.kickoman.qiyaa.DISLIKE"

    val like = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
    val dislike = SessionCommand(ACTION_DISLIKE, Bundle.EMPTY)

    fun withCustomCommands(commands: SessionCommands): SessionCommands =
        commands.buildUpon().add(like).add(dislike).build()

    fun layout(context: Context, liked: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(
            if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
        )
            .setSessionCommand(like)
            .setDisplayName(
                context.getString(if (liked) R.string.notification_unlike else R.string.notification_like),
            )
            .build(),
        CommandButton.Builder(CommandButton.ICON_THUMB_DOWN_UNFILLED)
            .setSessionCommand(dislike)
            .setDisplayName(context.getString(R.string.notification_dislike))
            .build(),
    )
}
