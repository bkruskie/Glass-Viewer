package app.universalmedia.media.cast

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

class CastException(message: String) : Exception(message)

enum class CastKind { GOOGLE_CAST, DLNA, ROKU, TV_BROWSER }

data class CastDevice(val id: String, val name: String, val kind: CastKind, val detail: String? = null)

/** Something to play on the TV. [uri] may be content:// (served by the phone) or http(s). */
data class CastItem(val title: String, val uri: String, val mimeType: String?)

data class ActiveCast(
    val device: CastDevice,
    val item: CastItem,
    val status: RemoteStatus = RemoteStatus(),
    val connecting: Boolean = true,
    val error: String? = null,
)

/**
 * One API over every way of reaching a TV: Google Cast, DLNA/UPnP and the TV's
 * own web browser. Local files are streamed by [LocalMediaServer], so the TV
 * pulls the video itself and the phone stays free.
 * Call from the main thread.
 */
class CastManager(context: Context, http: OkHttpClient, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val server = LocalMediaServer(appContext)
    private val dlna = DlnaClient(appContext, http)
    private val google = GoogleCastClient(appContext)

    private val dlnaRenderers = MutableStateFlow<Map<String, DlnaRenderer>>(emptyMap())
    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _active = MutableStateFlow<ActiveCast?>(null)
    val active: StateFlow<ActiveCast?> = _active.asStateFlow()

    private val _networkTvs = MutableStateFlow<List<NetworkTv>>(emptyList())
    /** Every TV seen on the Wi-Fi, castable or not, with advice for each. */
    val networkTvs: StateFlow<List<NetworkTv>> = _networkTvs.asStateFlow()
    private val airplay = AirPlayDiscovery(appContext)

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** Address to open on a TV's web browser, e.g. http://192.168.1.20:41234/tv (null off Wi-Fi). */
    val tvBrowserAddress: StateFlow<String?> get() = server.baseUrl

    private var discoveryJob: Job? = null
    private var statusJob: Job? = null

    init {
        google.onSessionEnded = { _active.value = null }
        google.onSessionFailed = { message -> _active.update { it?.copy(connecting = false, error = message) } }
        scope.launch(Dispatchers.Main) { google.routes.collect { rebuildDevices() } }
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        scope.launch(Dispatchers.IO) { server.start() }.invokeOnCompletion { scope.launch(Dispatchers.Main) { rebuildDevices() } }
        google.startDiscovery()
        airplay.start { publishTvs() }
        discoveryJob = scope.launch {
            while (isActive) {
                _searching.value = true
                val found = runCatching { dlna.discover() }.getOrDefault(emptyList())
                if (found.isNotEmpty()) dlnaRenderers.update { old -> old + found.associateBy { it.usn } }
                val tvs = runCatching { dlna.scanAll() }.getOrDefault(emptyList())
                if (tvs.isNotEmpty()) scannedTvs = (scannedTvs + tvs.associateBy { it.id })
                publishTvs()
                _searching.value = false
                withContext(Dispatchers.Main) { rebuildDevices() }
                delay(15_000)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _searching.value = false
        google.stopDiscovery()
        airplay.stop()
    }

    @Volatile private var scannedTvs: Map<String, NetworkTv> = emptyMap()

    private fun publishTvs() {
        val scanned = scannedTvs.values.toList()
        val names = scanned.map { it.name.lowercase() }.toSet()
        val airplayOnly = airplay.names.filter { it.lowercase() !in names }.map {
            NetworkTv("airplay:$it", it, TvClassifier.brandOf(it), null, "", NetworkTv.Kind.AIRPLAY)
        }
        _networkTvs.value = (scanned + airplayOnly).sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
        scope.launch(Dispatchers.Main) { rebuildDevices() }
    }

    private fun rebuildDevices() {
        val list = mutableListOf<CastDevice>()
        google.routes.value.forEach { list += CastDevice(it.id, it.name, CastKind.GOOGLE_CAST, it.description?.toString()) }
        dlnaRenderers.value.values.sortedBy { it.friendlyName }.forEach { r ->
            list += CastDevice(r.usn, r.friendlyName, CastKind.DLNA, listOfNotNull(r.manufacturer, r.modelName).joinToString(" ").ifBlank { null })
        }
        _networkTvs.value.filter { it.kind == NetworkTv.Kind.ROKU }.forEach { tv ->
            list += CastDevice(tv.id, tv.name, CastKind.ROKU, listOfNotNull("Roku", tv.model, "· experimental").joinToString(" "))
        }
        server.baseUrl.value?.let { base ->
            list += CastDevice("tv-browser", "Any TV with a web browser", CastKind.TV_BROWSER, "Open $base/tv on the TV")
        }
        _devices.value = list
    }

    /** Starts playing [item] on [device]. Errors are reported in [active]. */
    fun cast(device: CastDevice, item: CastItem) {
        stopCurrent(keepState = false)
        _active.value = ActiveCast(device, item)
        scope.launch {
            try {
                val mime = item.mimeType?.substringBefore(';')?.ifBlank { null } ?: guessMime(item.uri)
                val url = withContext(Dispatchers.IO) { reachableUrl(item, mime) }
                when (device.kind) {
                    CastKind.GOOGLE_CAST -> withContext(Dispatchers.Main) { google.load(device.id, url, item.title, mime) }
                    CastKind.DLNA -> {
                        val renderer = dlnaRenderers.value[device.id] ?: throw CastException("That TV is no longer available.")
                        dlna.load(renderer, url, item.title, mime)
                    }
                    CastKind.ROKU -> dlna.rokuPlay(rokuHost(device), url, mime)
                    CastKind.TV_BROWSER -> server.webReceiver.load(url, item.title)
                }
                _active.update { it?.copy(connecting = false, error = null) }
                startStatusPolling()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _active.update { it?.copy(connecting = false, error = e.message ?: "Couldn't start playback on the TV.") }
            }
        }
    }

    fun play() = control { device ->
        when (device.kind) {
            CastKind.GOOGLE_CAST -> withContext(Dispatchers.Main) { google.play() }
            CastKind.DLNA -> renderer(device)?.let { dlna.play(it) }
            CastKind.ROKU -> dlna.rokuKey(rokuHost(device), "Play")
            CastKind.TV_BROWSER -> server.webReceiver.send("play")
        }
    }

    fun pause() = control { device ->
        when (device.kind) {
            CastKind.GOOGLE_CAST -> withContext(Dispatchers.Main) { google.pause() }
            CastKind.DLNA -> renderer(device)?.let { dlna.pause(it) }
            CastKind.ROKU -> dlna.rokuKey(rokuHost(device), "Play") // Roku's Play key toggles
            CastKind.TV_BROWSER -> server.webReceiver.send("pause")
        }
    }

    fun seekTo(ms: Long) = control { device ->
        when (device.kind) {
            CastKind.GOOGLE_CAST -> withContext(Dispatchers.Main) { google.seek(ms) }
            CastKind.DLNA -> renderer(device)?.let { dlna.seek(it, ms) }
            CastKind.ROKU -> {
                val now = _active.value?.status?.positionMs ?: 0
                dlna.rokuKey(rokuHost(device), if (ms >= now) "Fwd" else "Rev")
            }
            CastKind.TV_BROWSER -> server.webReceiver.send("seek", ms)
        }
    }

    fun changeVolume(up: Boolean) = control { device ->
        when (device.kind) {
            CastKind.GOOGLE_CAST -> withContext(Dispatchers.Main) { google.changeVolume(if (up) 0.1 else -0.1) }
            CastKind.DLNA -> renderer(device)?.let { dlna.changeVolume(it, if (up) 5 else -5) }
            CastKind.ROKU -> dlna.rokuKey(rokuHost(device), if (up) "VolumeUp" else "VolumeDown")
            CastKind.TV_BROWSER -> server.webReceiver.send(if (up) "vol+" else "vol-")
        }
    }

    /** Stops playback on the TV and ends the session. */
    fun stop() = stopCurrent(keepState = false)

    private fun stopCurrent(keepState: Boolean) {
        val current = _active.value ?: return
        statusJob?.cancel()
        if (!keepState) _active.value = null
        scope.launch {
            runCatching {
                when (current.device.kind) {
                    CastKind.GOOGLE_CAST -> withContext(Dispatchers.Main) { google.stop() }
                    CastKind.DLNA -> renderer(current.device)?.let { dlna.stop(it) }
                    CastKind.ROKU -> dlna.rokuKey(rokuHost(current.device), "Home")
                    CastKind.TV_BROWSER -> server.webReceiver.load(null, "")
                }
            }
        }
    }

    private fun control(block: suspend (CastDevice) -> Unit) {
        val device = _active.value?.device ?: return
        scope.launch {
            try {
                block(device)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _active.update { it?.copy(error = e.message ?: "The TV didn't respond.") }
            }
        }
    }

    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = scope.launch {
            while (isActive) {
                val device = _active.value?.device ?: break
                val status = runCatching {
                    when (device.kind) {
                        CastKind.GOOGLE_CAST -> withContext(Dispatchers.Main) { google.status() }
                        CastKind.DLNA -> renderer(device)?.let { dlna.status(it) }
                        CastKind.ROKU -> dlna.rokuStatus(rokuHost(device))
                        CastKind.TV_BROWSER -> server.webReceiver.let { w ->
                            RemoteStatus(w.positionMs, w.durationMs, playing = !w.paused)
                        }
                    }
                }.getOrNull()
                if (status != null) _active.update { it?.copy(status = status) }
                delay(1_000)
            }
        }
    }

    private fun rokuHost(device: CastDevice): String =
        _networkTvs.value.firstOrNull { it.id == device.id }?.host ?: throw CastException("That Roku is no longer available.")

    private fun renderer(device: CastDevice): DlnaRenderer? = dlnaRenderers.value[device.id]

    /** TVs can't read content:// links, so local files go through the phone's media server. */
    private fun reachableUrl(item: CastItem, mime: String): String {
        val uri = Uri.parse(item.uri)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> item.uri
            "content", "file" -> server.publish(uri, mime, item.title)
                ?: throw CastException("Connect to Wi-Fi to play on a TV.")
            else -> throw CastException("This item can't be played on a TV.")
        }
    }

    private fun guessMime(uri: String): String {
        val ext = uri.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ts" -> "video/mp2t"
            else -> "video/mp4"
        }
    }
}
