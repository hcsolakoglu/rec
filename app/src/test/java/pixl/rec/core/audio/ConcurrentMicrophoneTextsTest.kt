package pixl.rec.core.audio

import android.content.Context
import android.provider.Settings
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pixl.rec.R
import pixl.rec.service.ConcurrentMicrophoneService

class ConcurrentMicrophoneTextsTest {
    private val context = mockk<Context> {
        every { getString(R.string.concurrent_mic_title) } returns "Concurrent microphone (experimental)"
        every { getString(R.string.concurrent_mic_disconnected, any()) } answers {
            "Assistance off. Turn on “${(secondArg<Array<Any>>())[0]}”."
        }
        every { getString(R.string.concurrent_mic_stopped, any()) } answers {
            "The system stopped “${(secondArg<Array<Any>>())[0]}”. Turn it off and on."
        }
        every { getString(R.string.concurrent_mic_enable, any()) } answers {
            "Turn on “${(secondArg<Array<Any>>())[0]}”."
        }
    }
    private val service = ConcurrentMicrophoneService::class.java.name

    @Test fun hintsNameTheAccessibilityEntryInsteadOfARawPlaceholder() {
        // The recording-start toast once passed the raw resource ID and showed "%1$s".
        assertEquals("Assistance off. Turn on “Concurrent microphone (experimental)”.",
            ConcurrentMicrophoneTexts.disconnectedHint(context))
        assertEquals("Turn on “Concurrent microphone (experimental)”.", ConcurrentMicrophoneTexts.enableHint(context))
    }

    @Test fun serviceStillEnabledButDisconnectedAsksForAnOffOnToggle() {
        // A vendor cleaner killing REC leaves the service enabled in settings, crashed and unbound.
        every { context.contentResolver } returns mockk(relaxed = true)
        every { context.packageName } returns "pixl.rec.hcsolakoglu"
        mockkStatic(Settings.Secure::class)
        try {
            every { Settings.Secure.getString(any(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) } returns
                "com.other/.Reader:pixl.rec.hcsolakoglu/$service"
            assertEquals("The system stopped “Concurrent microphone (experimental)”. Turn it off and on.",
                ConcurrentMicrophoneTexts.disconnectedHint(context))
            for (setting in listOf(null, "", "com.other/.Reader")) {
                every { Settings.Secure.getString(any(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) } returns setting
                assertEquals("Assistance off. Turn on “Concurrent microphone (experimental)”.",
                    ConcurrentMicrophoneTexts.disconnectedHint(context))
            }
        } finally {
            unmockkStatic(Settings.Secure::class)
        }
    }

    @Test fun enabledServicesSettingMatchesOnlyThisPackageAndClass() {
        val pkg = "pixl.rec.hcsolakoglu"
        assertTrue(ConcurrentMicrophoneTexts.isListed("a.b/.C:$pkg/$service", pkg, service))
        assertTrue(ConcurrentMicrophoneTexts.isListed("pixl.rec/.service.ConcurrentMicrophoneService", "pixl.rec", service))
        assertFalse(ConcurrentMicrophoneTexts.isListed(null, pkg, service))
        assertFalse(ConcurrentMicrophoneTexts.isListed("", pkg, service))
        // Upstream REC uses the same class name under another package.
        assertFalse(ConcurrentMicrophoneTexts.isListed("pixl.rec/$service", pkg, service))
        assertFalse(ConcurrentMicrophoneTexts.isListed("$pkg/${service}2", pkg, service))
        assertFalse(ConcurrentMicrophoneTexts.isListed(pkg, pkg, service))
    }
}
