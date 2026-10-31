package pixl.rec.core.audio

import android.content.Context
import android.provider.Settings
import pixl.rec.R
import pixl.rec.service.ConcurrentMicrophoneService

/** Formatted concurrent-microphone hints; keeps every caller passing the Accessibility entry name. */
object ConcurrentMicrophoneTexts {
    /**
     * For a disconnected service. If Android still lists it as enabled, the system stopped it
     * (e.g. a vendor cleaner killed REC) and only turning it off and on binds it again.
     */
    fun disconnectedHint(context: Context): String = context.getString(
        if (isEnabledInSettings(context)) R.string.concurrent_mic_stopped else R.string.concurrent_mic_disconnected,
        context.getString(R.string.concurrent_mic_title)
    )

    fun enableHint(context: Context): String =
        context.getString(R.string.concurrent_mic_enable, context.getString(R.string.concurrent_mic_title))

    private fun isEnabledInSettings(context: Context): Boolean = runCatching {
        isListed(
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            context.packageName,
            ConcurrentMicrophoneService::class.java.name
        )
    }.getOrDefault(false) // Unreadable setting: fall back to the plain "turn it on" hint.

    /** Parses ENABLED_ACCESSIBILITY_SERVICES: ':'-separated "package/class" or "package/.class" entries. */
    internal fun isListed(setting: String?, packageName: String, className: String): Boolean =
        setting.orEmpty().split(':').any { entry ->
            val pkg = entry.substringBefore('/')
            val cls = entry.substringAfter('/', "")
            pkg == packageName && (if (cls.startsWith('.')) pkg + cls else cls) == className
        }
}
