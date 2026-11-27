package app.universalmedia.receiver

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import app.universalmedia.mirror.protocol.MirrorProtocol
import app.universalmedia.mirror.protocol.PacketType
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Full-screen receiver for a phone's mirrored screen. Advertises itself on the
 * Wi-Fi, accepts one phone at a time and decodes its H.264 stream straight to
 * the screen with the TV's hardware decoder.
 */
class ReceiverActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var surfaceReady = false
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val deviceName: String by lazy {
        "Universal Media · " + (Build.MODEL?.takeIf { it.isNotBlank() } ?: "TV")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#E3E6EE"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setLineSpacing(0f, 1.3f)
        }
        container.addView(surfaceView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        container.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(container)
        surfaceView.holder.addCallback(this)
        showWaiting()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startServer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopServer()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // ---- Network ------------------------------------------------------------------

    private fun startServer() {
        if (server != null) return
        val socket = try {
            ServerSocket(0)
        } catch (e: IOException) {
            setStatus("Couldn't start the receiver: ${e.message}")
            return
        }
        server = socket
        advertise(socket.localPort)
        acceptThread = Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break
                }
                receive(client) // one phone at a time
                if (!socket.isClosed) runOnUiThread { showWaiting() }
            }
        }, "receiver-accept").also { it.start() }
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
        registration?.let { runCatching { nsd?.unregisterService(it) } }
        registration = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun advertise(port: Int) {
        val manager = getSystemService(NsdManager::class.java) ?: return
        nsd = manager
        multicastLock = getSystemService(WifiManager::class.java)?.createMulticastLock("um-receiver")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val info = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = MirrorProtocol.SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                setStatus("This TV couldn't announce itself on the Wi-Fi (error $errorCode).")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    // ---- Decoding -------------------------------------------------------------------

    private fun receive(client: Socket) {
        var decoder: MediaCodec? = null
        var renderThread: Thread? = null
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 512 * 1024))
            MirrorProtocol.readGreeting(input)
            runOnUiThread { status.visibility = View.GONE }
            while (true) {
                val packet = MirrorProtocol.read(input) ?: break
                when (packet.type) {
                    PacketType.VIDEO_CONFIG -> {
                        val config = MirrorProtocol.parseConfig(packet.payload)
                        renderThread?.interrupt()
                        decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
                        if (!surfaceReady) break
                        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height).apply {
                            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        codec.configure(format, surfaceView.holder.surface, null, 0)
                        codec.start()
                        queue(codec, config.codecConfig, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        decoder = codec
                        renderThread = Thread({ render(codec) }, "receiver-render").also { it.start() }
                        runOnUiThread { fitVideo(config.width, config.height) }
                    }
                    PacketType.VIDEO_FRAME, PacketType.VIDEO_KEY_FRAME -> decoder?.let {
                        queue(it, packet.payload, packet.timeUs, if (packet.type == PacketType.VIDEO_KEY_FRAME) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    }
                    PacketType.BYE -> break
                }
            }
        } catch (e: IOException) {
            // phone disconnected
        } catch (e: IllegalStateException) {
            // decoder error; wait for the next phone
        } finally {
            renderThread?.interrupt()
            decoder?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { client.close() }
        }
    }

    private fun queue(codec: MediaCodec, data: ByteArray, timeUs: Long, flags: Int) {
        val index = codec.dequeueInputBuffer(50_000)
        if (index < 0) return // decoder busy: drop this frame rather than fall behind
        val buffer = codec.getInputBuffer(index) ?: return
        buffer.clear()
        if (data.size > buffer.capacity()) {
            codec.queueInputBuffer(index, 0, 0, timeUs, 0)
            return
        }
        buffer.put(data)
        codec.queueInputBuffer(index, 0, data.size, timeUs, flags)
    }

    private fun render(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index >= 0) codec.releaseOutputBuffer(index, true) // show immediately: lowest delay
            }
        } catch (e: IllegalStateException) {
            // decoder released
        }
    }

    /** Letterboxes the picture to keep the phone screen's shape. */
    private fun fitVideo(width: Int, height: Int) {
        val cw = container.width.takeIf { it > 0 } ?: return
        val ch = container.height.takeIf { it > 0 } ?: return
        val scale = minOf(cw.toFloat() / width, ch.toFloat() / height)
        surfaceView.layoutParams = FrameLayout.LayoutParams((width * scale).toInt(), (height * scale).toInt(), Gravity.CENTER)
    }

    // ---- Status ---------------------------------------------------------------------

    private fun showWaiting() {
        val ip = localIp()
        setStatus(
            "Universal Media Receiver\n\n" +
                "On your phone open Universal Media → Connect → Mirror your screen,\n" +
                "then choose “$deviceName”.\n\n" +
                (ip?.let { "This TV: $it" } ?: "Connect this TV to the same Wi-Fi as your phone."),
        )
    }

    private fun setStatus(text: String) = runOnUiThread {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
