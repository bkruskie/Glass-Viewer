package app.universalmedia.media.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Combines separately downloaded tracks (e.g. DASH video + audio, or HLS video +
 * its separate audio playlist) into one MP4 or WebM, copying samples without
 * re-encoding. Uses only the Android framework (MediaExtractor + MediaMuxer).
 */
internal object TrackMuxer {

    class Output(val file: File, val mimeType: String, val extension: String)

    private val webmMimes = setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "audio/vorbis", "audio/opus")

    private class Source(
        val extractor: MediaExtractor,
        val trackMap: MutableMap<Int, Int> = mutableMapOf(),
        var offsetUs: Long = 0,
    )

    /**
     * Earliest timestamp in [file]. Stream segments keep the broadcaster's clock
     * (often minutes or hours in), and separate audio files usually start at 0; left
     * as is, players wait forever for one track to "catch up" and the video freezes.
     */
    private fun firstTimestampUs(file: File): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            for (t in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) ex.selectTrack(t)
            }
            var min = Long.MAX_VALUE
            var scanned = 0
            while (scanned < 2_000) { // the first few seconds are enough; B-frames sit near the start
                val time = ex.sampleTime
                if (time < 0) break
                if (time < min) min = time
                ex.advance()
                scanned++
            }
            if (min == Long.MAX_VALUE) 0 else min
        } catch (e: Exception) {
            0
        } finally {
            runCatching { ex.release() }
        }
    }

    private const val AAC = "audio/mp4a-latm"

    /**
     * Length of the ADTS header at the start of [buf] (7, or 9 with CRC) when the sample
     * is a whole ADTS frame of [size] bytes; 0 otherwise. Android's MPEG-TS extractor can
     * hand out AAC frames with this header still attached, but MP4 stores raw AAC: left
     * in, players can't decode the sound and the video plays silent.
     */
    internal fun adtsHeaderLength(buf: ByteBuffer, size: Int): Int {
        if (size < 9) return 0
        val b0 = buf.get(0).toInt() and 0xFF
        val b1 = buf.get(1).toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF6) != 0xF0) return 0 // sync word, layer 0
        val frameLength = ((buf.get(3).toInt() and 0x03) shl 11) or
            ((buf.get(4).toInt() and 0xFF) shl 3) or
            ((buf.get(5).toInt() and 0xE0) ushr 5)
        if (frameLength != size) return 0
        return if ((b1 and 0x01) == 1) 7 else 9
    }

    /** AudioSpecificConfig (csd-0) built from an ADTS header, for when the extractor doesn't supply one. */
    private fun audioSpecificConfig(buf: ByteBuffer): ByteBuffer {
        val b2 = buf.get(2).toInt() and 0xFF
        val b3 = buf.get(3).toInt() and 0xFF
        val profile = ((b2 ushr 6) and 0x03) + 1
        val freqIndex = (b2 ushr 2) and 0x0F
        val channels = ((b2 and 0x01) shl 2) or ((b3 ushr 6) and 0x03)
        return ByteBuffer.wrap(
            byteArrayOf(
                ((profile shl 3) or (freqIndex ushr 1)).toByte(),
                (((freqIndex and 0x01) shl 7) or (channels shl 3)).toByte(),
            ),
        )
    }

    /** Makes sure an AAC track format carries codec config; reads the first frame's ADTS header if needed. */
    private fun ensureAacConfig(file: File, track: Int, format: MediaFormat) {
        if (format.containsKey("csd-0")) return
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            ex.selectTrack(track)
            val buf = ByteBuffer.allocate(64 * 1024)
            val size = ex.readSampleData(buf, 0)
            if (size > 0 && adtsHeaderLength(buf, size) > 0) format.setByteBuffer("csd-0", audioSpecificConfig(buf))
        } catch (e: Exception) {
            // leave as is; addTrack will report if it can't work
        } finally {
            runCatching { ex.release() }
        }
    }

    /** e.g. "video/avc 1280x720, audio/mp4a-latm 48000 Hz 2 ch". */
    fun describe(file: File): String {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).joinToString(", ") { t ->
                val f = ex.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_WIDTH) ->
                        "$mime ${f.getInteger(MediaFormat.KEY_WIDTH)}x${f.getInteger(MediaFormat.KEY_HEIGHT)}"
                    mime.startsWith("audio/") && f.containsKey(MediaFormat.KEY_SAMPLE_RATE) ->
                        "$mime ${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz" +
                            (if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) " ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch" else "")
                    else -> mime
                }
            }.ifBlank { "no tracks found" }
        } catch (e: Exception) {
            "unreadable (${e.javaClass.simpleName})"
        } finally {
            runCatching { ex.release() }
        }
    }

    /** True when [file] has a video track but no audio track. */
    fun hasNoAudio(file: File): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            mimes.any { it.startsWith("video/") } && mimes.none { it.startsWith("audio/") }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { ex.release() }
        }
    }

    fun mux(inputs: List<File>, outDir: File, baseName: String): Output {
        val sources = inputs.map { file ->
            Source(MediaExtractor().apply { setDataSource(file.absolutePath) }, offsetUs = firstTimestampUs(file))
        }
        var muxer: MediaMuxer? = null
        try {
            val selected = mutableListOf<Triple<Source, Int, MediaFormat>>()
            for ((index, source) in sources.withIndex()) {
                val ex = source.extractor
                for (t in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(t)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime == AAC) {
                        ensureAacConfig(inputs[index], t, format)
                        format.setInteger("is-adts", 0)
                    }
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        ex.selectTrack(t)
                        selected += Triple(source, t, format)
                    }
                }
            }
            if (selected.isEmpty()) throw DownloadException("The downloaded stream contains no playable tracks.")

            val mimes = selected.map { it.third.getString(MediaFormat.KEY_MIME).orEmpty() }
            val webm = mimes.all { it in webmMimes }
            if (!webm && mimes.any { it in webmMimes }) {
                throw DownloadException("This stream mixes formats that can't be combined into one file.")
            }
            val hasVideo = mimes.any { it.startsWith("video/") }
            val extension = when {
                webm -> "webm"
                hasVideo -> "mp4"
                else -> "m4a"
            }
            val output = File(outDir, "$baseName.$extension")
            output.delete()
            val m = MediaMuxer(
                output.absolutePath,
                if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxer = m
            val aacTracks = mutableSetOf<Int>()
            try {
                for ((source, track, format) in selected) {
                    val muxTrack = m.addTrack(format)
                    source.trackMap[track] = muxTrack
                    if (format.getString(MediaFormat.KEY_MIME) == AAC) aacTracks += muxTrack
                }
            } catch (e: Exception) {
                throw DownloadException("This phone can't package this stream's format (${mimes.joinToString()}).")
            }
            m.start()

            val maxInput = selected.maxOf { (_, _, f) ->
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            }
            val buffer = ByteBuffer.allocateDirect(maxOf(maxInput, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            val finished = BooleanArray(sources.size)

            // Interleave by timestamp so players can stream the file smoothly.
            while (true) {
                var next = -1
                var nextTime = Long.MAX_VALUE
                for (i in sources.indices) {
                    if (finished[i]) continue
                    val raw = sources[i].extractor.sampleTime
                    if (raw < 0) {
                        finished[i] = true
                        continue
                    }
                    val time = raw - sources[i].offsetUs
                    if (time < nextTime) {
                        nextTime = time
                        next = i
                    }
                }
                if (next < 0) break
                val source = sources[next]
                val ex = source.extractor
                buffer.clear()
                val size = try {
                    ex.readSampleData(buffer, 0)
                } catch (e: IllegalArgumentException) {
                    throw DownloadException("A video frame was too large to package.")
                }
                if (size < 0) {
                    finished[next] = true
                    continue
                }
                val target = source.trackMap[ex.sampleTrackIndex]
                if (target != null) {
                    val keyFrame = (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    // Each source starts at 0; tracks inside one source keep their relative timing.
                    val time = (ex.sampleTime - source.offsetUs).coerceAtLeast(0)
                    val skip = if (target in aacTracks) adtsHeaderLength(buffer, size) else 0
                    info.set(skip, size - skip, time, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    m.writeSampleData(target, buffer, info)
                }
                ex.advance()
            }
            try {
                m.stop()
            } catch (e: IllegalStateException) {
                throw DownloadException("The downloaded stream couldn't be packaged.")
            }
            val mime = when {
                webm && hasVideo -> "video/webm"
                webm -> "audio/webm"
                hasVideo -> "video/mp4"
                else -> "audio/mp4"
            }
            return Output(output, mime, extension)
        } finally {
            runCatching { muxer?.release() }
            sources.forEach { runCatching { it.extractor.release() } }
        }
    }
}
