package app.universalmedia

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        val container = (application as UniversalMediaApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
            val pending by incomingUrl.collectAsStateWithLifecycle()
            UniversalMediaTheme(pureBlack = settings.pureBlackTheme) {
                UniversalMediaRoot(
                    container = container,
                    webViewPool = webViewPool,
                    incomingUrl = pending,
                    onIncomingConsumed = { incomingUrl.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        webViewPool.destroyAll()
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
