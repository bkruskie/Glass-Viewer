package app.universalmedia.media.cast

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Finds and controls DLNA/UPnP TVs on the local network. */
internal class DlnaClient(private val context: Context, private val http: OkHttpClient) {

    /** Sends SSDP searches and returns the media renderers that answer within [timeoutMs]. */
    suspend fun discover(timeoutMs: Int = 3_000): List<DlnaRenderer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("universal-media-ssdp")?.apply { setReferenceCounted(false) }
        val found = LinkedHashMap<String, String>() // usn -> location
        try {
            lock?.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val data = DlnaProtocol.searchRequest.toByteArray()
                val target = InetAddress.getByName(DlnaProtocol.SSDP_ADDRESS)
                repeat(2) { socket.send(DatagramPacket(data, data.size, target, DlnaProtocol.SSDP_PORT)) }
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    DlnaProtocol.parseSearchResponse(String(packet.data, 0, packet.length))?.let { (location, usn) ->
                        found.putIfAbsent(usn, location)
                    }
                }
            }
        } catch (e: Exception) {
            // No Wi-Fi or multicast blocked: report what we have.
        } finally {
            runCatching { lock?.release() }
        }
        found.mapNotNull { (usn, location) ->
            runCatching {
                val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() }
                xml?.let { DlnaProtocol.parseDescription(it, location, usn) }
            }.getOrNull()
        }
    }

    suspend fun load(renderer: DlnaRenderer, url: String, title: String, mimeType: String) {
        runCatching { action(renderer.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0")) }
        action(
            renderer.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to url, "CurrentURIMetaData" to DlnaProtocol.didl(title, url, mimeType)),
        )
        play(renderer)
    }

    suspend fun play(r: DlnaRenderer) {
        action(r.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    }

    suspend fun pause(r: DlnaRenderer) {
        action(r.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "Pause", listOf("InstanceID" to "0"))
    }

    suspend fun stop(r: DlnaRenderer) {
        action(r.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "Stop", listOf("InstanceID" to "0"))
    }

    suspend fun seek(r: DlnaRenderer, ms: Long) {
        action(
            r.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "Seek",
            listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to DlnaProtocol.formatTime(ms)),
        )
    }

    suspend fun changeVolume(r: DlnaRenderer, delta: Int) {
        val url = r.renderingControlUrl ?: return
        val current = DlnaProtocol.responseValue(
            action(url, DlnaProtocol.RENDERING_CONTROL, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")),
            "CurrentVolume",
        )?.toIntOrNull() ?: 20
        action(
            url, DlnaProtocol.RENDERING_CONTROL, "SetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to (current + delta).coerceIn(0, 100).toString()),
        )
    }

    suspend fun status(r: DlnaRenderer): RemoteStatus {
        val position = action(r.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))
        val transport = runCatching {
            action(r.avTransportUrl, DlnaProtocol.AV_TRANSPORT, "GetTransportInfo", listOf("InstanceID" to "0"))
        }.getOrNull()
        val state = transport?.let { DlnaProtocol.responseValue(it, "CurrentTransportState") }.orEmpty()
        return RemoteStatus(
            positionMs = DlnaProtocol.parseTime(DlnaProtocol.responseValue(position, "RelTime")) ?: 0,
            durationMs = DlnaProtocol.parseTime(DlnaProtocol.responseValue(position, "TrackDuration")) ?: 0,
            playing = state == "PLAYING" || state == "TRANSITIONING",
            ended = state == "STOPPED" || state == "NO_MEDIA_PRESENT",
        )
    }

    private suspend fun action(controlUrl: String, service: String, name: String, args: List<Pair<String, String>>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(controlUrl)
                .header("SOAPACTION", DlnaProtocol.soapActionHeader(service, name))
                .post(DlnaProtocol.soapEnvelope(service, name, args).toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val upnpError = DlnaProtocol.responseValue(body, "errorDescription")
                    throw CastException("The TV refused \"$name\"" + (upnpError?.let { ": $it" } ?: " (HTTP ${response.code})"))
                }
                body
            }
        }
}
