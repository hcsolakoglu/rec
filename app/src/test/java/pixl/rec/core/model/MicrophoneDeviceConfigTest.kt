package pixl.rec.core.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import pixl.rec.core.storage.ConfigSerializer

class MicrophoneDeviceConfigTest {
    @Test fun everyChoiceSurvivesProfileExportAndImport() {
        for (device in MicrophoneDevice.entries) {
            // Import aligns sizes to 16 (default width 1080 becomes 1088); 1600x720 round-trips exactly.
            val config = RecordingConfig(width = 1600, height = 720, microphoneDevice = device, concurrentMicrophone = true)
            assertEquals(config, ConfigSerializer.importFromJson(ConfigSerializer.exportToJson(config)).getOrThrow())
        }
    }

    @Test fun oldOrUnknownProfileValuesKeepAndroidRouting() {
        for (stored in listOf(null, "BLUETOOTH", "", "phone")) {
            val json = JSONObject(ConfigSerializer.exportToJson(RecordingConfig(microphoneDevice = MicrophoneDevice.PHONE)))
            json.getJSONObject("audio").apply { if (stored == null) remove("microphone_device") else put("microphone_device", stored) }
            assertEquals(MicrophoneDevice.AUTO, ConfigSerializer.importFromJson(json.toString()).getOrThrow().microphoneDevice)
        }
    }
}
