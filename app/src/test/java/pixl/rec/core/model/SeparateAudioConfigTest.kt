package pixl.rec.core.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pixl.rec.core.storage.ConfigSerializer

class SeparateAudioConfigTest {
    @Test fun oldProfilesStayMixedAndRetainTheirAudioBitrate() {
        val old = JSONObject(ConfigSerializer.exportToJson(RecordingConfig(audioBitrate = 192_000)))
        old.getJSONObject("audio").apply {
            remove("separate_audio_tracks")
            remove("microphone_track_bitrate")
        }
        val restored = ConfigSerializer.importFromJson(old.toString()).getOrThrow()
        assertFalse(restored.separateAudioTracks)
        assertEquals(192_000, restored.audioBitrate)
        assertEquals(256_000, restored.microphoneTrackBitrate)
    }

    @Test fun everyAudioModeRoundTripsAndOnlyBothSplits() {
        for (mode in AudioSource.entries) for (split in listOf(false, true)) {
            val config = RecordingConfig(width = 1600, height = 720, audioSource = mode,
                concurrentMicrophone = true, separateAudioTracks = split,
                audioBitrate = 192_000, microphoneTrackBitrate = 96_000)
            val restored = ConfigSerializer.importFromJson(ConfigSerializer.exportToJson(config)).getOrThrow()
            assertEquals(config, restored)
            assertEquals(split && mode == AudioSource.INTERNAL_AND_MIC, restored.usesSeparateAudioTracks)
        }
    }

    @Test fun streamingKeepsMixedAudioAndDoesNotChangeSavedPreference() {
        val config = RecordingConfig(separateAudioTracks = true, concurrentMicrophone = true)
        assertSame(config, config.forAudioOutput(streaming = false, localArchive = true))
        for (local in listOf(false, true)) {
            val effective = config.forAudioOutput(streaming = true, localArchive = local)
            assertEquals(config.copy(separateAudioTracks = false), effective)
            assertTrue(config.separateAudioTracks)
        }
        assertFalse(config.forAudioOutput(streaming = false, localArchive = false).usesSeparateAudioTracks)
    }

    @Test fun storageEstimateIncludesExactlyTwoSelectedAudioBitrates() {
        val split = RecordingConfig(videoBitrate = 6_000_000, separateAudioTracks = true,
            audioBitrate = 192_000, microphoneTrackBitrate = 96_000)
        assertEquals(288_000, split.totalAudioBitrate)
        assertEquals(6.288f, split.totalBitrateMbps, 0.00001f)
        assertEquals(6_288_000 / 8.0 / (1024 * 1024) * 60, split.estimatedMbPerMinute, 0.00001)
        assertEquals(192_000, split.copy(separateAudioTracks = false).totalAudioBitrate)
        assertEquals(192_000, split.copy(audioSource = AudioSource.MIC_ONLY).totalAudioBitrate)
        assertEquals(0, split.copy(audioSource = AudioSource.MUTE).totalAudioBitrate)
    }

    @Test fun importedMicrophoneBitrateIsBounded() {
        for ((input, expected) in listOf(-1 to 64_000, Int.MAX_VALUE to 320_000)) {
            val json = JSONObject(ConfigSerializer.exportToJson(RecordingConfig()))
            json.getJSONObject("audio").put("microphone_track_bitrate", input)
            assertEquals(expected, ConfigSerializer.importFromJson(json.toString()).getOrThrow().microphoneTrackBitrate)
        }
    }
}
