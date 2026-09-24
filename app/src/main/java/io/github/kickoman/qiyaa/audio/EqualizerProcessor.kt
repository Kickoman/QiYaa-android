package io.github.kickoman.qiyaa.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * ExoPlayer audio processor: 16-bit PCM → float → [EqualizerDsp] → balance → 16-bit PCM.
 * Runs on the playback thread; settings arrive through the DSP's volatile coefficients.
 */
@UnstableApi
class EqualizerProcessor(private val bus: AudioBus) : BaseAudioProcessor() {
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        bus.setFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // An empty input would make replaceOutputBuffer(0) hand back the shared EMPTY_BUFFER.
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val bytes = inputBuffer.remaining()
        val samples = bytes / 2
        val frames = samples / channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        val input = inputBuffer.asShortBuffer()
        for (i in 0 until samples) scratch[i] = input.get(i) / 32768f

        bus.eq.process(scratch, frames, channels)
        if (channels >= 2 && (bus.gainLeft != 1f || bus.gainRight != 1f)) {
            val gl = bus.gainLeft
            val gr = bus.gainRight
            var p = 0
            for (i in 0 until frames) {
                scratch[p] *= gl
                scratch[p + 1] *= gr
                p += channels
            }
        }

        val out = replaceOutputBuffer(bytes)
        for (i in 0 until samples) {
            val v = (scratch[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out.putShort(v.toShort())
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        bus.eq.reset()
    }

    override fun onReset() {
        bus.eq.reset()
    }
}

/** Pass-through processor that copies PCM into the [VisTap] ring for the visualizer. */
@UnstableApi
class VisTapProcessor(private val bus: AudioBus) : BaseAudioProcessor() {
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // An empty input would make replaceOutputBuffer(0) hand back the shared EMPTY_BUFFER.
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val bytes = inputBuffer.remaining()
        val samples = bytes / 2
        val frames = samples / channels
        if (scratch.size < samples) scratch = FloatArray(samples)
        val input = inputBuffer.asShortBuffer()
        for (i in 0 until samples) scratch[i] = input.get(i) / 32768f
        bus.visTap.write(scratch, frames, channels)

        val out = replaceOutputBuffer(bytes)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        bus.visTap.clear()
    }
}
