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

    /** Asks every UPnP device on the Wi-Fi to answer, and returns the ones that look like TVs. */
    suspend fun scanAll(timeoutMs: Int = 3_000): List<NetworkTv> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("universal-media-ssdp-all")?.apply { setReferenceCounted(false) }
        val answers = LinkedHashMap<String, DlnaProtocol.SsdpAnswer>() // usn+st -> answer
        try {
            lock?.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val target = InetAddress.getByName(DlnaProtocol.SSDP_ADDRESS)
                val requests = listOf(
                    DlnaProtocol.searchAllRequest,
                    DlnaProtocol.searchAllRequest.replace("ST: ssdp:all", "ST: roku:ecp"),
                    DlnaProtocol.searchAllRequest.replace("ST: ssdp:all", "ST: urn:dial-multiscreen-org:service:dial:1"),
                )
                requests.forEach { r ->
                    val data = r.toByteArray()
                    socket.send(DatagramPacket(data, data.size, target, DlnaProtocol.SSDP_PORT))
                }
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    DlnaProtocol.parseAnswer(String(packet.data, 0, packet.length))?.let { a ->
                        answers.putIfAbsent(a.usn + "|" + a.st, a)
                    }
                }
            }
        } catch (e: Exception) {
            // No Wi-Fi or multicast blocked.
        } finally {
            runCatching { lock?.release() }
        }
        val identities = answers.values.map { it.location }.distinct().take(40).mapNotNull { location ->
            runCatching {
                val xml = http.newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() }
                xml?.let { DlnaProtocol.parseIdentity(it) }?.let { location to it }
            }.getOrNull()
        }.toMap()
        TvClassifier.classify(answers.values.toList(), identities)
    }

    /**
     * Roku External Control: asks the Roku's built-in player to play [url] (experimental).
     * Tries the ways different Roku OS versions accept it, and reports each answer if all fail.
     */
    suspend fun rokuPlay(host: String, url: String, mimeType: String, title: String) = withContext(Dispatchers.IO) {
        val m = mimeType.lowercase()
        val format = when {
            "mpegurl" in m -> "hls"
            "dash" in m -> "dash"
            "matroska" in m -> "mkv"
            "audio" in m -> "mp3"
            else -> "mp4"
        }
        val query = "t=v&u=" + enc(url) + "&k=(null)&videoName=" + enc(title.take(60)) + "&videoFormat=$format"
        // "launch" starts Roku's built-in player; "input" only reaches it if it's already open,
        // and the Roku answers OK either way, so check that something is actually playing.
        val attempts = listOf(
            "http://$host:8060/launch/15985?$query",
            "http://$host:8060/input/15985?$query",
            "http://$host:8060/launch/15985?t=v&u=${enc(url)}&k=(null)",
        )
        val codes = mutableListOf<Int>()
        for (target in attempts) {
            val code = runCatching { rokuPostCode(target) }.getOrDefault(-1)
            if (code == 403) throw CastException(
                "The Roku refused. On the Roku, open Settings › System › Advanced system settings › Control by mobile apps › Network access and set it to Default or Permissive.",
            )
            codes += code
            if (code !in 200..299) continue
            if (rokuStartedPlaying(host)) return@withContext
        }
        throw CastException(
            "This Roku didn't start the video (answers: ${codes.joinToString()}). " +
                "Some Roku models don't let apps send video. Use Screen mirroring (Smart View) to this Roku instead.",
        )
    }

    /** Waits up to ~8 s for the Roku's player to open with our video. */
    private suspend fun rokuStartedPlaying(host: String): Boolean {
        repeat(8) {
            kotlinx.coroutines.delay(1_000)
            val state = runCatching {
                http.newCall(Request.Builder().url("http://$host:8060/query/media-player").build()).execute().use { it.body?.string() }
            }.getOrNull().orEmpty()
            val s = Regex("state=\"([a-z]+)\"").find(state)?.groupValues?.get(1)
            if (s == "play" || s == "buffer" || s == "startup" || s == "pause") return true
            if (s == "error") return false
        }
        return false
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun rokuPostCode(url: String): Int {
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
        return http.newCall(request).execute().use { it.code }
    }

    suspend fun rokuKey(host: String, key: String) = withContext(Dispatchers.IO) { rokuPost("http://$host:8060/keypress/$key") }

    /** Reads the Roku's player state; null when it doesn't say. */
    suspend fun rokuStatus(host: String): RemoteStatus? = withContext(Dispatchers.IO) {
        val xml = http.newCall(Request.Builder().url("http://$host:8060/query/media-player").build()).execute()
            .use { it.body?.string() } ?: return@withContext null
        RokuProtocol.parsePlayer(xml)
    }

    private fun rokuPost(url: String) {
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 403) throw CastException("The Roku refused. On the Roku, turn on Settings › System › Advanced system settings › Control by mobile apps › Network access.")
            if (!response.isSuccessful) throw CastException("The Roku didn't accept the video (${response.code}).")
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
