package pixl.rec.core.keepalive

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveChecksTest {
    private fun other(description: String?) = KeepAliveChecks.classifyExit(ApplicationExitInfo.REASON_OTHER, description)

    @Test fun miuiKillsSeenOnDeviceMapToTheirFix() {
        assertEquals(KillCause.RECENTS_CLEAN, other("SwipeUpClean"))
        assertEquals(KillCause.RECENTS_CLEAN, other("OneKeyClean"))
        assertEquals(KillCause.GAME_CLEAN, other("GameClean"))
        assertEquals(KillCause.BACKGROUND_CLEAN, other("PeriodicCleaner(4|2|pressure)"))
        assertEquals(KillCause.BACKGROUND_CLEAN, other("kill all background"))
    }

    @Test fun stockSwipeAndLowMemoryAreReported() {
        assertEquals(
            KillCause.RECENTS_CLEAN,
            KeepAliveChecks.classifyExit(ApplicationExitInfo.REASON_USER_REQUESTED, "remove task")
        )
        assertEquals(KillCause.LOW_MEMORY, KeepAliveChecks.classifyExit(ApplicationExitInfo.REASON_LOW_MEMORY, null))
    }

    @Test fun updatesCrashesAndRoutineTrimsAreNotBlamedOnTheUser() {
        assertNull(KeepAliveChecks.classifyExit(ApplicationExitInfo.REASON_USER_REQUESTED, "stop pixl.rec.hcsolakoglu due to installPackageLI"))
        assertNull(KeepAliveChecks.classifyExit(ApplicationExitInfo.REASON_CRASH, "SwipeUpClean"))
        assertNull(KeepAliveChecks.classifyExit(ApplicationExitInfo.REASON_EXIT_SELF, null))
        assertNull(KeepAliveChecks.classifyExit(ApplicationExitInfo.REASON_ANR, null))
        assertNull(other(null))
        assertNull(other("empty #17"))
        assertNull(other("too many cached"))
    }

    @Test fun onlyRecentKillsOfAWorkingRecAreShown() {
        val now = 10L * 24 * 60 * 60 * 1000
        val hour = 60L * 60 * 1000
        val perceptible = ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
        val cached = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED
        assertTrue(KeepAliveChecks.isRelevant(perceptible, now - hour, now))
        assertFalse(KeepAliveChecks.isRelevant(cached, now - hour, now))
        assertFalse(KeepAliveChecks.isRelevant(perceptible, now - 8 * 24 * hour, now))
        assertFalse(KeepAliveChecks.isRelevant(perceptible, now + hour, now)) // clock moved back
    }

    @Test fun xiaomiFamilyIsDetectedByMakerOrBrand() {
        assertTrue(KeepAliveChecks.isXiaomi("Xiaomi", "Redmi"))
        assertTrue(KeepAliveChecks.isXiaomi("unknown", "POCO"))
        assertTrue(KeepAliveChecks.isXiaomi("xiaomi", null))
        assertFalse(KeepAliveChecks.isXiaomi("samsung", "samsung"))
        assertFalse(KeepAliveChecks.isXiaomi(null, null))
    }
}
