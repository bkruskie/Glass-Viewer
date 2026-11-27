package app.universalmedia

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.model.AppSettings
import app.universalmedia.core.ui.theme.UniversalMediaTheme
import app.universalmedia.feature.browser.SharedTextParser
import app.universalmedia.feature.browser.WebViewPool
import app.universalmedia.media.detection.UrlUtils
import app.universalmedia.navigation.UniversalMediaRoot
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val webViewPool by lazy { WebViewPool(this) }

    /** A link handed to us by another app (share sheet or "open with"). */
    private val incomingUrl = MutableStateFlow<String?>(null)

    private var inPictureInPicture by mutableStateOf(false)

    private val container: AppContainer get() = (application as UniversalMediaApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val pending by incomingUrl.collectAsStateWithLifecycle()
            UniversalMediaTheme(pureBlack = settings.pureBlackTheme) {
                UniversalMediaRoot(
                    container = container,
                    webViewPool = webViewPool,
                    incomingUrl = pending,
                    onIncomingConsumed = { incomingUrl.value = null },
                    inPictureInPicture = inPictureInPicture,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Leaving the app while a video plays on the Player screen shrinks it to picture-in-picture. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val playback = container.playback
        if (!playback.isPlayerScreenVisible || !playback.isPlayingVideo()) return
        val size = playback.player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() / size.height
            when {
                r > 2.39f -> Rational(239, 100)
                r < 0.42f -> Rational(42, 100)
                else -> Rational(size.width, size.height)
            }
        } else {
            Rational(16, 9)
        }
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onDestroy() {
        webViewPool.destroyAll()
        if (isFinishing) container.playback.stop()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_SEND -> SharedTextParser.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> intent.dataString?.takeIf(UrlUtils::isHttp)
            else -> null
        }
        if (url != null) incomingUrl.value = url
    }
}
