package app.universalmedia.media.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny HTTP server on the phone so TVs can stream files straight from it
 * ("the phone stays free"). Serves only items explicitly published for casting,
 * under random paths, with Range support and the headers DLNA TVs expect.
 * Also hosts the TV-browser receiver page at /tv.
 */
class LocalMediaServer(private val context: Context) {

    private class Item(val uri: Uri, val mimeType: String)

    private val items = ConcurrentHashMap<String, Item>()
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    val webReceiver = WebReceiverState()

    private val _baseUrl = MutableStateFlow<String?>(null)

    /** e.g. http://192.168.1.20:41234, or null when not running / not on Wi-Fi. */
    val baseUrl: StateFlow<String?> = _baseUrl.asStateFlow()

    @Synchronized
    fun start(): String? {
        val ip = localIpv4(context) ?: return null.also { _baseUrl.value = null }
        val running = server
        if (running != null && !running.isClosed) {
            return "http://${ip.hostAddress}:${running.localPort}".also { _baseUrl.value = it }
        }
        val socket = ServerSocket(0, 50)
        server = socket
        pool.execute { acceptLoop(socket) }
        return "http://${ip.hostAddress}:${socket.localPort}".also { _baseUrl.value = it }
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        items.clear()
        _baseUrl.value = null
    }

    /** Makes [uri] reachable by devices on the network and returns its URL. */
    fun publish(uri: Uri, mimeType: String, title: String): String? {
        val base = start() ?: return null
        val id = UUID.randomUUID().toString().replace("-", "")
        items[id] = Item(uri, mimeType)
        val ext = mimeType.substringAfter('/').substringBefore(';').let { if (it == "mp2t") "ts" else it }
        val name = URLEncoder.encode(title.take(60).ifBlank { "media" }, "UTF-8").replace("+", "%20")
        return "$base/m/$id/$name.$ext"
    }

    private val playlists = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Wraps one MPEG-TS file in a one-segment HLS playlist. Players that only take
     * .ts inside HLS (Roku) can then play a saved .ts download.
     */
    fun publishTsAsHls(mediaUrl: String, uri: Uri?): String? {
        val base = start() ?: return null
        val seconds = uri?.let { durationSeconds(it) } ?: 36_000L
        val id = UUID.randomUUID().toString().replace("-", "")
        playlists[id] = buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-TARGETDURATION:").append(seconds).append("\n")
            append("#EXTINF:").append(seconds).append(".0,\n").append(mediaUrl).append("\n#EXT-X-ENDLIST\n")
        }
        return "$base/h/$id/index.m3u8"
    }

    private fun durationSeconds(uri: Uri): Long? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { (it + 999) / 1000 }
        } finally {
            r.release()
        }
    }.getOrNull()?.takeIf { it > 0 }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            }
            pool.execute { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            runCatching {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val out = s.getOutputStream()
                when {
                    method == "OPTIONS" -> writeHead(
                        out, 204, "No Content",
                        mapOf(
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                            "Access-Control-Allow-Headers" to "Range, Content-Type",
                            "Access-Control-Expose-Headers" to "Content-Length, Content-Range",
                            "Content-Length" to "0",
                        ),
                    )
                    path.startsWith("/m/") -> serveMedia(path, method, headers, out)
                    path.startsWith("/h/") -> playlists[path.removePrefix("/h/").substringBefore('/')]
                        ?.let { respond(out, 200, "application/vnd.apple.mpegurl", it.toByteArray()) }
                        ?: respond(out, 404, "text/plain", "Not found".toByteArray())
                    path == "/tv" || path == "/tv/" -> respond(out, 200, "text/html; charset=utf-8", WebReceiverState.PAGE.toByteArray())
                    path == "/tv/state" -> respond(out, 200, "application/json", webReceiver.stateJson().toByteArray())
                    path == "/tv/status" && method == "POST" -> {
                        val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(4096) ?: 0
                        val body = ByteArray(length).also { var r = 0; while (r < length) { val n = input.read(it, r, length - r); if (n < 0) break; r += n } }
                        webReceiver.report(String(body))
                        respond(out, 204, "text/plain", ByteArray(0))
                    }
                    else -> respond(out, 404, "text/plain", "Not found".toByteArray())
                }
            }
        }
    }

    private fun serveMedia(path: String, method: String, headers: Map<String, String>, out: OutputStream) {
        val id = path.removePrefix("/m/").substringBefore('/')
        val item = items[id] ?: return respond(out, 404, "text/plain", "Not found".toByteArray())
        val afd = context.contentResolver.openAssetFileDescriptor(item.uri, "r")
            ?: return respond(out, 404, "text/plain", "Not found".toByteArray())
        afd.use { fd ->
            val length = if (fd.length >= 0) fd.length else fd.parcelFileDescriptor.statSize
            if (length <= 0) return respond(out, 500, "text/plain", "Unknown size".toByteArray())

            var start = 0L
            var end = length - 1
            val range = headers["range"]
            val partial = range != null && range.startsWith("bytes=")
            if (partial) {
                val spec = range!!.removePrefix("bytes=").substringBefore(',')
                val a = spec.substringBefore('-').trim()
                val b = spec.substringAfter('-').trim()
                if (a.isEmpty()) {
                    start = (length - (b.toLongOrNull() ?: 0)).coerceAtLeast(0)
                } else {
                    start = a.toLongOrNull() ?: 0
                    end = b.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
                }
                if (start >= length || start > end) {
                    return writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
                }
            }
            val count = end - start + 1
            val head = mutableMapOf(
                "Content-Type" to item.mimeType,
                "Content-Length" to count.toString(),
                "Accept-Ranges" to "bytes",
                "transferMode.dlna.org" to "Streaming",
                "contentFeatures.dlna.org" to DlnaProtocol.DLNA_FEATURES,
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Expose-Headers" to "Content-Length, Content-Range",
            )
            if (partial) head["Content-Range"] = "bytes $start-$end/$length"
            writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", head)
            if (method == "HEAD") return

            FileInputStream(fd.fileDescriptor).use { stream ->
                val channel = stream.channel
                channel.position(fd.startOffset + start)
                copy(stream, out, count)
            }
        }
    }

    private fun copy(input: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(256 * 1024)
        var remaining = count
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
        out.flush()
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "Error" }
        writeHead(
            out, code, reason,
            mapOf("Content-Type" to type, "Content-Length" to body.size.toString(), "Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*"),
        )
        out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Server: UniversalMedia/1.0 UPnP/1.0 DLNADOC/1.50\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    companion object {
        /** The phone's IPv4 address on Wi-Fi (or Ethernet / hotspot), or null. */
        fun localIpv4(context: Context): InetAddress? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val fromActive = cm?.let { manager ->
                manager.allNetworks.firstNotNullOfOrNull { network ->
                    val caps = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                    val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (!local) return@firstNotNullOfOrNull null
                    manager.getLinkProperties(network)?.linkAddresses
                        ?.map { it.address }
                        ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                }
            }
            if (fromActive != null) return fromActive
            // Hotspot or unusual setups: look at interfaces directly.
            return runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("eth")) }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            }.getOrNull()
        }
    }
}
