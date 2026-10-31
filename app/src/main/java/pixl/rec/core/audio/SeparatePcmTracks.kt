package pixl.rec.core.audio

/** Synchronous borrowed-buffer delivery: the consumer must finish reading before returning. */
internal class SeparatePcmTracks(
    chunkBytes: Int,
    private val gameGain: Float,
    private val micGain: Float,
    private val consume: Consumer
) {
    /** Primitive signature avoids boxing the length and timestamp on every chunk. */
    fun interface Consumer {
        fun accept(game: ByteArray, mic: ByteArray, length: Int, ptsUs: Long)
    }

    // Unity gain passes existing capture buffers directly, with no copy or sample traversal.
    private val gameScratch = if (gameGain == 1f) null else ByteArray(chunkBytes)
    private val micScratch = if (micGain == 1f) null else ByteArray(chunkBytes)

    fun emit(game: ByteArray, mic: ByteArray, length: Int, ptsUs: Long) {
        val gameOutput = gameScratch?.also {
            PcmAudioMixer.applyGain16Bit(game, length, gameGain, it)
        } ?: game
        val micOutput = micScratch?.also {
            PcmAudioMixer.applyGain16Bit(mic, length, micGain, it)
        } ?: mic
        consume.accept(gameOutput, micOutput, length, ptsUs)
    }
}
