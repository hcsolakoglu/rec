package pixl.rec.core.audio

import android.content.Context
import pixl.rec.R

/** Formatted concurrent-microphone hints; keeps every caller passing the Accessibility entry name. */
object ConcurrentMicrophoneTexts {
    fun disconnectedHint(context: Context): String =
        context.getString(R.string.concurrent_mic_disconnected, context.getString(R.string.concurrent_mic_title))

    fun enableHint(context: Context): String =
        context.getString(R.string.concurrent_mic_enable, context.getString(R.string.concurrent_mic_title))
}
