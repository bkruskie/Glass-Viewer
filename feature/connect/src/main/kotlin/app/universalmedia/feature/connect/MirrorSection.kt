package app.universalmedia.feature.connect

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.ui.components.FeatureCard
import app.universalmedia.core.ui.components.SectionHeader
import app.universalmedia.media.mirror.MirrorService
import app.universalmedia.media.mirror.MirrorState
import app.universalmedia.media.mirror.MirrorStatus
import app.universalmedia.media.mirror.MirrorTarget
import app.universalmedia.media.mirror.ReceiverDiscovery

private const val RECEIVER_DOWNLOAD = "github.com/bkruskie/Glass-Viewer/releases/latest/download/UniversalMediaReceiver.apk"

/** "Mirror your screen": receiver apps found on the Wi-Fi, any web browser, and the live session. */
@Composable
internal fun MirrorSection(discovery: ReceiverDiscovery) {
    val context = LocalContext.current
    val status by MirrorState.status.collectAsStateWithLifecycle()
    val receivers by discovery.receivers.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<MirrorTarget?>(null) }

    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    // Android asks the user every time before the screen can be captured.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = pending
        pending = null
        val data = result.data
        if (target != null && result.resultCode == Activity.RESULT_OK && data != null) {
            MirrorService.start(context, target, result.resultCode, data)
        }
    }
    fun mirrorTo(target: MirrorTarget) {
        MirrorService.clearError()
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return
        pending = target
        consent.launch(manager.createScreenCaptureIntent())
    }

    SectionHeader("Mirror your screen")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val s = status) {
            is MirrorStatus.Active -> ActiveMirror(s)
            is MirrorStatus.Starting -> Text("Starting mirroring to ${s.target.name}…", style = MaterialTheme.typography.bodyMedium)
            is MirrorStatus.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            MirrorStatus.Idle -> Unit
        }

        if (status !is MirrorStatus.Active) {
            receivers.forEach { receiver ->
                FeatureCard(
                    Icons.Rounded.Tv, receiver.name, "Receiver app · smooth, sharp picture",
                    onClick = { mirrorTo(receiver) },
                )
            }
            FeatureCard(
                Icons.Rounded.Cast, "Use Smart View / Screen cast",
                "Your phone's built-in mirroring, with sound. Works with most Samsung, LG, Roku and other TVs.",
                tint = MaterialTheme.colorScheme.tertiary,
                onClick = { openSystemMirroring(context) },
            )
            FeatureCard(
                Icons.Rounded.Language, "Any TV with a web browser",
                "Nothing to install. Shows an address to open on the TV.",
                tint = MaterialTheme.colorScheme.secondary,
                onClick = { mirrorTo(MirrorTarget.Browser) },
            )
            Text(
                "Android TV, Google TV or Fire TV? Install the free receiver app on the TV for the best picture: " +
                    "open the TV's browser or the Downloader app and enter $RECEIVER_DOWNLOAD",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Receiver app", "https://$RECEIVER_DOWNLOAD"))
                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy receiver app link") }
        }
    }
}

/**
 * Opens the phone's own screen mirroring (Samsung Smart View, or Android's Cast screen),
 * which can use the TV's built-in "Screen mirroring" input. Apps aren't allowed to use it directly.
 */
private fun openSystemMirroring(context: android.content.Context) {
    val pm = context.packageManager
    val candidates = listOfNotNull(
        pm.getLaunchIntentForPackage("com.samsung.android.smartmirroring"),
        android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS),
        android.content.Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: android.content.ActivityNotFoundException) {
            // try the next option
        } catch (e: SecurityException) {
            // try the next option
        }
    }
    Toast.makeText(
        context,
        "Swipe down from the top and tap Smart View (or Screen cast) in quick settings.",
        Toast.LENGTH_LONG,
    ).show()
}

@Composable
private fun ActiveMirror(status: MirrorStatus.Active) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ScreenShare, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Mirroring to ${status.target.name}", style = MaterialTheme.typography.titleMedium)
        }
        status.address?.let { address ->
            Spacer(Modifier.padding(top = 8.dp))
            Text("On the TV's web browser, open:", style = MaterialTheme.typography.bodySmall)
            Text(address, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Everything on your screen is shown on the TV. Notifications too. Stop any time from here or the notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        FilledTonalButton(onClick = { MirrorService.stop(context) }, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Rounded.StopScreenShare, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Stop mirroring")
        }
    }
}
