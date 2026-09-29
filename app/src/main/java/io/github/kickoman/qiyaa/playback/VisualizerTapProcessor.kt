package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.kickoman.qiyaa.audio.AudioBus
import io.github.kickoman.qiyaa.audio.Pcm16
import java.nio.ByteBuffer

@UnstableApi
class VisualizerTapProcessor(private val audioBus: AudioBus) : BaseAudioProcessor() {
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // With an empty input, replaceOutputBuffer(0) hands back the shared EMPTY_BUFFER (Media3 1.4).
        if (!inputBuffer.hasRemaining()) return
        val channels = inputAudioFormat.channelCount
        val byteCount = inputBuffer.remaining()
        val sampleCount = byteCount / Pcm16.BYTES_PER_SAMPLE
        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
        Pcm16.decode(inputBuffer.asShortBuffer(), sampleCount, scratch)
        audioBus.visualizerTap.write(scratch, sampleCount / channels, channels)

        val output = replaceOutputBuffer(byteCount)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        audioBus.visualizerTap.clear()
    }
}
