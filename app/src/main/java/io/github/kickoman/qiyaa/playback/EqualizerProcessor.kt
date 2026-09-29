package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.audio.Pcm16
import java.nio.ByteBuffer

@UnstableApi
class EqualizerProcessor(private val audioBus: AudioBus) : BaseAudioProcessor() {
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        audioBus.setFormat(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // With an empty input, replaceOutputBuffer(0) hands back the shared EMPTY_BUFFER (Media3 1.4).
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val byteCount = inputBuffer.remaining()
        val sampleCount = byteCount / Pcm16.BYTES_PER_SAMPLE
        val frameCount = sampleCount / channels
        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
        Pcm16.decode(inputBuffer.asShortBuffer(), sampleCount, scratch)

        audioBus.equalizer.process(scratch, frameCount, channels)
        applyBalance(frameCount, channels)

        val output = replaceOutputBuffer(byteCount)
        Pcm16.encode(scratch, sampleCount, output)
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun applyBalance(frameCount: Int, channels: Int) {
        val gainLeft = audioBus.gainLeft
        val gainRight = audioBus.gainRight
        if (channels < 2 || (gainLeft == 1f && gainRight == 1f)) return
        var position = 0
        for (i in 0 until frameCount) {
            scratch[position] *= gainLeft
            scratch[position + 1] *= gainRight
            position += channels
        }
    }

    override fun onFlush() {
        audioBus.equalizer.reset()
    }

    override fun onReset() {
        audioBus.equalizer.reset()
    }
}
