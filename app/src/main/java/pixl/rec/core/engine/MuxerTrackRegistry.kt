package pixl.rec.core.engine

import android.media.MediaFormat

/** Called under the engine's muxer lock. Keeps game audio before microphone audio in MP4. */
internal class MuxerTrackRegistry(audioTrackCount: Int) {
    private val formats = arrayOfNulls<MediaFormat>(1 + audioTrackCount)
    private val indices = IntArray(formats.size) { -1 }
    private var registered = 0

    val isReady: Boolean get() = registered == indices.size

    /** Samples buffered before the muxer starts: the original 120, plus 60 for a second audio track. */
    val pendingSampleLimit: Int = PENDING_SAMPLES_PER_TRACK * maxOf(2, formats.size)

    fun reset() {
        formats.fill(null)
        indices.fill(-1)
        registered = 0
    }

    fun onFormat(logicalTrack: Int, format: MediaFormat, addTrack: (MediaFormat) -> Int) {
        if (formats[logicalTrack] != null) return
        formats[logicalTrack] = format
        while (registered < formats.size) {
            val next = formats[registered] ?: break
            indices[registered] = addTrack(next)
            registered++
        }
    }

    fun indexOf(logicalTrack: Int): Int = indices[logicalTrack]

    private companion object {
        const val PENDING_SAMPLES_PER_TRACK = 60
    }
}
