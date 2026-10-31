package pixl.rec.core.storage

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import pixl.rec.core.model.AudioSource
import pixl.rec.core.model.RecordingConfig

class ConfigPreferencesAudioTest {
    private val values = mutableMapOf<String, Any?>()
    private val context = mockk<Context>().also { context ->
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.contains(any()) } answers { firstArg<String>() in values }
        every { prefs.getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
        every { prefs.getInt(any(), any()) } answers { values[firstArg()] as? Int ?: secondArg() }
        every { prefs.getFloat(any(), any()) } answers { values[firstArg()] as? Float ?: secondArg() }
        every { prefs.getBoolean(any(), any()) } answers { values[firstArg()] as? Boolean ?: secondArg() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); editor }
        every { editor.putInt(any(), any()) } answers { values[firstArg()] = secondArg<Int>(); editor }
        every { editor.putFloat(any(), any()) } answers { values[firstArg()] = secondArg<Float>(); editor }
        every { editor.putBoolean(any(), any()) } answers { values[firstArg()] = secondArg<Boolean>(); editor }
        every { editor.apply() } returns Unit
    }

    @Test fun audioOptionsSurviveSaveAndLoad() {
        val saved = RecordingConfig(
            audioSource = AudioSource.INTERNAL_AND_MIC,
            audioBitrate = 192_000,
            concurrentMicrophone = true,
            separateAudioTracks = true,
            gameTrackBitrate = 128_000,
            microphoneTrackBitrate = 96_000
        )
        ConfigPreferences.saveConfig(context, saved)
        val loaded = ConfigPreferences.loadConfig(context, RecordingConfig())
        assertEquals(saved.audioBitrate, loaded.audioBitrate)
        assertEquals(true, loaded.concurrentMicrophone)
        assertEquals(true, loaded.separateAudioTracks)
        assertEquals(96_000, loaded.microphoneTrackBitrate)
        assertEquals(128_000, loaded.gameTrackBitrate)
    }

    @Test fun upgradeKeepsGameTrackBitrateFromSharedSetting() {
        // 0.9.0-audio.1/.2 stored only audio_bitrate, which then also drove the game track.
        for ((stored, expected) in listOf(192_000 to 192_000, 512_000 to 320_000)) {
            ConfigPreferences.saveConfig(context, RecordingConfig(separateAudioTracks = true, audioBitrate = stored))
            values.remove("game_track_bitrate")
            val loaded = ConfigPreferences.loadConfig(context, RecordingConfig())
            assertEquals(stored, loaded.audioBitrate)
            assertEquals(expected, loaded.gameTrackBitrate)
        }
    }

    @Test fun preferencesSavedBeforeTheseOptionsLoadAsOff() {
        ConfigPreferences.saveConfig(context, RecordingConfig(audioBitrate = 128_000))
        listOf("concurrent_microphone", "separate_audio_tracks", "microphone_track_bitrate").forEach(values::remove)
        val loaded = ConfigPreferences.loadConfig(context, RecordingConfig())
        assertEquals(false, loaded.concurrentMicrophone)
        assertEquals(false, loaded.separateAudioTracks)
        assertEquals(RecordingConfig().microphoneTrackBitrate, loaded.microphoneTrackBitrate)
        assertEquals(128_000, loaded.audioBitrate)
    }

    @Test fun outOfRangeStoredTrackBitratesAreClamped() {
        ConfigPreferences.saveConfig(context, RecordingConfig())
        values["microphone_track_bitrate"] = 1_000_000
        values["game_track_bitrate"] = 1_000
        val loaded = ConfigPreferences.loadConfig(context, RecordingConfig())
        assertEquals(320_000, loaded.microphoneTrackBitrate)
        assertEquals(64_000, loaded.gameTrackBitrate)
    }
}
