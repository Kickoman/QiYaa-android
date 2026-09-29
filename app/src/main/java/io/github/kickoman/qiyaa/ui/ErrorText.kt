package io.github.kickoman.qiyaa.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.yandex.ErrorKind

const val LOG_TAG = "QiYaa"

data class TextRes(val id: Int, val args: List<Any> = emptyList())

fun ErrorKind.text(): TextRes = when (this) {
    ErrorKind.NoNetwork -> TextRes(R.string.error_no_network)
    ErrorKind.TokenRejected -> TextRes(R.string.error_token_rejected)
    is ErrorKind.ServerError -> TextRes(R.string.error_server, listOf(status))
    ErrorKind.Malformed -> TextRes(R.string.error_malformed)
    ErrorKind.SignInRefused -> TextRes(R.string.error_sign_in_refused)
    ErrorKind.CodeExpired -> TextRes(R.string.error_code_expired)
    ErrorKind.TrackUnplayable -> TextRes(R.string.error_track_unplayable)
    ErrorKind.Unknown -> TextRes(R.string.error_unknown)
}

fun ErrorKind.render(context: Context): String = text().let {
    context.getString(it.id, *it.args.toTypedArray())
}

@Composable
fun ErrorKind.render(): String = text().let { stringResource(it.id, *it.args.toTypedArray()) }
