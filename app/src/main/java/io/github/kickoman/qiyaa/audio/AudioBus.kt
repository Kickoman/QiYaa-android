package io.github.kickoman.qiyaa.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live audio facts published by the processors for the UI readouts. */
class AudioBus {
    val eq = EqualizerDsp()
    val visTap = VisTap()

    private val _sampleRate = MutableStateFlow(0)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private val _channels = MutableStateFlow(0)
    val channels: StateFlow<Int> = _channels.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0)
    val bitrateKbps: StateFlow<Int> = _bitrateKbps.asStateFlow()

    fun setFormat(sampleRate: Int, channels: Int) {
        _sampleRate.value = sampleRate
        _channels.value = channels
        eq.setSampleRate(sampleRate)
        visTap.sampleRate = sampleRate
    }

    fun setBitrate(kbps: Int) {
        _bitrateKbps.value = kbps
    }

    /** Balance -100..100 → per-channel linear gains; the opposite channel is attenuated. */
    @Volatile
    var gainLeft = 1f
        private set

    @Volatile
    var gainRight = 1f
        private set

    fun setBalance(balance: Int) {
        val b = balance.coerceIn(-100, 100) / 100f
        gainLeft = if (b > 0) 1f - b else 1f
        gainRight = if (b < 0) 1f + b else 1f
    }

    companion object {
        /** Winamp-like volume curve: 0..100 → (v/100)². */
        fun volumeGain(volume: Int): Float {
            val v = volume.coerceIn(0, 100) / 100f
            return v * v
        }
    }
}
