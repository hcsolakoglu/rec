package pixl.rec.core.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import pixl.rec.core.model.MicrophoneDevice

class MicrophoneDevicePickerTest {
    private class Input(val type: Int, val address: String = "")

    private val back = Input(AudioDeviceInfo.TYPE_BUILTIN_MIC, "back")
    private val bottom = Input(AudioDeviceInfo.TYPE_BUILTIN_MIC, "bottom")
    private val unnamed = Input(AudioDeviceInfo.TYPE_BUILTIN_MIC)
    private val wired = Input(AudioDeviceInfo.TYPE_WIRED_HEADSET)
    private val usbHeadset = Input(AudioDeviceInfo.TYPE_USB_HEADSET)
    private val usbDevice = Input(AudioDeviceInfo.TYPE_USB_DEVICE)
    private val sco = Input(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    private val ble = Input(AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun pick(preference: MicrophoneDevice, vararg inputs: Input) =
        MicrophoneDevicePicker.pick(preference, inputs.toList(), Input::type, Input::address)

    @Test fun autoLeavesRoutingToAndroid() {
        assertNull(pick(MicrophoneDevice.AUTO, bottom, wired))
    }

    @Test fun phonePrefersTheBottomBuiltInMicrophone() {
        assertSame(bottom, pick(MicrophoneDevice.PHONE, wired, back, bottom))
        assertSame(back, pick(MicrophoneDevice.PHONE, wired, back))
        assertSame(unnamed, pick(MicrophoneDevice.PHONE, back, unnamed))
        assertNull(pick(MicrophoneDevice.PHONE, wired, sco))
    }

    @Test fun headsetPrefersWiredThenUsbAndNeverBluetooth() {
        assertSame(wired, pick(MicrophoneDevice.HEADSET, sco, usbDevice, usbHeadset, wired, bottom))
        assertSame(usbHeadset, pick(MicrophoneDevice.HEADSET, sco, usbDevice, usbHeadset, bottom))
        assertSame(usbDevice, pick(MicrophoneDevice.HEADSET, ble, usbDevice, bottom))
        assertNull(pick(MicrophoneDevice.HEADSET, sco, ble, bottom))
        assertNull(pick(MicrophoneDevice.HEADSET))
    }
}
