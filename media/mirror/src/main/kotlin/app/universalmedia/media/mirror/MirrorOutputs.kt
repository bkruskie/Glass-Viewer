package app.universalmedia.media.mirror

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import app.universalmedia.mirror.protocol.MirrorProtocol
import app.universalmedia.mirror.protocol.Packet
import app.universalmedia.mirror.protocol.PacketType
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Where the captured screen goes. The virtual display draws into [surface]. */
internal interface MirrorOutput {
    val surface: Surface

    /** Rebuilds for a new size (phone rotated) and returns the surface to draw into. */
    fun resize(width: Int, height: Int): Surface

    fun stop()
}

/**
 * Sends the screen to the TV receiver app as H.264 over TCP: sharp, smooth and
 * light on Wi-Fi. The connection survives rotation; a new config packet tells the
 * receiver about the new size.
 */
internal class H264Output(
    private val host: String,
    private val port: Int,
    width: Int,
    height: Int,
    private val onFailure: (String) -> Unit,
) : MirrorOutput {

    private val running = AtomicBoolean(true)
    private val socket = Socket()
    private lateinit var out: DataOutputStream
    private var encoder: MediaCodec? = null
    private var drainThread: Thread? = null
    private val lock = Any()

    /** Config packets are remembered so a late connection still gets them. */
    @Volatile private var lastConfig: Packet? = null

    override var surface: Surface = startEncoder(width, height)
        private set

    init {
        Thread {
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 5_000)
                synchronized(lock) {
                    out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 256 * 1024))
                    MirrorProtocol.writeGreeting(out)
                }
                requestKeyFrame()
            } catch (e: IOException) {
                if (running.get()) onFailure("Couldn't reach the TV app. Is it open on the TV?")
            }
        }.start()
    }

    private fun startEncoder(width: Int, height: Int): Surface {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 4).coerceIn(2_000_000, 10_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Keep sending frames when the screen is still, so the TV doesn't wait.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val input = codec.createInputSurface()
        codec.start()
        encoder = codec
        val thread = Thread({ drain(codec, width, height) }, "mirror-h264")
        drainThread = thread
        thread.start()
        return input
    }

    private fun drain(codec: MediaCodec, width: Int, height: Int) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get() && encoder === codec) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index < 0) continue
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    val packet = when {
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 ->
                            Packet(PacketType.VIDEO_CONFIG, 0, MirrorProtocol.configPayload(width, height, bytes))
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ->
                            Packet(PacketType.VIDEO_KEY_FRAME, info.presentationTimeUs, bytes)
                        else -> Packet(PacketType.VIDEO_FRAME, info.presentationTimeUs, bytes)
                    }
                    send(packet)
                }
                codec.releaseOutputBuffer(index, false)
            }
        } catch (e: IllegalStateException) {
            // encoder stopped (rotation or end)
        } catch (e: IOException) {
            if (running.get()) onFailure("The TV disconnected.")
        }
    }

    @Volatile private var lastAudioConfig: Packet? = null

    /** Sends a packet that isn't video (sound). Silently dropped until connected. */
    fun sendExtra(packet: Packet) {
        try {
            send(packet)
        } catch (e: IOException) {
            if (running.get()) onFailure("The TV disconnected.")
        }
    }

    private fun send(packet: Packet) {
        if (packet.type == PacketType.VIDEO_CONFIG) lastConfig = packet
        if (packet.type == PacketType.AUDIO_CONFIG) lastAudioConfig = packet
        synchronized(lock) {
            if (!::out.isInitialized) return // not connected yet; frames before connecting are dropped
            MirrorProtocol.write(out, packet)
            out.flush()
        }
    }

    private fun requestKeyFrame() {
        lastConfig?.let { runCatching { send(it) } }
        lastAudioConfig?.let { runCatching { send(it) } }
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    override fun resize(width: Int, height: Int): Surface {
        val old = encoder
        encoder = null
        runCatching { old?.stop() }
        runCatching { old?.release() }
        surface.release()
        surface = startEncoder(width, height)
        return surface
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        runCatching {
            synchronized(lock) {
                if (::out.isInitialized) {
                    MirrorProtocol.write(out, Packet(PacketType.BYE, 0, ByteArray(0)))
                    out.flush()
                }
            }
        }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { surface.release() }
        runCatching { socket.close() }
    }
}

/**
 * Serves the screen to any web browser as a Motion-JPEG stream: works on nearly
 * every smart TV, console or computer with nothing to install.
 */
internal class MjpegOutput(
    width: Int,
    height: Int,
    private val onReady: (port: Int, key: String) -> Unit,
    private val onFailure: (String) -> Unit,
) : MirrorOutput {

    private val running = AtomicBoolean(true)
    private val handlerThread = HandlerThread("mirror-mjpeg").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val pool = Executors.newCachedThreadPool()
    private val server = ServerSocket(0)

    /** Short secret in the address so others on the Wi-Fi can't watch the screen. Easy to type on a TV remote. */
    val key: String = java.security.SecureRandom().let { r -> (1..5).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }
    private val frameLock = Object()
    @Volatile private var frame: ByteArray? = null
    @Volatile private var frameSeq = 0L
    private var lastFrameAt = 0L
    private var reader: ImageReader = newReader(width, height)

    override val surface: Surface get() = reader.surface

    init {
        pool.execute { acceptLoop() }
        onReady(server.localPort, key)
    }

    private fun newReader(width: Int, height: Int): ImageReader {
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * image.width
                val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                val jpeg = ByteArrayOutputStream(256 * 1024).also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
                if (bitmap !== padded) bitmap.recycle()
                padded.recycle()
                synchronized(frameLock) {
                    frame = jpeg
                    frameSeq++
                    frameLock.notifyAll()
                }
            } finally {
                image.close()
            }
        }, handler)
        return r
    }

    override fun resize(width: Int, height: Int): Surface {
        val old = reader
        reader = newReader(width, height)
        handler.post { runCatching { old.close() } }
        return reader.surface
    }

    private fun acceptLoop() {
        while (running.get()) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            runCatching {
                val requestLine = s.getInputStream().bufferedReader().readLine() ?: return
                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                val out = BufferedOutputStream(s.getOutputStream())
                when (path) {
                    "/$key" -> {
                        out.write("HTTP/1.1 302 Found\r\nLocation: /$key/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                    }
                    "/$key/stream.mjpg" -> stream(out)
                    "/$key/frame.jpg" -> {
                        val jpeg = frame ?: ByteArray(0)
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(jpeg)
                        out.flush()
                    }
                    "/$key/" -> {
                        val page = PAGE.toByteArray()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(page)
                        out.flush()
                    }
                    else -> {
                        val body = "Open the full address shown on the phone, including the code at the end.".toByteArray()
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
    }

    private fun stream(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        var seen = -1L
        while (running.get()) {
            val jpeg = synchronized(frameLock) {
                while (running.get() && frameSeq == seen) frameLock.wait(1_000)
                seen = frameSeq
                frame
            } ?: continue
            out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
            out.write(jpeg)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        synchronized(frameLock) { frameLock.notifyAll() }
        runCatching { server.close() }
        runCatching { reader.close() }
        handlerThread.quitSafely()
        pool.shutdownNow()
    }

    companion object {
        private const val FRAME_INTERVAL_MS = 66L // about 15 frames per second
        private const val JPEG_QUALITY = 70

        private val PAGE = """
<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Universal Media · Screen</title>
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}
img{position:absolute;top:0;left:0;width:100%;height:100%;object-fit:contain}</style></head>
<body><img id="s" src="stream.mjpg" alt="">
<script>
// Some TV browsers don't play MJPEG streams: fall back to refreshing single frames.
var s=document.getElementById('s'),fallback=false;
s.onerror=function(){if(fallback)return;fallback=true;(function f(){s.src='frame.jpg?t='+Date.now();setTimeout(f,120);})();};
</script></body></html>
        """.trimIndent()
    }
}
