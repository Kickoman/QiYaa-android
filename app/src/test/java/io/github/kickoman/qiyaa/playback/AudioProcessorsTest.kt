package io.github.kickoman.qiyaa.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import io.github.kickoman.qiyaa.audio.AudioBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProcessorsTest {
    private val audioBus = AudioBus()
    private val processors = listOf(EqualizerProcessor(audioBus), VisualizerTapProcessor(audioBus))

    @Test
    fun `the processors step aside for audio that is not 16-bit PCM instead of failing playback`() {
        for (encoding in listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_24BIT)) {
            for (processor in processors) {
                val output = processor.configure(AudioProcessor.AudioFormat(48_000, 2, encoding))
                assertEquals(AudioProcessor.AudioFormat.NOT_SET, output)
                processor.flush()
                assertFalse("${processor.javaClass.simpleName} active for $encoding", processor.isActive)
            }
        }
    }

    @Test
    fun `the processors handle 16-bit PCM`() {
        val format = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        for (processor in processors) {
            assertEquals(format, processor.configure(format))
            processor.flush()
            assertTrue(processor.isActive)
        }
        assertEquals(44_100, audioBus.sampleRate.value)
    }
}
