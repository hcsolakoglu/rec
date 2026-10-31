package pixl.rec.core.engine

import android.media.MediaCodec
import android.media.MediaFormat
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import pixl.rec.core.model.RecordingConfig
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioEncoderLifecycleTest {
    @Test fun bothEncodersFlushTailBeforeEosAndReleaseIdempotently() = runBlocking {
        mockkStatic(MediaCodec::class, MediaFormat::class)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val encoders = mutableListOf<AudioEncoder>()
        try {
            every { MediaFormat.createAudioFormat(any(), any(), any()) } returns mockk(relaxed = true)
            val codecs = List(2) { mockk<MediaCodec>(relaxed = true) }
            every { MediaCodec.createEncoderByType(any()) } returnsMany codecs
            val tails = AtomicInteger()
            for (codec in codecs) {
                val eosQueued = CountDownLatch(1)
                val outputNumber = AtomicInteger()
                every { codec.dequeueInputBuffer(any()) } returns 0
                every { codec.queueInputBuffer(0, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM) } answers {
                    eosQueued.countDown()
                }
                every { codec.dequeueOutputBuffer(any(), any()) } answers {
                    check(eosQueued.await(2, TimeUnit.SECONDS))
                    val info = firstArg<MediaCodec.BufferInfo>()
                    val tail = outputNumber.getAndIncrement() == 0
                    info.offset = 0
                    info.size = if (tail) 4 else 0
                    info.presentationTimeUs = 21333L
                    info.flags = if (tail) 0 else MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    0
                }
                every { codec.getOutputBuffer(0) } answers { ByteBuffer.allocate(4) }
                val encoder = AudioEncoder(RecordingConfig(), object : AudioEncoder.OutputListener {
                    override fun onAudioFormatChanged(format: MediaFormat) = Unit
                    override fun onAudioSampleData(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
                        assertEquals(21333L, bufferInfo.presentationTimeUs)
                        tails.incrementAndGet()
                    }
                    override fun onAudioError(e: Throwable) { throw AssertionError(e) }
                })
                encoders += encoder
                encoder.prepare()
                encoder.start(scope)
            }
            encoders.forEach { it.stop(); it.stop() }
            for (encoder in encoders) assertTrue("Encoder must drain tail and EOS", encoder.awaitEos(2000))
            assertEquals(2, tails.get())
            encoders.forEach { it.release(); it.release() }
            codecs.forEach { codec ->
                verify(exactly = 1) { codec.release() }
                verify(exactly = 1) { codec.queueInputBuffer(0, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
            }
        } finally {
            encoders.forEach { it.release() }
            scope.cancel()
            unmockkStatic(MediaCodec::class, MediaFormat::class)
        }
    }
}
