package pixl.rec.core.audio

import android.media.AudioDeviceInfo
import pixl.rec.core.model.MicrophoneDevice

/** Picks the input for a [MicrophoneDevice] choice; generic so JVM tests need no AudioDeviceInfo. */
internal object MicrophoneDevicePicker {
    // Bluetooth is excluded: its microphone needs a call-style SCO link that also changes playback.
    private val headsetTypes = listOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE
    )

    /** Null for AUTO or when no matching input exists; the caller then keeps Android's routing. */
    fun <T> pick(preference: MicrophoneDevice, inputs: List<T>, type: (T) -> Int, address: (T) -> String?): T? =
        when (preference) {
            MicrophoneDevice.AUTO -> null
            MicrophoneDevice.PHONE -> inputs.filter { type(it) == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                // "bottom" is the main voice microphone where Android names built-in mics.
                // The back mic also reports TYPE_BUILTIN_MIC; it is the camcorder mic, so it is the last resort.
                .let { mics ->
                    mics.firstOrNull { address(it) == "bottom" }
                        ?: mics.firstOrNull { address(it) != "back" }
                        ?: mics.firstOrNull()
                }
            MicrophoneDevice.HEADSET -> headsetTypes.firstNotNullOfOrNull { wanted ->
                inputs.firstOrNull { type(it) == wanted }
            }
        }
}
