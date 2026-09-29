package io.github.kickoman.qiyaa.yandex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SessionState {
    data object LoggedOut : SessionState

    data object Connecting : SessionState

    data class Online(val account: Account) : SessionState

    data object Offline : SessionState

    data object Expired : SessionState
}

interface AccountGateway {
    var token: String
    val tokenRejections: Flow<HttpException>

    suspend fun connectAccount(): Account

    suspend fun preloadLikes()

    fun forget()
}

class Session(
    private val gateway: AccountGateway,
    private val connectivity: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    @Volatile
    private var job: Job? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            gateway.tokenRejections.collect {
                if (mutableState.value != SessionState.LoggedOut) expire()
            }
        }
    }

    fun start() {
        job?.cancel()
        if (gateway.token.isEmpty()) {
            mutableState.value = SessionState.LoggedOut
            return
        }
        job = scope.launch { keepConnected(online = null) }
    }

    suspend fun signIn(token: String): Account {
        job?.cancel()
        mutableState.value = SessionState.LoggedOut
        gateway.token = token
        val account =
            try {
                gateway.connectAccount()
            } catch (failed: Exception) {
                gateway.token = ""
                throw failed
            }
        mutableState.value = SessionState.Online(account)
        job = scope.launch { keepConnected(online = account) }
        return account
    }

    fun signOut() {
        job?.cancel()
        gateway.forget()
        mutableState.value = SessionState.LoggedOut
    }

    private fun expire() {
        job?.cancel()
        mutableState.value = SessionState.Expired
    }

    private suspend fun keepConnected(online: Account?) {
        var account = online
        var retryMs = FIRST_RETRY_MS
        while (true) {
            if (account != null) {
                mutableState.value = SessionState.Online(account)
                preloadLikes()
                connectivity.first { !it }
                account = null
                retryMs = FIRST_RETRY_MS
            }
            if (!connectivity.first()) {
                mutableState.value = SessionState.Offline
                connectivity.first { it }
                retryMs = FIRST_RETRY_MS
            }
            mutableState.value = SessionState.Connecting
            try {
                account = gateway.connectAccount()
            } catch (failed: YandexException) {
                if (failed.rejectsToken()) {
                    mutableState.value = SessionState.Expired
                    return
                }
                mutableState.value = SessionState.Offline
                val lost = withTimeoutOrNull(retryMs) { connectivity.first { !it } }
                retryMs = if (lost != null) FIRST_RETRY_MS else minOf(retryMs * 2, MAX_RETRY_MS)
            }
        }
    }

    private suspend fun preloadLikes() {
        try {
            gateway.preloadLikes()
        } catch (ignored: YandexException) {
            // Likes are re-read with the library lists; a rejected token arrives through tokenRejections.
        }
    }

    private fun YandexException.rejectsToken(): Boolean =
        this is AuthException || (this is HttpException && isTokenRejected)

    companion object {
        const val FIRST_RETRY_MS = 2_000L
        const val MAX_RETRY_MS = 60_000L
    }
}
