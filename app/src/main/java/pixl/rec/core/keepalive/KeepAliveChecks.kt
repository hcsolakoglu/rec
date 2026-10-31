package pixl.rec.core.keepalive

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import pixl.rec.core.audio.ConcurrentMicrophoneTexts

/** Why the system last closed REC, read from Android's own exit records (Android 11+). */
enum class KillCause { RECENTS_CLEAN, GAME_CLEAN, BACKGROUND_CLEAN, LOW_MEMORY }

data class LastKill(val cause: KillCause, val timestampMs: Long)

/**
 * What Android lets an app check about being kept alive. Vendor-only switches (MIUI Autostart,
 * recents lock, Game Turbo exceptions) have no public API, so they are not part of the status.
 */
data class KeepAliveStatus(
    val batteryUnrestricted: Boolean,
    val backgroundRestricted: Boolean,
    val assistanceEnabledInSettings: Boolean,
    val lastKill: LastKill?,
    val isXiaomi: Boolean
)

object KeepAliveChecks {
    private const val TAG = "KeepAliveChecks"
    private const val EXIT_RECORDS = 16
    private const val MAX_KILL_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Binder calls only; call off the main thread. */
    fun read(context: Context): KeepAliveStatus {
        val pkg = context.packageName
        val power = context.getSystemService(PowerManager::class.java)
        val activity = context.getSystemService(ActivityManager::class.java)
        return KeepAliveStatus(
            batteryUnrestricted = runCatching { power?.isIgnoringBatteryOptimizations(pkg) == true }.getOrDefault(false),
            backgroundRestricted = runCatching { activity?.isBackgroundRestricted == true }.getOrDefault(false),
            assistanceEnabledInSettings = ConcurrentMicrophoneTexts.isEnabledInSettings(context),
            lastKill = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) lastKill(activity, pkg, System.currentTimeMillis()) else null,
            isXiaomi = isXiaomi(Build.MANUFACTURER, Build.BRAND)
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun lastKill(activity: ActivityManager?, pkg: String, nowMs: Long): LastKill? = runCatching {
        // Newest first; the first recent kill of a working REC is the one worth explaining.
        activity?.getHistoricalProcessExitReasons(pkg, 0, EXIT_RECORDS).orEmpty().firstNotNullOfOrNull { info ->
            classifyExit(info.reason, info.description)
                ?.takeIf { isRelevant(info.importance, info.timestamp, nowMs) }
                ?.let { LastKill(it, info.timestamp) }
        }
    }.onFailure { Log.w(TAG, "Exit records unavailable", it) }.getOrNull()

    /**
     * Maps an exit record to a kill the user can prevent. App updates, crashes, ANRs, normal exits and
     * Android's routine trimming of idle cached processes return null.
     */
    internal fun classifyExit(reason: Int, description: String?): KillCause? {
        val text = description.orEmpty()
        return when {
            reason == ApplicationExitInfo.REASON_LOW_MEMORY -> KillCause.LOW_MEMORY
            // Stock Android logs a swipe from recents as a user request named "remove task".
            reason == ApplicationExitInfo.REASON_USER_REQUESTED && text.contains("remove task", ignoreCase = true) ->
                KillCause.RECENTS_CLEAN
            reason != ApplicationExitInfo.REASON_OTHER -> null
            // MIUI names: SwipeUpClean (card swiped away), OneKeyClean (clear all button).
            text.contains("SwipeUp", ignoreCase = true) || text.contains("OneKey", ignoreCase = true) -> KillCause.RECENTS_CLEAN
            text.contains("GameClean", ignoreCase = true) -> KillCause.GAME_CLEAN
            text.contains("Clean", ignoreCase = true) || text.contains("Kill", ignoreCase = true) -> KillCause.BACKGROUND_CLEAN
            else -> null
        }
    }

    /**
     * A kill matters only when REC was doing something: recording or holding the accessibility service
     * keeps it above cached importance. Old records are dropped so a fixed problem stops showing.
     */
    internal fun isRelevant(importance: Int, timestampMs: Long, nowMs: Long): Boolean =
        importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED && nowMs - timestampMs in 0..MAX_KILL_AGE_MS

    /** Redmi and POCO phones report Xiaomi as manufacturer; the brand check covers odd builds. */
    internal fun isXiaomi(manufacturer: String?, brand: String?): Boolean =
        listOf(manufacturer, brand).any { value ->
            value != null && listOf("xiaomi", "redmi", "poco").any { value.equals(it, ignoreCase = true) }
        }
}

/** Settings screens for each fix, most specific first; vendor screens fall back to standard ones. */
object KeepAliveIntents {
    private fun appDetails(pkg: String) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    @SuppressLint("BatteryLife") // Sideloaded recorder that must survive long game sessions.
    fun battery(pkg: String): List<Intent> = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(pkg)
    )

    fun appSettings(pkg: String): List<Intent> = listOf(appDetails(pkg))

    // The per-service details screen needs a signature permission, so only the list is reachable.
    fun accessibility(): List<Intent> = listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun xiaomiAutostart(pkg: String): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
        appDetails(pkg)
    )

    fun xiaomiBatterySaver(pkg: String, label: String): List<Intent> = listOf(
        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", pkg)
            .putExtra("package_label", label),
        appDetails(pkg)
    )

    /** Opens the first screen this phone has; false when none opened. */
    fun launch(context: Context, candidates: List<Intent>): Boolean = candidates.any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) { // Vendor screens may be unexported on some builds.
            Log.w("KeepAliveIntents", "Blocked: ${intent.component ?: intent.action}", e)
            false
        }
    }
}
