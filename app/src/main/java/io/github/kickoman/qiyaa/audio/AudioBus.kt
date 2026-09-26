package io.github.kickoman.qiyaa.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AudioBus {
    val equalizer = EqualizerDsp()
    val visualizerTap = VisualizerTap()

    private val mutableSampleRate = MutableStateFlow(0)
    val sampleRate: StateFlow<Int> = mutableSampleRate.asStateFlow()

    private val mutableChannels = MutableStateFlow(0)
    val channels: StateFlow<Int> = mutableChannels.asStateFlow()

    private val mutableBitrateKbps = MutableStateFlow(0)
    val bitrateKbps: StateFlow<Int> = mutableBitrateKbps.asStateFlow()

    @Volatile
    var gainLeft = 1f
        private set

    @Volatile
    var gainRight = 1f
        private set

    fun setFormat(sampleRate: Int, channels: Int) {
        mutableSampleRate.value = sampleRate
        mutableChannels.value = channels
        equalizer.setSampleRate(sampleRate)
        visualizerTap.sampleRate = sampleRate
    }

    fun setBitrate(kbps: Int) {
        mutableBitrateKbps.value = kbps
    }

    fun setBalance(balance: Int) {
        val fraction = balance.coerceIn(-MAX_BALANCE, MAX_BALANCE) / MAX_BALANCE.toFloat()
        gainLeft = if (fraction > 0) 1f - fraction else 1f
        gainRight = if (fraction < 0) 1f + fraction else 1f
    }

    companion object {
        const val MAX_VOLUME = 100
        const val MAX_BALANCE = 100

        fun volumeGain(volume: Int): Float {
            val fraction = volume.coerceIn(0, MAX_VOLUME) / MAX_VOLUME.toFloat()
            return fraction * fraction
        }
    }
}
