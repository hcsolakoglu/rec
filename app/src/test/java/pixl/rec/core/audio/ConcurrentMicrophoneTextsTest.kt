package pixl.rec.core.audio

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import pixl.rec.R

class ConcurrentMicrophoneTextsTest {
    private val context = mockk<Context> {
        every { getString(R.string.concurrent_mic_title) } returns "Concurrent microphone (experimental)"
        every { getString(R.string.concurrent_mic_disconnected, any()) } answers {
            "Assistance off. Turn on “${(secondArg<Array<Any>>())[0]}”."
        }
        every { getString(R.string.concurrent_mic_enable, any()) } answers {
            "Turn on “${(secondArg<Array<Any>>())[0]}”."
        }
    }

    @Test fun hintsNameTheAccessibilityEntryInsteadOfARawPlaceholder() {
        // The recording-start toast once passed the raw resource ID and showed "%1$s".
        assertEquals("Assistance off. Turn on “Concurrent microphone (experimental)”.",
            ConcurrentMicrophoneTexts.disconnectedHint(context))
        assertEquals("Turn on “Concurrent microphone (experimental)”.", ConcurrentMicrophoneTexts.enableHint(context))
    }
}
