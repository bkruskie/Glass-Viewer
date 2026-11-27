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
            for (source in sources) {
                val ex = source.extractor
                for (t in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(t)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
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
            try {
                for ((source, track, format) in selected) source.trackMap[track] = m.addTrack(format)
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
                    info.set(0, size, time, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
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
