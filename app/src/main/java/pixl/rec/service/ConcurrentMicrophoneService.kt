package pixl.rec.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pixl.rec.core.model.RecordingConfig
import pixl.rec.core.storage.ConfigPreferences

/**
 * Explicitly enabled audio-sharing assistance. No screen/event data or overlays.
 * Android 12 AudioPolicyService recognizes the bound service's UID, so AudioRecord
 * remains owned by AudioCaptureManager in the same process and recording lifecycle.
 */
class ConcurrentMicrophoneService : AccessibilityService() {
    override fun onServiceConnected() {
        if (!ConfigPreferences.loadConfig(this, RecordingConfig()).concurrentMicrophone) {
            disableSelf()
            return
        }
        instance = this
        connectedState.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnected()
        super.onDestroy()
    }

    private fun disconnected() {
        if (instance === this) {
            instance = null
            connectedState.value = false
        }
    }

    companion object {
        private var instance: ConcurrentMicrophoneService? = null
        private val connectedState = MutableStateFlow(false)
        val connected = connectedState.asStateFlow()

        fun disable() {
            instance?.disableSelf()
            connectedState.value = false
        }
    }
}
