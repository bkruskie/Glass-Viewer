package app.universalmedia.media.download

import app.universalmedia.media.detection.HlsPlaylist
import app.universalmedia.media.detection.HlsPlaylistParser
import app.universalmedia.media.detection.HlsSegment
import app.universalmedia.media.detection.HlsSegmentList
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Whole-file download with HTTP Range resume. */
internal class ProgressiveDownloader(private val http: Http) {

    /** Downloads [url] into [part], resuming if it already has data. Returns the Content-Type. */
    suspend fun download(url: String, part: File, onProgress: suspend (done: Long, total: Long?) -> Unit): String? =
        withContext(Dispatchers.IO) {
            var existing = if (part.exists()) part.length() else 0L
            val response = http.open(http.request(url, if (existing > 0) "bytes=$existing-" else null))
            response.use { r ->
                if (r.code == 416 && existing > 0) return@withContext null // already complete
                if (!r.isSuccessful) throw httpError(r.code)
                if (r.code != 206) existing = 0 // server ignored the range: start over
                val body = r.body ?: throw DownloadException("Empty response", retryable = true)
                val total = body.contentLength().takeIf { it >= 0 }?.plus(existing)
                var done = existing
                FileOutputStream(part, existing > 0).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 500) {
                                lastReport = now
                                onProgress(done, total)
                            }
                        }
                    }
                }
                onProgress(done, total)
                if (total != null && done < total) throw DownloadException("The connection ended early.", retryable = true)
                r.header("Content-Type")
            }
        }
}

/** One piece of a segmented stream: a URL plus an optional Range header. */
internal data class Piece(val url: String, val range: String? = null)

/**
 * Downloads an init section and an ordered list of segments into one file,
 * a few segments at a time, optionally transforming each (decryption).
 * Resume state is "index:bytesWritten".
 */
internal class SegmentDownloader(private val http: Http) {

    suspend fun download(
        init: Piece?,
        pieces: List<Piece>,
        part: File,
        resumeState: String?,
        transform: (suspend (index: Int, bytes: ByteArray) -> ByteArray)? = null,
        onProgress: suspend (done: Long, estimatedTotal: Long?, state: String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (pieces.isEmpty()) throw DownloadException("The stream has no segments.")
        var index = 0
        var written = 0L
        resumeState?.split(':')?.takeIf { it.size == 2 }?.let {
            index = it[0].toIntOrNull()?.coerceIn(0, pieces.size) ?: 0
            written = it[1].toLongOrNull() ?: 0L
        }
        if (!part.exists() || part.length() < written) {
            index = 0
            written = 0L
        }
        RandomAccessFile(part, "rw").use { it.setLength(written) }

        FileOutputStream(part, true).use { out ->
            if (written == 0L && init != null) {
                val bytes = retrying { http.bytes(init.url, init.range) }
                out.write(bytes)
                written += bytes.size
                onProgress(written, null, "0:$written")
            }
            while (index < pieces.size) {
                ensureActive()
                val start = index
                val window = pieces.subList(start, minOf(start + PARALLEL_SEGMENTS, pieces.size))
                val data = coroutineScope {
                    window.mapIndexed { offset, piece ->
                        async {
                            val raw = retrying { http.bytes(piece.url, piece.range) }
                            transform?.invoke(start + offset, raw) ?: raw
                        }
                    }.awaitAll()
                }
                for (bytes in data) {
                    out.write(bytes)
                    written += bytes.size
                }
                out.flush()
                index += window.size
                val estimate = if (index == pieces.size) written else written * pieces.size / index
                onProgress(written, estimate, "$index:$written")
            }
        }
        part
    }

    private companion object {
        const val PARALLEL_SEGMENTS = 4
    }
}

/**
 * HLS download of one media playlist (a master picks its best variant):
 * standard AES-128 is decrypted with the keys the site serves to this session.
 */
internal class HlsDownloader(private val http: Http) {

    data class Output(val file: File, val fragmentedMp4: Boolean)

    suspend fun download(
        playlistUrl: String,
        part: File,
        resumeState: String?,
        onProgress: suspend (done: Long, estimatedTotal: Long?, state: String) -> Unit,
    ): Output {
        val list = loadSegmentList(playlistUrl)
        list.unsupportedReason?.let { throw DownloadException(it) }
        val keys = ConcurrentHashMap<String, ByteArray>()
        val segments = list.segments
        val file = SegmentDownloader(http).download(
            init = list.initUrl?.let { Piece(it, list.initByteRange?.rangeHeader) },
            pieces = segments.map { Piece(it.url, it.byteRange?.rangeHeader) },
            part = part,
            resumeState = resumeState,
            transform = { index, bytes -> stripId3(decrypt(segments[index], bytes, keys)) },
            onProgress = onProgress,
        )
        return Output(file, list.isFragmentedMp4)
    }

    private suspend fun loadSegmentList(url: String): HlsSegmentList {
        val (text, finalUrl) = retrying { http.text(url) }
        HlsSegmentList.parse(text, finalUrl)?.let { return it }
        val master = HlsPlaylistParser.parse(text, finalUrl) as? HlsPlaylist.Master
            ?: throw DownloadException("This isn't a stream that can be saved.")
        val best = master.variants.firstOrNull { !it.audioOnly } ?: master.variants.firstOrNull()
            ?: throw DownloadException("The stream lists no qualities.")
        val (childText, childUrl) = retrying { http.text(best.url) }
        return HlsSegmentList.parse(childText, childUrl) ?: throw DownloadException("Unreadable stream playlist.")
    }

    private suspend fun decrypt(segment: HlsSegment, raw: ByteArray, keys: ConcurrentHashMap<String, ByteArray>): ByteArray {
        val key = segment.key ?: return raw
        val keyBytes = keys[key.uri] ?: retrying { http.bytes(key.uri) }.also { keys[key.uri] = it }
        if (keyBytes.size != 16) throw DownloadException("The stream's key is invalid.")
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(segment.effectiveIv()))
                doFinal(raw)
            }
        } catch (e: java.security.GeneralSecurityException) {
            throw DownloadException("A segment couldn't be decrypted.")
        }
    }
}

/** What will be downloaded for an HLS stream, plus notes for the Technical details view. */
internal data class HlsTracks(val video: String, val audio: String?, val notes: List<String>)

/**
 * Finds the video playlist to download and, if the stream carries sound separately,
 * the audio playlist. Re-reads the master so links with expiring tokens are fresh,
 * and matches the chosen quality by resolution and bitrate.
 */
internal suspend fun resolveHlsTracks(
    http: Http,
    url: String,
    masterUrl: String,
    targetHeight: Int?,
    targetBitrate: Long?,
): HlsTracks {
    val notes = mutableListOf<String>()
    suspend fun master(link: String): HlsPlaylist.Master? = runCatching {
        val (text, finalUrl) = retrying { http.text(link) }
        HlsPlaylistParser.parse(text, finalUrl) as? HlsPlaylist.Master
    }.getOrNull()

    val fromMaster = (if (masterUrl != url) master(masterUrl) else null) ?: master(url)
    if (fromMaster == null) {
        notes += "No master playlist found (only a single quality playlist); separate audio can't be looked up."
        return HlsTracks(url, null, notes)
    }
    val chosenUrl = if (url == masterUrl) null else url
    val variant = fromMaster.match(chosenUrl, targetHeight, targetBitrate)
        ?: throw DownloadException("The stream lists no qualities.")
    val audio = fromMaster.audioFor(variant)
    val renditions = fromMaster.renditions.filter { it.type == "AUDIO" }
    notes += "Master: ${fromMaster.variants.size} qualities, ${renditions.size} audio tracks " +
        "(${renditions.count { it.uri != null }} separate)."
    notes += "Chosen: ${variant.height ?: "?"}p, codecs=${variant.codecs ?: "not listed"}, " +
        "audio group=${fromMaster.audioGroups[variant.url] ?: "none"}."
    notes += if (audio != null) "Separate audio: ${shortUrl(audio)}" else "Separate audio: none (sound expected inside the video)."
    return HlsTracks(variant.url, audio, notes)
}

internal fun shortUrl(url: String): String = url.substringAfter("://").substringBefore('?').let {
    if (it.length > 90) it.take(40) + "…" + it.takeLast(45) else it
}

/** Packed-audio HLS segments start with an ID3 tag; drop it so the segments join into one clean stream. */
internal fun stripId3(bytes: ByteArray): ByteArray {
    if (bytes.size < 10 || bytes[0] != 'I'.code.toByte() || bytes[1] != 'D'.code.toByte() || bytes[2] != '3'.code.toByte()) return bytes
    val size = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or
        ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
    val footer = if ((bytes[5].toInt() and 0x10) != 0) 10 else 0
    val skip = 10 + size + footer
    return if (skip in 1 until bytes.size) bytes.copyOfRange(skip, bytes.size) else bytes
}
