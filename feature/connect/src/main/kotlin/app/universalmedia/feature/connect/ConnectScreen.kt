package app.universalmedia.feature.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.universalmedia.core.ui.components.FeatureCard
import app.universalmedia.core.ui.components.SectionHeader

private data class InfoCard(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val details: String,
    val badge: String?,
)

private val actions = listOf(
    InfoCard(
        "cast", Icons.Rounded.Cast, "Cast to a TV",
        "Send a video to the TV. Your phone stays free.",
        "The TV fetches the video straight from your phone over Wi-Fi, so you can lock the phone or use other apps. " +
            "Pause, seek and volume stay on your phone.",
        "Soon",
    ),
    InfoCard(
        "mirror", Icons.Rounded.ScreenShare, "Mirror your screen",
        "Show everything on your phone on a big screen.",
        "Android asks for permission every time; you can stop at any moment from the notification. " +
            "Resolution and quality adjust to your Wi-Fi.",
        "Soon",
    ),
    InfoCard(
        "transfer", Icons.Rounded.SwapHoriz, "Send to a device",
        "Move files between phones and computers on your Wi-Fi.",
        "Transfers go directly between devices on your network with a one-time pairing code. Nothing passes through the internet.",
        "Soon",
    ),
)

private val receivers = listOf(
    InfoCard(
        "gcast", Icons.Rounded.Cast, "Chromecast & Google TV",
        "Also TVs with Chromecast built in (Sony, TCL, Hisense, Philips...)",
        "Uses Google Cast with the free built-in receiver, so no account or setup is needed.",
        null,
    ),
    InfoCard(
        "dlna", Icons.Rounded.Tv, "Smart TVs (DLNA)",
        "Samsung, LG, Panasonic, Roku TVs, many soundbars and consoles",
        "Most smart TVs made in the last decade can play from a phone over DLNA/UPnP. Your TV appears automatically when it is on the same Wi-Fi.",
        null,
    ),
    InfoCard(
        "web", Icons.Rounded.Language, "Any TV with a web browser",
        "Fire TV, older smart TVs, game consoles, computers",
        "If nothing else works, the app shows a short local address. Open it in the TV's browser to play the video from your phone.",
        null,
    ),
    InfoCard(
        "companion", Icons.Rounded.Computer, "Android TV & Fire TV app",
        "Free receiver app for screen mirroring and the best quality",
        "A small companion app for Android TV, Google TV and Fire TV receives mirroring and casts with the lowest delay.",
        null,
    ),
    InfoCard(
        "hdmi", Icons.Rounded.SettingsInputHdmi, "Cable (HDMI)",
        "Works today with a USB-C to HDMI adapter",
        "On phones that support video out, connect a USB-C to HDMI adapter. Any TV with an HDMI port works, no Wi-Fi needed.",
        null,
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(onOpenSettings: () -> Unit) {
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Connect") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEach { card ->
                    FeatureCard(card.icon, card.title, card.subtitle, badge = card.badge, onClick = { openKey = card.key })
                }
            }
            SectionHeader("Works with your TV")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                receivers.forEach { card ->
                    FeatureCard(
                        card.icon, card.title, card.subtitle,
                        tint = MaterialTheme.colorScheme.secondary,
                        onClick = { openKey = card.key },
                    )
                }
            }
        }
    }

    val open = (actions + receivers).firstOrNull { it.key == openKey }
    if (open != null) {
        AlertDialog(
            onDismissRequest = { openKey = null },
            icon = { Icon(open.icon, contentDescription = null) },
            title = { Text(open.title) },
            text = { Text(open.details) },
            confirmButton = { TextButton(onClick = { openKey = null }) { Text("Got it") } },
        )
    }
}
