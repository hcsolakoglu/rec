package pixl.rec.core.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import pixl.rec.core.model.AudioSource
import pixl.rec.core.model.RecordingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import pixl.rec.service.ConcurrentMicrophoneService

/**
 * Manages dual-stream audio capture:
 * 1. Internal game audio via [AudioPlaybackCaptureConfiguration] (API 29+)
 * 2. Microphone audio via [AudioRecord]
 * Mixes both streams in real-time with sample-accurate monotonic PTS synchronization
 * and seamless silence synthesis for immediate MediaMuxer startup.
 */
class AudioCaptureManager(
    private val context: Context,
    private val config: RecordingConfig,
    private val mediaProjection: MediaProjection?,
    private val listener: AudioDataListener
) {
    interface AudioDataListener {
        fun onPcmAudioData(pcmBytes: ByteArray, length: Int, ptsUs: Long)
        fun onSeparatePcmAudioData(game: ByteArray, mic: ByteArray, length: Int, ptsUs: Long)
        fun onAudioLevels(gameDb: Float, micDb: Float)
        fun onAudioError(e: Throwable)
    }

    private val tag = "AudioCaptureManager"
    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null

    private var captureJob: Job? = null
    private var internalReaderJob: Job? = null
    private var micReaderJob: Job? = null
    private var assistanceJob: Job? = null
    private var diagnostics: AudioCaptureDiagnostics? = null
    private var prepared = false

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    // Throttled VU calculation state (10Hz UI matching)
    private var lastDbCalcTimeNs = 0L
    private val DB_CALC_INTERVAL_NS = 100_000_000L // 100ms

    private val sampleRate = config.audioSampleRate // 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2 * 2 // 16-bit (2 bytes) * 2 channels = 4 bytes per stereo frame

    // Standard AAC-LC frame: 1024 samples per channel = 4096 bytes per frame
    private val CHUNK_FRAME_COUNT = 1024
    private val CHUNK_BYTES = CHUNK_FRAME_COUNT * bytesPerSample // 4096 bytes
    private val CHUNK_INTERVAL_NS = (CHUNK_FRAME_COUNT.toLong() * 1_000_000_000L) / sampleRate.toLong() // 21,333,333 ns

    private val internalQueue = ConcurrentLinkedQueue<ByteArray>()
    private val micQueue = ConcurrentLinkedQueue<ByteArray>()
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()

    private fun getBuffer(): ByteArray {
        return bufferPool.poll() ?: ByteArray(CHUNK_BYTES)
    }

    private fun recycleBuffer(buf: ByteArray) {
        if (bufferPool.size < 32) {
            bufferPool.offer(buf)
        }
    }

    private var bufferSizeInBytes: Int = 0

    /**
     * Prepares AudioRecord instances based on [RecordingConfig.audioSource].
     */
    @SuppressLint("MissingPermission")
    fun prepare() {
        if (prepared) return
        prepared = true
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        bufferSizeInBytes = (minBufferSize * 2).coerceAtLeast(CHUNK_BYTES * 2)

        val audioFormatConfig = AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        // 1. Prepare Internal Audio Capture (API 29+)
        if (config.audioSource.hasInternal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                internalAudioRecord = AudioRecord.Builder()
                    .setAudioFormat(audioFormatConfig)
                    .setBufferSizeInBytes(bufferSizeInBytes)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build()

                if (internalAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(tag, "Internal AudioRecord failed to initialize")
                    internalAudioRecord?.release()
                    internalAudioRecord = null
                } else {
                    Log.i(tag, "Internal AudioRecord initialized successfully")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to configure AudioPlaybackCapture", e)
            }
        }

        // 2. Prepare Microphone Audio Record
        if (config.audioSource.hasMic) {
            val hasRecordPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (hasRecordPermission) {
                try {
                    for (source in MicrophoneCapturePolicy.sources(
                        config.concurrentMicrophone, ConcurrentMicrophoneService.connected.value
                    )) {
                        Log.i(tag, "Initializing mic source=$source assistance=${ConcurrentMicrophoneService.connected.value}")
                        val candidate = runCatching {
                            AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSizeInBytes)
                        }.getOrElse {
                            Log.w(tag, "Mic initialization failed source=$source", it)
                            null
                        }
                        if (candidate?.state == AudioRecord.STATE_INITIALIZED) {
                            micAudioRecord = candidate
                            break
                        }
                        candidate?.release()
                    }

                    if (micAudioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                        Log.w(tag, "Mic AudioRecord failed to initialize")
                        micAudioRecord?.release()
                        micAudioRecord = null
                    } else {
                        Log.i(tag, "Mic AudioRecord initialized successfully")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to initialize mic AudioRecord", e)
                }
            } else {
                Log.w(tag, "RECORD_AUDIO permission not granted, skipping mic capture")
            }
        }
    }

    /**
     * Starts audio recording and mixing loops with unified session base time.
     */
    fun start(scope: CoroutineScope, sessionBaseTimeNs: Long = System.nanoTime()) {
        if (!config.audioSource.hasAudio) {
            Log.i(tag, "Audio is muted in config, skipping capture start")
            return
        }
        if (!isRunning.compareAndSet(false, true)) return
        diagnostics = runCatching { AudioCaptureDiagnostics(context) }.getOrNull()
        internalAudioRecord?.let { record ->
            runCatching { diagnostics?.observe(record, "internal") }
                .onFailure { Log.w(tag, "Internal diagnostics unavailable", it) }
        }
        micAudioRecord?.let { record ->
            runCatching { diagnostics?.observe(record, "mic") }
                .onFailure { Log.w(tag, "Mic diagnostics unavailable", it) }
        }
        if (config.concurrentMicrophone && config.audioSource.hasMic) {
            assistanceJob = scope.launch(Dispatchers.Main) {
                ConcurrentMicrophoneService.connected.collect { connected ->
                    Log.i(tag, "Concurrent microphone assistance connected=$connected")
                    if (!connected) android.widget.Toast.makeText(
                        context, ConcurrentMicrophoneTexts.disconnectedHint(context),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        try {
            internalAudioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(tag, "Error starting internal AudioRecord", e)
        }

        try {
            micAudioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(tag, "Error starting mic AudioRecord", e)
        }

        isPaused.set(false)
        lastDbCalcTimeNs = 0L

        internalQueue.clear()
        micQueue.clear()

        // 1. Dedicated asynchronous reader for Internal Game Audio
        if (config.audioSource.hasInternal && internalAudioRecord != null) {
            internalReaderJob = scope.launch(Dispatchers.IO) {
                val tempBuf = ByteArray(CHUNK_BYTES)
                while (isActive && isRunning.get()) {
                    val intRecord = internalAudioRecord ?: break
                    if (isPaused.get()) {
                        delay(10)
                        continue
                    }
                    try {
                        val read = intRecord.read(tempBuf, 0, CHUNK_BYTES)
                        if (read > 0) {
                            val chunk = getBuffer()
                            System.arraycopy(tempBuf, 0, chunk, 0, read)
                            if (read < CHUNK_BYTES) {
                                Arrays.fill(chunk, read, CHUNK_BYTES, 0.toByte())
                            }
                            // Cap queue to avoid unbounded latency drift
                            while (internalQueue.size >= 8) {
                                internalQueue.poll()?.let { recycleBuffer(it) }
                            }
                            internalQueue.offer(chunk)
                        } else {
                            if (read < 0) diagnostics?.readError("internal", read)
                            delay(5)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.w(tag, "Error in internal audio reader", e)
                        }
                        delay(10)
                    }
                }
            }
        }

        // 2. Dedicated asynchronous reader for Microphone Audio
        if (config.audioSource.hasMic && micAudioRecord != null) {
            micReaderJob = scope.launch(Dispatchers.IO) {
                val tempBuf = ByteArray(CHUNK_BYTES)
                while (isActive && isRunning.get()) {
                    val micRecord = micAudioRecord ?: break
                    if (isPaused.get()) {
                        delay(10)
                        continue
                    }
                    try {
                        val read = micRecord.read(tempBuf, 0, CHUNK_BYTES)
                        if (read > 0) {
                            val chunk = getBuffer()
                            System.arraycopy(tempBuf, 0, chunk, 0, read)
                            if (read < CHUNK_BYTES) {
                                Arrays.fill(chunk, read, CHUNK_BYTES, 0.toByte())
                            }
                            while (micQueue.size >= 8) {
                                micQueue.poll()?.let { recycleBuffer(it) }
                            }
                            micQueue.offer(chunk)
                        } else {
                            if (read < 0) diagnostics?.readError("mic", read)
                            delay(5)
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.w(tag, "Error in mic audio reader", e)
                        }
                        delay(10)
                    }
                }
            }
        }

        // 3. Master Mixing & Pacing Loop
        captureJob = scope.launch(Dispatchers.IO) {
            runMixerLoop()
        }
    }

    fun pause() {
        if (isPaused.compareAndSet(false, true)) {
            Log.i(tag, "AudioCaptureManager paused")
        }
    }

    fun resume() {
        if (isPaused.compareAndSet(true, false)) {
            Log.i(tag, "AudioCaptureManager resumed")
        }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        assistanceJob?.cancel()
        assistanceJob = null
        diagnostics?.close()
        diagnostics = null
        captureJob?.cancel()
        internalReaderJob?.cancel()
        micReaderJob?.cancel()

        try {
            internalAudioRecord?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping internal AudioRecord", e)
        }

        try {
            micAudioRecord?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping mic AudioRecord", e)
        }

        internalQueue.clear()
        micQueue.clear()
        bufferPool.clear()
    }

    /** Finish any synchronous PCM handoff before the engine signals encoder EOS. */
    suspend fun awaitStopped() {
        captureJob?.join()
        internalReaderJob?.join()
        micReaderJob?.join()
    }

    fun release() {
        stop()
        try {
            internalAudioRecord?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing internal AudioRecord", e)
        }
        try {
            micAudioRecord?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing mic AudioRecord", e)
        }
        internalAudioRecord = null
        micAudioRecord = null
        prepared = false
        Log.i(tag, "AudioCaptureManager released")
    }

    private suspend fun runMixerLoop() {
        val silenceBuf = ByteArray(CHUNK_BYTES)
        val mixedBuf = ByteArray(CHUNK_BYTES)
        val separateTracks = if (config.usesSeparateAudioTracks) SeparatePcmTracks(
            CHUNK_BYTES, config.internalAudioGain, config.micGain, listener::onSeparatePcmAudioData
        ) else null
        var totalEmittedSamples = 0L

        var nextChunkTimeNs = System.nanoTime()

        while (isRunning.get()) {
            if (isPaused.get()) {
                delay(10)
                nextChunkTimeNs = System.nanoTime()
                continue
            }

            val now = System.nanoTime()
            if (now < nextChunkTimeNs) {
                val waitMs = (nextChunkTimeNs - now) / 1_000_000L
                if (waitMs > 1) {
                    delay(waitMs)
                }
            }
            nextChunkTimeNs += CHUNK_INTERVAL_NS
            if (System.nanoTime() - nextChunkTimeNs > CHUNK_INTERVAL_NS * 5) {
                // Reset clock pacing if background scheduling delayed the loop
                nextChunkTimeNs = System.nanoTime() + CHUNK_INTERVAL_NS
            }

            val gameChunk = internalQueue.poll()
            val micChunk = micQueue.poll()

            val hasGame = gameChunk != null
            val hasMic = micChunk != null

            val gBuf = gameChunk ?: silenceBuf
            val mBuf = micChunk ?: silenceBuf

            // Throttled VU decibel level calculation (10Hz matching UI telemetry ticker)
            if (now - lastDbCalcTimeNs >= DB_CALC_INTERVAL_NS) {
                lastDbCalcTimeNs = now
                val gameDb = if (hasGame) PcmAudioMixer.calculateDbLevel(gBuf, CHUNK_BYTES, config.internalAudioGain) else -60f
                val micDb = if (hasMic) PcmAudioMixer.calculateDbLevel(mBuf, CHUNK_BYTES, config.micGain) else -60f
                listener.onAudioLevels(gameDb, micDb)
                diagnostics?.levels(gameDb, micDb, hasGame, hasMic, totalEmittedSamples, sampleRate)
            }

            if (separateTracks != null) {
                val ptsUs = (totalEmittedSamples * 1_000_000L) / sampleRate.toLong()
                try {
                    // Both encoders consume synchronously before the readers can reuse these buffers.
                    separateTracks.emit(gBuf, mBuf, CHUNK_BYTES, ptsUs)
                } finally {
                    if (gameChunk != null) recycleBuffer(gameChunk)
                    if (micChunk != null) recycleBuffer(micChunk)
                }
                totalEmittedSamples += CHUNK_FRAME_COUNT
                continue
            }

            val outputBytes: Int
            val bufferToSend: ByteArray

            when (config.audioSource) {
                AudioSource.INTERNAL_AND_MIC -> {
                    if (hasGame && hasMic) {
                        outputBytes = PcmAudioMixer.mixStereo16Bit(
                            gBuf, CHUNK_BYTES,
                            mBuf, CHUNK_BYTES,
                            config.internalAudioGain, config.micGain,
                            mixedBuf
                        )
                        bufferToSend = mixedBuf
                    } else if (hasGame) {
                        outputBytes = PcmAudioMixer.applyGain16Bit(
                            gBuf, CHUNK_BYTES,
                            config.internalAudioGain,
                            mixedBuf
                        )
                        bufferToSend = mixedBuf
                    } else if (hasMic) {
                        outputBytes = PcmAudioMixer.applyGain16Bit(
                            mBuf, CHUNK_BYTES,
                            config.micGain,
                            mixedBuf
                        )
                        bufferToSend = mixedBuf
                    } else {
                        // Both streams currently silent: send digital silence to keep AAC encoder alive
                        outputBytes = CHUNK_BYTES
                        bufferToSend = silenceBuf
                    }
                }
                AudioSource.INTERNAL_ONLY -> {
                    if (hasGame) {
                        outputBytes = PcmAudioMixer.applyGain16Bit(
                            gBuf, CHUNK_BYTES,
                            config.internalAudioGain,
                            mixedBuf
                        )
                        bufferToSend = mixedBuf
                    } else {
                        // Game audio idle/quiet: output digital silence to guarantee immediate muxer startup
                        outputBytes = CHUNK_BYTES
                        bufferToSend = silenceBuf
                    }
                }
                AudioSource.MIC_ONLY -> {
                    if (hasMic) {
                        outputBytes = PcmAudioMixer.applyGain16Bit(
                            mBuf, CHUNK_BYTES,
                            config.micGain,
                            mixedBuf
                        )
                        bufferToSend = mixedBuf
                    } else {
                        outputBytes = CHUNK_BYTES
                        bufferToSend = silenceBuf
                    }
                }
                AudioSource.MUTE -> {
                    outputBytes = 0
                    bufferToSend = silenceBuf
                }
            }

            if (gameChunk != null) recycleBuffer(gameChunk)
            if (micChunk != null) recycleBuffer(micChunk)

            if (outputBytes > 0) {
                // Strictly monotonic presentation timestamp based on the continuous audio sample clock
                val ptsUs = (totalEmittedSamples * 1_000_000L) / sampleRate.toLong()
                totalEmittedSamples += CHUNK_FRAME_COUNT

                listener.onPcmAudioData(bufferToSend, outputBytes, ptsUs)
            }
        }
    }
}

