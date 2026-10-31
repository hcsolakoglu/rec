package pixl.rec.core.audio

import android.media.MediaRecorder

internal object MicrophoneCapturePolicy {
    fun sources(optIn: Boolean, serviceConnected: Boolean): IntArray =
        if (optIn && serviceConnected) {
            // Android 12's accessibility exception explicitly permits VOICE_RECOGNITION.
            // MIC/DEFAULT do not have this exception during privacy-sensitive capture.
            intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        } else {
            intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        }
}
