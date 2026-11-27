package app.universalmedia.media.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.universalmedia.media.cast.LocalMediaServer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** A file saved on the phone after the other device sent it. */
data class ReceivedFile(val uri: Uri, val name: String, val mimeType: String, val sizeBytes: Long)

data class TransferProgress(
    val id: Long,
    val name: String,
    val sending: Boolean, // true: phone → other device
    val bytes: Long,
    val total: Long?,
    val done: Boolean = false,
    val error: String? = null,
)

data class TransferSession(val address: String, val transfers: List<TransferProgress> = emptyList())

/**
 * Wi-Fi transfer without the cloud or a second app: the phone runs a small web
 * server, and any computer or phone on the same Wi-Fi opens its address in a
 * browser to download the phone's files or send files to it. The address has a
 * random secret in it, so only someone who sees it on this phone can connect.
 * It stops by itself after 20 minutes without use.
 */
class TransferServer(
    context: Context,
    private val scope: CoroutineScope,
    private val files: suspend () -> List<SharedFile>,
    private val onReceived: suspend (ReceivedFile) -> Unit,
) {
    private val context = context.applicationContext
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var token = ""
    private var idleJob: Job? = null
    @Volatile private var lastUse = 0L
    private var nextId = 0L

    private val _session = MutableStateFlow<TransferSession?>(null)
    val session: StateFlow<TransferSession?> = _session.asStateFlow()

    /** Starts sharing; returns the address, or null when not on Wi-Fi. */
    @Synchronized
    fun start(): String? {
        _session.value?.let { return it.address }
        val ip = LocalMediaServer.localIpv4(context)?.hostAddress ?: return null
        token = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val socket = runCatching { ServerSocket(0, 50) }.getOrNull() ?: return null
        server = socket
        pool.execute { acceptLoop(socket) }
        val address = "http://$ip:${socket.localPort}/$token/"
        _session.value = TransferSession(address)
        lastUse = System.currentTimeMillis()
        idleJob = scope.launch {
            while (true) {
                delay(60_000)
                val busy = _session.value?.transfers?.any { !it.done } == true
                if (!busy && System.currentTimeMillis() - lastUse > 20 * 60_000) { stop(); break }
            }
        }
        return address
    }

    @Synchronized
    fun stop() {
        idleJob?.cancel()
        idleJob = null
        runCatching { server?.close() }
        server = null
        _session.value = null
    }

    fun clearFinished() = _session.update { s -> s?.copy(transfers = s.transfers.filter { !it.done }) }

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
        client.use { s -> runCatching { serve(s) } }
    }

    private fun serve(s: Socket) {
        run {
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val req = TransferProtocol.parseRequestLine(readLine(input) ?: return) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val prefix = "/$token/"
            if (token.isEmpty() || !(req.path == "/$token" || req.path.startsWith(prefix))) {
                return respond(out, 404, "text/plain", "Not found".toByteArray())
            }
            lastUse = System.currentTimeMillis()
            val rest = req.path.removePrefix("/$token").removePrefix("/")
            when {
                rest.isEmpty() && req.method == "GET" -> respond(out, 200, "text/html; charset=utf-8", TransferProtocol.PAGE.toByteArray())
                rest == "list" -> {
                    val list = runBlocking { files() }
                    respond(out, 200, "application/json", TransferProtocol.listJson(list, deviceName()).toByteArray())
                }
                rest.startsWith("f/") -> serveFile(rest.removePrefix("f/").substringBefore('/'), req.method, headers, out)
                rest == "up" && (req.method == "PUT" || req.method == "POST") -> receive(req, headers, input, out)
                else -> respond(out, 404, "text/plain", "Not found".toByteArray())
            }
        }
    }

    private fun deviceName(): String =
        listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ").ifBlank { "your phone" }

    // ---- phone → other device ----

    private fun serveFile(id: String, method: String, headers: Map<String, String>, out: OutputStream) {
        val file = runBlocking { files() }.firstOrNull { it.id == id } ?: return respond(out, 404, "text/plain", "Not found".toByteArray())
        val uri = Uri.parse(file.uri)
        val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
            ?: return respond(out, 404, "text/plain", "That file is no longer on the phone.".toByteArray())
        afd.use { fd ->
            val length = if (fd.length >= 0) fd.length else fd.parcelFileDescriptor.statSize
            var start = 0L
            var end = length - 1
            val range = headers["range"]
            val partial = range != null && range.startsWith("bytes=") && length > 0
            if (partial) {
                val spec = range!!.removePrefix("bytes=").substringBefore(',')
                start = spec.substringBefore('-').trim().toLongOrNull() ?: 0
                end = spec.substringAfter('-').trim().toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
                if (start >= length || start > end) {
                    return writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
                }
            }
            val count = end - start + 1
            val disposition = "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            val head = mutableMapOf(
                "Content-Type" to (file.mimeType ?: "application/octet-stream"),
                "Content-Length" to count.toString(),
                "Accept-Ranges" to "bytes",
                "Content-Disposition" to disposition,
            )
            if (partial) head["Content-Range"] = "bytes $start-$end/$length"
            writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", head)
            if (method == "HEAD") return
            val progress = addTransfer(file.name, sending = true, total = count)
            try {
                FileInputStream(fd.fileDescriptor).use { stream ->
                    stream.channel.position(fd.startOffset + start)
                    copy(stream, out, count) { n -> report(progress, n) }
                }
                finish(progress, null)
            } catch (e: Exception) {
                finish(progress, "Stopped")
                throw e
            }
        }
    }

    // ---- other device → phone ----

    private fun receive(req: TransferProtocol.Request, headers: Map<String, String>, input: InputStream, out: OutputStream) {
        val length = headers["content-length"]?.toLongOrNull()
            ?: return respond(out, 411, "text/plain", "Send one file at a time.".toByteArray())
        val name = TransferProtocol.safeFileName(req.query["name"].orEmpty())
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val free = Environment.getExternalStorageDirectory().usableSpace
        if (free in 1 until length + 50_000_000) {
            return respond(out, 507, "text/plain", "The phone doesn't have enough free space.".toByteArray())
        }
        val progress = addTransfer(name, sending = false, total = length)
        val target = runCatching { createTarget(name, mime) }.getOrElse {
            finish(progress, "Couldn't save")
            return respond(out, 500, "text/plain", "The phone couldn't save the file.".toByteArray())
        }
        try {
            val stream = context.contentResolver.openOutputStream(target.uri, "w") ?: error("no output")
            val got = stream.use { o -> copy(input, o, length) { n -> report(progress, n) } }
            if (got < length) error("incomplete")
            target.commit()
            runBlocking { runCatching { onReceived(ReceivedFile(target.uri, name, mime, length)) } }
            finish(progress, null)
            respond(out, 201, "text/plain", "Saved".toByteArray())
        } catch (e: Exception) {
            target.discard()
            finish(progress, "Connection lost")
            runCatching { respond(out, 500, "text/plain", "Connection lost.".toByteArray()) }
        }
    }

    private inner class Target(val uri: Uri, val file: File?) {
        fun commit() {
            if (file == null) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
        fun discard() {
            if (file != null) file.delete() else runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    /** Videos go to Movies, music to Music, anything else to Download, each in a "Universal Media" folder. */
    private fun createTarget(name: String, mime: String): Target {
        val dirType = when {
            mime.startsWith("video") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio") -> Environment.DIRECTORY_MUSIC
            mime.startsWith("image") -> Environment.DIRECTORY_PICTURES
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = when (dirType) {
                Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dirType/Universal Media")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, values) ?: error("insert failed")
            return Target(uri, null)
        }
        // Android 8–9: app-owned folder shared through FileProvider (no storage permission needed).
        val dir = File(context.getExternalFilesDir(dirType) ?: context.filesDir, "Universal Media").apply { mkdirs() }
        var dest = File(dir, name)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, name.substringBeforeLast('.') + " ($n)" + name.substring(name.substringBeforeLast('.').length))
            n++
        }
        dest.createNewFile()
        return Target(FileProvider.getUriForFile(context, context.packageName + ".files", dest), dest)
    }

    // ---- progress ----

    @Synchronized
    private fun addTransfer(name: String, sending: Boolean, total: Long?): Long {
        val id = ++nextId
        _session.update { s -> s?.copy(transfers = s.transfers + TransferProgress(id, name, sending, 0, total)) }
        return id
    }

    private fun report(id: Long, bytes: Long) {
        lastUse = System.currentTimeMillis()
        _session.update { s -> s?.copy(transfers = s.transfers.map { if (it.id == id) it.copy(bytes = bytes) else it }) }
    }

    private fun finish(id: Long, error: String?) {
        _session.update { s -> s?.copy(transfers = s.transfers.map { if (it.id == id) it.copy(done = true, error = error) else it }) }
    }

    // ---- HTTP plumbing ----

    /** Copies up to [count] bytes; returns how many were copied. */
    private fun copy(input: InputStream, out: OutputStream, count: Long, onProgress: (Long) -> Unit): Long {
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        var lastReport = 0L
        while (done < count) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), count - done).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            if (done - lastReport >= 1_000_000) { onProgress(done); lastReport = done }
        }
        out.flush()
        onProgress(done)
        return done
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 201 -> "Created"; 404 -> "Not Found"; 411 -> "Length Required"; 507 -> "Insufficient Storage"; else -> "Error" }
        writeHead(out, code, reason, mapOf("Content-Type" to type, "Content-Length" to body.size.toString(), "Cache-Control" to "no-store"))
        out.write(body)
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
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
}
