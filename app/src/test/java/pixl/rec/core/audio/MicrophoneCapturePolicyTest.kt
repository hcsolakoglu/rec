package pixl.rec.core.audio

import android.media.MediaRecorder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.json.JSONObject
import pixl.rec.core.model.AudioSource
import pixl.rec.core.model.RecordingConfig
import pixl.rec.core.storage.ConfigSerializer

class MicrophoneCapturePolicyTest {
    @Test fun normalModePreservesRecognitionThenMic() {
        assertArrayEquals(intArrayOf(6, 1), MicrophoneCapturePolicy.sources(false, false))
        assertArrayEquals(intArrayOf(6, 1), MicrophoneCapturePolicy.sources(false, true))
    }

    @Test fun absentOrRevokedServiceUsesNormalPolicy() {
        assertArrayEquals(intArrayOf(6, 1), MicrophoneCapturePolicy.sources(true, false))
    }

    @Test fun connectedOptInUsesDocumentedAccessibilitySource() {
        assertArrayEquals(intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION),
            MicrophoneCapturePolicy.sources(true, true))
    }

    @Test fun disconnectAndReconnectDoNotChangeSelectionState() {
        for (connected in listOf(true, false, true, false, true)) {
            assertEquals(if (connected) 1 else 2,
                MicrophoneCapturePolicy.sources(true, connected).size)
        }
    }

    @Test fun optInRoundTripsForAllExistingAudioModes() {
        for (mode in AudioSource.entries) {
            // Use the requested aligned profile; upstream import rounds 1080 up to 1088.
            val config = RecordingConfig(width = 1600, height = 720,
                audioSource = mode, concurrentMicrophone = true)
            val restored = ConfigSerializer.importFromJson(ConfigSerializer.exportToJson(config)).getOrThrow()
            assertEquals(config, restored)
        }
    }

    @Test fun oldProfilesDoNotOptInToSpecialAccess() {
        val config = RecordingConfig()
        val json = JSONObject(ConfigSerializer.exportToJson(config))
        json.getJSONObject("audio").remove("concurrent_microphone")
        assertFalse(ConfigSerializer.importFromJson(json.toString()).getOrThrow().concurrentMicrophone)
        assertFalse(config.concurrentMicrophone)
    }
}
