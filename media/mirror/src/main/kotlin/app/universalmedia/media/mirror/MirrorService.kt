package app.universalmedia.media.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where to mirror to. */
data class MirrorTarget(
    val id: String,
    val name: String,
    val kind: Kind,
    val host: String? = null,
    val port: Int = 0,
    /** The receiver plays sound (receiver app 1.1+). */
    val audio: Boolean = false,
) {
    enum class Kind { RECEIVER_APP, WEB_BROWSER }

    companion object {
        val Browser = MirrorTarget("browser", "Any TV with a web browser", Kind.WEB_BROWSER)
    }
}

sealed interface MirrorStatus {
    data object Idle : MirrorStatus
    data class Starting(val target: MirrorTarget) : MirrorStatus
    /** [address] is set for browser mirroring: the page to open on the TV. */
    data class Active(val target: MirrorTarget, val address: String?, val sound: Sound = Sound.NONE) : MirrorStatus

    enum class Sound { NONE, ON, RECEIVER_TOO_OLD, ANDROID_TOO_OLD, NO_PERMISSION, UNAVAILABLE }
    data class Failed(val message: String) : MirrorStatus
}

/** Shared state between the service and the UI. */
object MirrorState {
    internal val mutable = MutableStateFlow<MirrorStatus>(MirrorStatus.Idle)
    val status: StateFlow<MirrorStatus> = mutable.asStateFlow()
}

/**
 * Foreground service that captures the screen (with the user's permission each
 * time) and sends it to the chosen target. Rotation is followed by resizing the
 * capture, so landscape videos fill a TV.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var output: MirrorOutput? = null
    private var audio: Any? = null // AudioCapture on Android 10+
    private var size: Pair<Int, Int> = 0 to 0
    private var target: MirrorTarget? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMirroring(null)
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val chosen = MirrorTarget(
            id = intent?.getStringExtra(EXTRA_TARGET_ID).orEmpty(),
            name = intent?.getStringExtra(EXTRA_TARGET_NAME).orEmpty(),
            kind = MirrorTarget.Kind.valueOf(intent?.getStringExtra(EXTRA_TARGET_KIND) ?: MirrorTarget.Kind.WEB_BROWSER.name),
            host = intent?.getStringExtra(EXTRA_TARGET_HOST),
            port = intent?.getIntExtra(EXTRA_TARGET_PORT, 0) ?: 0,
        )
        // Must be in the foreground (typed mediaProjection) before using the permission.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(chosen.name),
            when {
                Build.VERSION.SDK_INT < 29 -> 0
                // Capturing app sound counts as recording on Android 11+, so it needs the microphone type too.
                Build.VERSION.SDK_INT >= 30 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            },
        )
        if (data == null || resultCode != Activity.RESULT_OK) {
            stopMirroring("Screen capture wasn't allowed.")
            return START_NOT_STICKY
        }
        try {
            start(chosen, resultCode, data)
        } catch (e: Exception) {
            stopMirroring("Couldn't start mirroring: ${e.message ?: e.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun start(chosen: MirrorTarget, resultCode: Int, data: Intent) {
        stopOutputs()
        target = chosen
        MirrorState.mutable.value = MirrorStatus.Starting(chosen)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = manager.getMediaProjection(resultCode, data) ?: throw IllegalStateException("No permission")
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopMirroring(null) }
            }
        }, main)

        val maxSide = if (chosen.kind == MirrorTarget.Kind.RECEIVER_APP) 1920 else 1280
        val (w, h) = captureSize(maxSide)
        size = w to h
        val failure: (String) -> Unit = { message -> main.post { stopMirroring(message) } }
        val out = when (chosen.kind) {
            MirrorTarget.Kind.RECEIVER_APP -> H264Output(chosen.host.orEmpty(), chosen.port, w, h, failure).also { video ->
                val sound = startAudio(chosen, p, video)
                MirrorState.mutable.value = MirrorStatus.Active(chosen, null, sound)
            }
            MirrorTarget.Kind.WEB_BROWSER -> MjpegOutput(
                w, h,
                onReady = { port, key ->
                    val ip = localIpv4(this)
                    MirrorState.mutable.value = MirrorStatus.Active(chosen, ip?.let { "http://$it:$port/$key/" })
                    if (ip == null) failure("Connect to Wi-Fi to mirror to a TV.")
                },
                onFailure = failure,
            )
        }
        output = out
        display = p.createVirtualDisplay(
            "UniversalMediaMirror", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, out.surface, null, main,
        )
    }

    /** Follow rotation: resize the capture so the picture isn't letterboxed. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val out = output ?: return
        val d = display ?: return
        val maxSide = if (target?.kind == MirrorTarget.Kind.RECEIVER_APP) 1920 else 1280
        val newSize = captureSize(maxSide)
        if (newSize == size) return
        size = newSize
        runCatching {
            val surface = out.resize(newSize.first, newSize.second)
            d.resize(newSize.first, newSize.second, resources.displayMetrics.densityDpi)
            d.surface = surface
        }
    }

    /** Screen size scaled so the long side is at most [maxSide], rounded to multiples of 16 for the encoder. */
    private fun captureSize(maxSide: Int): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val (rw, rh) = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        val scale = minOf(1f, maxSide.toFloat() / maxOf(rw, rh))
        fun round16(v: Float) = (v / 16f).toInt().coerceAtLeast(1) * 16
        return round16(rw * scale) to round16(rh * scale)
    }

    /** Starts sending sound when the receiver plays it and Android allows it; returns what the user should know. */
    private fun startAudio(chosen: MirrorTarget, p: MediaProjection, video: H264Output): MirrorStatus.Sound {
        if (!chosen.audio) return MirrorStatus.Sound.RECEIVER_TOO_OLD
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return MirrorStatus.Sound.ANDROID_TOO_OLD
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return MirrorStatus.Sound.NO_PERMISSION
        }
        return runCatching {
            audio = AudioCapture(p) { packet -> video.sendExtra(packet) }
            MirrorStatus.Sound.ON
        }.getOrDefault(MirrorStatus.Sound.UNAVAILABLE)
    }

    private fun stopOutputs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (audio as? AudioCapture)?.stop()
        audio = null
        runCatching { display?.release() }
        display = null
        runCatching { output?.stop() }
        output = null
    }

    private fun stopMirroring(error: String?) {
        stopOutputs()
        runCatching { projection?.stop() }
        projection = null
        MirrorState.mutable.value = if (error != null) MirrorStatus.Failed(error) else MirrorStatus.Idle
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopOutputs()
        runCatching { projection?.stop() }
        if (MirrorState.mutable.value !is MirrorStatus.Failed) MirrorState.mutable.value = MirrorStatus.Idle
        super.onDestroy()
    }

    private fun notification(targetName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Screen mirroring", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("Mirroring your screen")
            .setContentText(targetName)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "mirroring"
        private const val NOTIFICATION_ID = 4242
        private const val ACTION_STOP = "app.universalmedia.mirror.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_TARGET_ID = "target_id"
        private const val EXTRA_TARGET_NAME = "target_name"
        private const val EXTRA_TARGET_KIND = "target_kind"
        private const val EXTRA_TARGET_HOST = "target_host"
        private const val EXTRA_TARGET_PORT = "target_port"

        /** Call after the user granted screen capture (MediaProjectionManager.createScreenCaptureIntent()). */
        fun start(context: Context, target: MirrorTarget, resultCode: Int, data: Intent) {
            MirrorState.mutable.value = MirrorStatus.Starting(target)
            val intent = Intent(context, MirrorService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_TARGET_ID, target.id)
                .putExtra(EXTRA_TARGET_NAME, target.name)
                .putExtra(EXTRA_TARGET_KIND, target.kind.name)
                .putExtra(EXTRA_TARGET_HOST, target.host)
                .putExtra(EXTRA_TARGET_PORT, target.port)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MirrorService::class.java).setAction(ACTION_STOP))
        }

        fun clearError() {
            if (MirrorState.mutable.value is MirrorStatus.Failed) MirrorState.mutable.value = MirrorStatus.Idle
        }

        internal fun localIpv4(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            val fromNetworks = cm?.allNetworks?.firstNotNullOfOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return@firstNotNullOfOrNull null
                }
                cm.getLinkProperties(network)?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
            }
            return fromNetworks ?: runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
            }.getOrNull()
        }
    }
}
