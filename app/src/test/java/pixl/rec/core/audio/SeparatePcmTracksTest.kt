package pixl.rec.core.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SeparatePcmTracksTest {
    private fun pcm(vararg values: Int): ByteArray = ByteBuffer.allocate(values.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()

    @Test fun unityGainBorrowsOriginalStereoBuffersWithoutMixing() {
        val game = pcm(1000, -2000, 3000, -4000)
        val mic = pcm(5000, 6000, -7000, -8000)
        var calls = 0
        val tracks = SeparatePcmTracks(8, 1f, 1f) { g, m, length, pts ->
            assertSame(game, g)
            assertSame(mic, m)
            assertEquals(8, length)
            assertEquals(21333L, pts)
            calls++
        }
        tracks.emit(game, mic, 8, 21333L)
        assertEquals(1, calls)
        assertArrayEquals(pcm(1000, -2000, 3000, -4000), game)
        assertArrayEquals(pcm(5000, 6000, -7000, -8000), mic)
    }

    @Test fun independentGainsClipSafelyAndReuseScratchWithoutMutatingInput() {
        val game = pcm(20000, -20000)
        val mic = pcm(24000, -24000)
        var firstGame: ByteArray? = null
        var firstMic: ByteArray? = null
        val tracks = SeparatePcmTracks(4, 0.5f, 2f) { g, m, _, _ ->
            assertArrayEquals(pcm(10000, -10000), g)
            // REC's existing soft limiter saturates symmetrically at +/-32767.
            assertArrayEquals(pcm(32767, -32767), m)
            if (firstGame != null) { assertSame(firstGame, g); assertSame(firstMic, m) }
            firstGame = g
            firstMic = m
        }
        repeat(1000) { tracks.emit(game, mic, 4, it * 21333L) }
        assertArrayEquals(pcm(20000, -20000), game)
        assertArrayEquals(pcm(24000, -24000), mic)
    }

    @Test fun missingMicrophoneStaysSilentThenRecoversWithoutContaminatingGame() {
        val game = pcm(1500, -1500)
        val mic = pcm(700, -700)
        val silence = ByteArray(4)
        var expectedMic = silence
        var expectedPts = 0L
        val tracks = SeparatePcmTracks(4, 1f, 1f) { g, m, count, pts ->
            assertSame(game, g)
            assertSame(expectedMic, m)
            assertEquals(4, count)
            assertEquals(expectedPts, pts)
        }
        for (buffer in listOf(silence, mic, silence, mic)) {
            expectedMic = buffer
            tracks.emit(game, buffer, 4, expectedPts)
            expectedPts += 21333L
        }
        assertArrayEquals(ByteArray(4), silence)
    }
}
