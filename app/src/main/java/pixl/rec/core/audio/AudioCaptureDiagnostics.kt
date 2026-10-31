package pixl.rec.core.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import pixl.rec.R

/** Metadata only; never records PCM, device addresses, package names or conversation content. */
internal class AudioCaptureDiagnostics(private val context: Context) : AutoCloseable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val registrations = mutableListOf<Registration>()
    @Volatile private var closed = false
    private var lastSummaryMs = 0L
    private var lastErrorMs = 0L
    private val modeListener = if (Build.VERSION.SDK_INT >= 31) {
        AudioManager.OnModeChangedListener { mode ->
            if (!closed) Log.i(TAG, "audio mode=$mode")
        }
    } else null

    init {
        Log.i(TAG, "audio mode=${audioManager.mode}")
        if (Build.VERSION.SDK_INT >= 31 && modeListener != null) {
            audioManager.addOnModeChangedListener(ContextCompat.getMainExecutor(context), modeListener)
        }
    }

    fun observe(record: AudioRecord, stream: String) {
        var previous: String? = null
        var previouslySilenced = false
        fun report(configuration: AudioRecordingConfiguration?) {
            if (closed) return
            val device = configuration?.audioDevice ?: record.routedDevice
            val silenced = configuration?.isClientSilenced
            val state = "stream=$stream state=${record.state} recording=${record.recordingState} " +
                "requestedSource=${record.audioSource} clientSource=${configuration?.clientAudioSource} " +
                "silenced=$silenced deviceId=${device?.id} deviceType=${device?.type}"
            if (state != previous) {
                Log.i(TAG, state)
                previous = state
            }
            if (stream == "mic" && silenced == true && !previouslySilenced) {
                Toast.makeText(context, R.string.concurrent_mic_silenced, Toast.LENGTH_LONG).show()
            }
            previouslySilenced = silenced == true
        }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                report(configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId })
            }
        }
        val routing = AudioRouting.OnRoutingChangedListener {
            if (!closed) report(record.activeRecordingConfiguration)
        }
        registrations.add(Registration(record, callback, routing))
        record.registerAudioRecordingCallback(ContextCompat.getMainExecutor(context), callback)
        record.addOnRoutingChangedListener(routing, handler)
        handler.post { if (!closed) report(record.activeRecordingConfiguration) }
    }

    @Synchronized
    fun readError(stream: String, code: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!closed && now - lastErrorMs >= 5_000L) {
            lastErrorMs = now
            Log.w(TAG, "stream=$stream readError=$code (zero buffers alone do not prove policy silencing)")
        }
    }

    fun levels(gameDb: Float, micDb: Float, hasGame: Boolean, hasMic: Boolean, samples: Long, rate: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!closed && now - lastSummaryMs >= 5_000L) {
            lastSummaryMs = now
            Log.i(TAG, "gameDb=$gameDb micDb=$micDb gameBuffer=$hasGame micBuffer=$hasMic " +
                "pcmSamples=$samples aacInputPtsUs=${samples * 1_000_000L / rate}")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        registrations.forEach {
            runCatching { it.record.unregisterAudioRecordingCallback(it.callback) }
            runCatching { it.record.removeOnRoutingChangedListener(it.routing) }
        }
        registrations.clear()
        if (Build.VERSION.SDK_INT >= 31 && modeListener != null) {
            audioManager.removeOnModeChangedListener(modeListener)
        }
        handler.removeCallbacksAndMessages(null)
    }

    private data class Registration(
        val record: AudioRecord,
        val callback: AudioManager.AudioRecordingCallback,
        val routing: AudioRouting.OnRoutingChangedListener
    )

    private companion object { const val TAG = "RecAudioDiagnostics" }
}
