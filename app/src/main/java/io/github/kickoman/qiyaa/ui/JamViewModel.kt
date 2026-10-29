package io.github.kickoman.qiyaa.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kickoman.qiyaa.R
import io.github.kickoman.qiyaa.appGraph
import io.github.kickoman.qiyaa.jam.JamNames
import io.github.kickoman.qiyaa.jam.JamOrder
import io.github.kickoman.qiyaa.jam.JamSettingsPatch
import io.github.kickoman.qiyaa.playback.JamHostEvent
import io.github.kickoman.qiyaa.yandex.signedIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class JamUi(val hostName: String = "")

class JamViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    private val host = graph.jamHost
    private val library = graph.library
    val state = host.state
    val settings = graph.settings

    private val mutableUi = MutableStateFlow(JamUi())
    val ui: StateFlow<JamUi> = mutableUi.asStateFlow()

    private val mutableNotices = MutableSharedFlow<String>(extraBufferCapacity = NOTICE_BUFFER)
    val notices: SharedFlow<String> = mutableNotices.asSharedFlow()

    init {
        viewModelScope.launch {
            host.events.collect { if (it !is JamHostEvent.AddRefused) notice(it.render(getApplication())) }
        }
        viewModelScope.launch {
            library.account.collect { account ->
                val name = JamNames.prefill(account.displayName.ifEmpty { account.login })
                mutableUi.update { if (it.hostName.isEmpty()) it.copy(hostName = name) else it }
            }
        }
    }

    val isConfigured: Boolean
        get() = settings.jamServer.value.isNotBlank()

    fun setHostName(name: String) = mutableUi.update { it.copy(hostName = JamNames.cut(name)) }

    fun start() {
        if (!graph.session.state.value.signedIn) {
            notice(string(R.string.jam_needs_login))
            return
        }
        if (graph.jamGuest.state.value.phase.isSession) {
            notice(string(R.string.jam_host_while_guest))
            return
        }
        if (!isConfigured) {
            notice(string(R.string.jam_not_configured))
            return
        }
        if (!host.create(mutableUi.value.hostName)) notice(string(R.string.jam_cannot_start))
    }

    fun cancelStart() = host.cancelCreate()

    fun end() = host.end()

    fun continueStored() = host.continueStored()

    fun discardStored() = host.discardStored()

    fun setOrder(order: JamOrder) = changeSettings(JamSettingsPatch(order = order))

    fun setGuestsCanSkip(allowed: Boolean) = changeSettings(JamSettingsPatch(guestsCanSkip = allowed))

    fun setJoinOpen(open: Boolean) = changeSettings(JamSettingsPatch(joinOpen = open))

    fun rotateLink() = sent(host.rotateLink())

    fun kick(publicId: String) = sent(host.kick(publicId))

    fun setServer(url: String) = settings.setJamServer(url)

    fun setWaveFeedback(enabled: Boolean) = settings.setJamWaveFeedback(enabled)

    fun setShareAudio(enabled: Boolean) = settings.setJamShareAudio(enabled)

    private fun changeSettings(patch: JamSettingsPatch) = sent(host.changeSettings(patch))

    private fun sent(accepted: Boolean, doneText: Int? = null) {
        when {
            !accepted -> notice(string(R.string.jam_no_connection))
            doneText != null -> notice(string(doneText))
        }
    }

    fun say(text: String) = notice(text)

    private fun notice(text: String) {
        mutableNotices.tryEmit(text)
    }

    private fun string(id: Int): String = getApplication<Application>().getString(id)

    companion object {
        private const val NOTICE_BUFFER = 8
    }
}
