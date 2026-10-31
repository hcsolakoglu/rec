package pixl.rec.core.engine

import android.media.MediaMuxer
import io.mockk.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import pixl.rec.core.audio.AudioCaptureManager
import pixl.rec.core.model.RecorderState
import pixl.rec.core.model.RecordingConfig
import pixl.rec.core.storage.MediaStoreWriter
import java.util.concurrent.atomic.AtomicBoolean

/** Engine has no injection seams; private fields stand in for a started two-track session. */
class SeparateAudioLifecycleTest {
    private val capture = mockk<AudioCaptureManager>(relaxed = true)
    private val gameEncoder = mockk<AudioEncoder>(relaxed = true)
    private val micEncoder = mockk<AudioEncoder>(relaxed = true)
    private val video = mockk<VideoEncoder>(relaxed = true)
    private val muxer = mockk<MediaMuxer>(relaxed = true)
    private val writer = mockk<MediaStoreWriter>(relaxed = true)

    private fun engine() = ScreenRecorderEngine(mockk(), RecordingConfig(separateAudioTracks = true), mockk())

    private fun ScreenRecorderEngine.set(name: String, value: Any) {
        ScreenRecorderEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value)
    }

    private fun startedTwoTrackEngine() = engine().apply {
        set("isRecording", AtomicBoolean(true))
        set("isMuxerStarted", AtomicBoolean(true))
        set("audioCaptureManager", capture)
        set("audioEncoder", gameEncoder)
        set("microphoneEncoder", micEncoder)
        set("videoEncoder", video)
        set("mediaMuxer", muxer)
        set("mediaStoreWriter", writer)
        coEvery { gameEncoder.awaitEos(any()) } returns true
        coEvery { micEncoder.awaitEos(any()) } returns true
        coEvery { video.awaitEos(any()) } returns true
    }

    private fun ScreenRecorderEngine.stopAndAwaitEnd(): RecorderState {
        stop()
        return runBlocking {
            withTimeout(5_000) { state.first { it is RecorderState.Finished || it is RecorderState.Error } }
        }
    }

    @Test fun stopQuiescesCaptureBeforeEosThenFinalizesFileOnce() {
        val engine = startedTwoTrackEngine()
        try {
            assertTrue(engine.stopAndAwaitEnd() is RecorderState.Finished)
            // Both EOS waits run concurrently, so each track is ordered against the shared steps separately.
            for (encoder in listOf(gameEncoder, micEncoder)) {
                coVerifyOrder {
                    capture.stop()
                    capture.awaitStopped()
                    encoder.stop()
                    encoder.awaitEos(any())
                    muxer.stop()
                    muxer.release()
                    writer.finish(any(), any())
                    encoder.release()
                }
            }
            verify(exactly = 1) { writer.finish(any(), any()) }
            verify(exactly = 0) { writer.cancel() }
        } finally { engine.release() }
    }

    @Test fun muxerReleaseFailureStillKeepsTheRecording() {
        val engine = startedTwoTrackEngine()
        every { muxer.release() } throws IllegalStateException("release failed")
        try {
            assertTrue(engine.stopAndAwaitEnd() is RecorderState.Finished)
            verify(exactly = 1) { writer.finish(any(), any()) }
            verify(exactly = 0) { writer.cancel() }
        } finally { engine.release() }
    }

    @Test fun shutdownRejectsLatePauseAndResumeWhileTailsAreDraining() {
        val engine = engine()
        try {
            engine.set("isRecording", AtomicBoolean(true))
            engine.set("isStopping", AtomicBoolean(true))
            engine.set("audioCaptureManager", capture)
            engine.pause()
            engine.set("isPaused", AtomicBoolean(true))
            engine.resume()
            verify(exactly = 0) { capture.pause() }
            verify(exactly = 0) { capture.resume() }
        } finally { engine.release() }
    }

    @Test fun unstartedMuxerIsReleasedExactlyOnceOnInitializationAbort() {
        val engine = engine()
        engine.set("mediaMuxer", muxer)
        engine.release(); engine.release()
        verify(exactly = 1) { muxer.release() }
        verify(exactly = 0) { muxer.stop() }
    }
}
