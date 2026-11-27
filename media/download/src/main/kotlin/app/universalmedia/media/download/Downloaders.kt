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

/**
 * HLS download: picks the media playlist, fetches segments a few at a time,
 * decrypts standard AES-128 with the keys the site serves to this session,
 * and appends them in order. Resumable at segment granularity.
 */
internal class HlsDownloader(private val http: Http) {

    data class Output(val file: File, val fragmentedMp4: Boolean)

    suspend fun download(
        playlistUrl: String,
        part: File,
        resumeState: String?,
        onProgress: suspend (done: Long, estimatedTotal: Long?, state: String) -> Unit,
    ): Output = withContext(Dispatchers.IO) {
        val list = loadSegmentList(playlistUrl)
        list.unsupportedReason?.let { throw DownloadException(it) }
        val segments = list.segments
        if (segments.isEmpty()) throw DownloadException("The stream has no segments.")

        var index = 0
        var written = 0L
        resumeState?.split(':')?.takeIf { it.size == 2 }?.let {
            index = it[0].toIntOrNull()?.coerceIn(0, segments.size) ?: 0
            written = it[1].toLongOrNull() ?: 0L
        }
        if (!part.exists() || part.length() < written) {
            index = 0
            written = 0L
        }
        RandomAccessFile(part, "rw").use { it.setLength(written) }

        val keys = ConcurrentHashMap<String, ByteArray>()
        FileOutputStream(part, true).use { out ->
            if (written == 0L && list.initUrl != null) {
                val init = retrying { http.bytes(list.initUrl!!, list.initByteRange?.rangeHeader) }
                out.write(init)
                written += init.size
                onProgress(written, null, "0:$written")
            }
            while (index < segments.size) {
                ensureActive()
                val window = segments.subList(index, minOf(index + PARALLEL_SEGMENTS, segments.size))
                val data = coroutineScope {
                    window.map { segment -> async { fetchSegment(segment, keys) } }.awaitAll()
                }
                for (bytes in data) {
                    out.write(bytes)
                    written += bytes.size
                }
                out.flush()
                index += window.size
                val estimate = written * segments.size / index
                onProgress(written, if (index == segments.size) written else estimate, "$index:$written")
            }
        }
        Output(part, list.isFragmentedMp4)
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

    private suspend fun fetchSegment(segment: HlsSegment, keys: ConcurrentHashMap<String, ByteArray>): ByteArray {
        val raw = retrying { http.bytes(segment.url, segment.byteRange?.rangeHeader) }
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

    private companion object {
        const val PARALLEL_SEGMENTS = 4
    }
}
