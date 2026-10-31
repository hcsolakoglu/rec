package pixl.rec.core.engine

import android.media.MediaFormat
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class MuxerTrackRegistryTest {
    @Test fun everyFormatArrivalOrderKeepsGameBeforeMicAndWaitsForAllTracks() {
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val formats = List(3) { mockk<MediaFormat>() }
            val added = mutableListOf<MediaFormat>()
            val registry = MuxerTrackRegistry(2)
            order.forEachIndexed { arrival, track ->
                registry.onFormat(track, formats[track]) { added.add(it); added.size + 10 }
                assertEquals(arrival == 2, registry.isReady)
            }
            assertEquals(formats, added)
            // Real muxer indices need not equal logical IDs, including queued startup samples.
            assertEquals(11, registry.indexOf(0))
            assertEquals(12, registry.indexOf(1))
            assertEquals(13, registry.indexOf(2))
            registry.onFormat(2, formats[2]) { error("Duplicate track registration") }
            registry.reset()
            assertFalse(registry.isReady)
            assertEquals(-1, registry.indexOf(2))
        }
    }

    @Test fun mixedAndMutedModesWaitOnlyForTheirConfiguredTracks() {
        for (audioCount in 0..1) {
            val registry = MuxerTrackRegistry(audioCount)
            for (track in 0..audioCount) {
                assertFalse(registry.isReady)
                registry.onFormat(track, mockk()) { track }
            }
            assertTrue(registry.isReady)
        }
    }

    @Test fun pendingBufferKeepsOriginalBoundAndAddsHeadroomOnlyForSecondAudioTrack() {
        assertEquals(120, MuxerTrackRegistry(0).pendingSampleLimit)
        assertEquals(120, MuxerTrackRegistry(1).pendingSampleLimit)
        assertEquals(180, MuxerTrackRegistry(2).pendingSampleLimit)
    }
}
