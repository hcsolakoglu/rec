package pixl.rec.core.engine

import android.media.MediaMuxer
import io.mockk.*
import org.junit.Test
import pixl.rec.core.audio.AudioCaptureManager
import pixl.rec.core.model.RecordingConfig
import java.util.concurrent.atomic.AtomicBoolean

class SeparateAudioLifecycleTest {
    private fun engine() = ScreenRecorderEngine(mockk(), RecordingConfig(separateAudioTracks = true), mockk())
    private fun set(engine: ScreenRecorderEngine, name: String, value: Any) {
        ScreenRecorderEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(engine, value)
    }

    @Test fun pauseResumeAreIdempotentAndUseOneSharedCaptureManager() {
        val engine = engine()
        val capture = mockk<AudioCaptureManager>(relaxed = true)
        val video = mockk<VideoEncoder>(relaxed = true)
        set(engine, "isRecording", AtomicBoolean(true))
        set(engine, "audioCaptureManager", capture)
        set(engine, "videoEncoder", video)
        try {
            engine.pause(); engine.pause()
            engine.resume(); engine.resume()
            verify(exactly = 1) { capture.pause() }
            verify(exactly = 1) { capture.resume() }
            verify(exactly = 1) { video.pause() }
            verify(exactly = 1) { video.resume() }
        } finally { engine.release() }
    }

    @Test fun shutdownRejectsLatePauseAndResumeWhileTailsAreDraining() {
        val engine = engine()
        val capture = mockk<AudioCaptureManager>(relaxed = true)
        set(engine, "isRecording", AtomicBoolean(true))
        set(engine, "isStopping", AtomicBoolean(true))
        set(engine, "audioCaptureManager", capture)
        try {
            engine.pause()
            set(engine, "isPaused", AtomicBoolean(true))
            engine.resume()
            verify(exactly = 0) { capture.pause() }
            verify(exactly = 0) { capture.resume() }
        } finally { engine.release() }
    }

    @Test fun unstartedMuxerIsReleasedExactlyOnceOnInitializationAbort() {
        val engine = engine()
        val muxer = mockk<MediaMuxer>(relaxed = true)
        set(engine, "mediaMuxer", muxer)
        engine.release(); engine.release()
        verify(exactly = 1) { muxer.release() }
        verify(exactly = 0) { muxer.stop() }
    }
}
