package app.universalmedia.feature.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.ui.components.FeatureCard
import app.universalmedia.media.transfer.QrCode
import app.universalmedia.media.transfer.TransferProgress
import app.universalmedia.media.transfer.TransferServer

/** "Send to a device": swap files with a computer or phone on the same Wi-Fi, through its web browser. */
@Composable
internal fun TransferCard(transfer: TransferServer) {
    val session by transfer.session.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    FeatureCard(
        Icons.Rounded.SwapHoriz,
        "Send to a computer or phone",
        if (session != null) "Sharing now · tap to see the address" else "Over your Wi-Fi, in any web browser. Nothing to install.",
        badge = if (session != null) "On" else null,
        onClick = { open = true },
    )
    if (open) TransferSheet(transfer, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(transfer: TransferServer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session by transfer.session.collectAsStateWithLifecycle()
    var noWifi by remember { mutableStateOf(false) }

    // Keep the screen on while this is open so big transfers aren't cut off.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Wi-Fi transfer", style = MaterialTheme.typography.titleLarge)
            val s = session
            if (s == null) {
                Text(
                    "Swap files with a computer or another phone on the same Wi-Fi. They open an address in their web browser " +
                        "to download your library or send files here. Nothing goes through the internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (noWifi) {
                    Text("Connect to Wi-Fi first.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { noWifi = transfer.start() == null }, modifier = Modifier.fillMaxWidth()) { Text("Start sharing") }
            } else {
                Text(
                    "On the other device, open this address in a web browser, or scan the code with its camera:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    s.address,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                val qr = remember(s.address) { QrCode.bitmap(s.address).asImageBitmap() }
                Image(
                    qr,
                    contentDescription = "QR code for the transfer address",
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(200.dp)
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Transfer address", s.address))
                        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.address)
                        context.startActivity(Intent.createChooser(send, "Send the address"))
                    }) { Text("Share") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { transfer.stop() }) { Text("Stop") }
                }
                Text(
                    "Only devices that have this address can connect. Sharing stops after 20 minutes without use. " +
                        "Keep this screen open during big transfers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.transfers.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transfers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (s.transfers.any { it.done }) TextButton(onClick = { transfer.clearFinished() }) { Text("Clear finished") }
                    }
                    s.transfers.asReversed().forEach { TransferRow(it) }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(t: TransferProgress) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (t.sending) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
            contentDescription = if (t.sending) "Sent from this phone" else "Received on this phone",
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val total = t.total
            val error = t.error
            when {
                error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                t.done -> Text(
                    if (t.sending) "Sent · ${size(t.bytes)}" else "Saved to this phone · ${size(t.bytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                total != null && total > 0 -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { (t.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("${size(t.bytes)} of ${size(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

private fun size(b: Long): String = when {
    b < 1_000_000 -> "${b / 1000} KB"
    b < 1_000_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "%.2f GB".format(b / 1e9)
}
