package app.universalmedia.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import app.universalmedia.media.cast.NetworkTv
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputHdmi
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.database.MediaItemDao
import app.universalmedia.core.database.MediaItemEntity
import app.universalmedia.core.model.formatDuration
import app.universalmedia.core.ui.components.FeatureCard
import app.universalmedia.core.ui.components.MediaThumbnail
import app.universalmedia.core.ui.components.SectionHeader
import app.universalmedia.media.cast.ActiveCast
import app.universalmedia.media.cast.CastDevice
import app.universalmedia.media.cast.CastItem
import app.universalmedia.media.cast.CastKind
import app.universalmedia.media.cast.CastManager
import app.universalmedia.media.mirror.ReceiverDiscovery

private data class InfoCard(val key: String, val icon: ImageVector, val title: String, val subtitle: String, val details: String)

private val moreWays = listOf(
    InfoCard(
        "transfer", Icons.Rounded.SwapHoriz, "Send to a device",
        "Coming soon: move files between phones and computers on your Wi-Fi.",
        "Transfers will go directly between devices on your network with a one-time pairing code. Nothing passes through the internet.",
    ),
    InfoCard(
        "hdmi", Icons.Rounded.SettingsInputHdmi, "Cable (HDMI)",
        "Works today with a USB-C to HDMI adapter",
        "On phones that support video output, a USB-C to HDMI adapter shows your screen on any TV with an HDMI port. No Wi-Fi needed.",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    cast: CastManager,
    mirrorDiscovery: ReceiverDiscovery,
    mediaItemDao: MediaItemDao,
    nowPlaying: CastItem?,
    pendingItem: CastItem?,
    onPendingConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val devices by cast.devices.collectAsStateWithLifecycle()
    val active by cast.active.collectAsStateWithLifecycle()
    val searching by cast.searching.collectAsStateWithLifecycle()
    val tvAddress by cast.tvBrowserAddress.collectAsStateWithLifecycle()
    val networkTvs by cast.networkTvs.collectAsStateWithLifecycle()
    var pickFor by remember { mutableStateOf<CastDevice?>(null) }
    var openInfo by rememberSaveable { mutableStateOf<String?>(null) }

    DisposableEffect(cast) {
        cast.startDiscovery()
        onDispose { cast.stopDiscovery() }
    }

    fun choose(device: CastDevice) {
        val item = pendingItem
        if (item != null) {
            cast.cast(device, item)
            onPendingConsumed()
        } else {
            pickFor = device
        }
    }

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
            active?.let { NowCasting(it, cast) }

            pendingItem?.let {
                Text(
                    "Choose a TV to play “${it.title}”",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
            }

            SectionHeader("Play on a TV")
            if (searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(2.dp))
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tvAddress == null && devices.isEmpty()) {
                    FeatureCard(
                        Icons.Rounded.Wifi,
                        "Connect to Wi-Fi",
                        "Your phone and TV need to be on the same Wi-Fi network to find each other.",
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                devices.forEach { device ->
                    FeatureCard(
                        icon = when (device.kind) {
                            CastKind.GOOGLE_CAST -> Icons.Rounded.Cast
                            CastKind.DLNA, CastKind.ROKU -> Icons.Rounded.Tv
                            CastKind.TV_BROWSER -> Icons.Rounded.Language
                        },
                        title = device.name,
                        subtitle = device.detail ?: when (device.kind) {
                            CastKind.GOOGLE_CAST -> "Chromecast / Google TV"
                            CastKind.DLNA -> "Smart TV (DLNA)"
                            CastKind.ROKU -> "Roku · experimental"
                            CastKind.TV_BROWSER -> "Open the address in the TV's browser"
                        },
                        tint = if (device.kind == CastKind.TV_BROWSER) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        onClick = { choose(device) },
                    )
                }
                if (devices.none { it.kind != CastKind.TV_BROWSER } && !searching && tvAddress != null) {
                    Text(
                        "No TVs found yet. Make sure the TV is on and on the same Wi-Fi. " +
                            "Any TV with a web browser can still use the address above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            WifiTvsSection(networkTvs.filter { it.kind == NetworkTv.Kind.TV_OTHER || it.kind == NetworkTv.Kind.AIRPLAY })

            MirrorSection(mirrorDiscovery)

            SectionHeader("More ways")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                moreWays.forEach { card ->
                    FeatureCard(card.icon, card.title, card.subtitle, onClick = { openInfo = card.key })
                }
            }
        }
    }

    pickFor?.let { device ->
        PickMediaSheet(
            device = device,
            nowPlaying = nowPlaying,
            mediaItemDao = mediaItemDao,
            onPick = { item ->
                pickFor = null
                cast.cast(device, item)
            },
            onDismiss = { pickFor = null },
        )
    }

    moreWays.firstOrNull { it.key == openInfo }?.let { info ->
        AlertDialog(
            onDismissRequest = { openInfo = null },
            icon = { Icon(info.icon, contentDescription = null) },
            title = { Text(info.title) },
            text = { Text(info.details) },
            confirmButton = { TextButton(onClick = { openInfo = null }) { Text("Got it") } },
        )
    }
}

@Composable
private fun NowCasting(active: ActiveCast, cast: CastManager) {
    val status = active.status
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
    ) {
        Text("Playing on ${active.device.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(active.item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val error = active.error
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            active.connecting -> Text("Connecting…", style = MaterialTheme.typography.bodySmall)
        }
        if (status.durationMs > 0) {
            Slider(
                value = if (dragging) dragValue else status.positionMs.toFloat().coerceAtMost(status.durationMs.toFloat()),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { cast.seekTo(dragValue.toLong()); dragging = false },
                valueRange = 0f..status.durationMs.toFloat(),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(status.positionMs), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(status.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { cast.changeVolume(up = false) }) { Icon(Icons.Rounded.VolumeDown, contentDescription = "Volume down") }
            IconButton(onClick = { cast.seekTo((status.positionMs - 10_000).coerceAtLeast(0)) }) {
                Icon(Icons.Rounded.Replay10, contentDescription = "Back 10 seconds")
            }
            FilledIconButton(onClick = { if (status.playing) cast.pause() else cast.play() }, modifier = Modifier.size(56.dp)) {
                Icon(if (status.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (status.playing) "Pause" else "Play")
            }
            IconButton(onClick = { cast.seekTo(status.positionMs + 10_000) }) {
                Icon(Icons.Rounded.Forward10, contentDescription = "Forward 10 seconds")
            }
            IconButton(onClick = { cast.changeVolume(up = true) }) { Icon(Icons.Rounded.VolumeUp, contentDescription = "Volume up") }
        }
        TextButton(onClick = cast::stop, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Rounded.Stop, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Stop casting")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickMediaSheet(
    device: CastDevice,
    nowPlaying: CastItem?,
    mediaItemDao: MediaItemDao,
    onPick: (CastItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val library by remember(mediaItemDao) { mediaItemDao.observeAll() }.collectAsStateWithLifecycle(emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(
            "Play on ${device.name}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp)) {
            if (nowPlaying != null) {
                item {
                    PickRow(nowPlaying.title, "Playing on this phone", null, false) { onPick(nowPlaying) }
                }
            }
            if (library.isEmpty() && nowPlaying == null) {
                item {
                    Text(
                        "Your library is empty. Save or add a video first.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            items(library, key = { it.id }) { item ->
                PickRow(
                    item.title,
                    item.durationMs?.let(::formatDuration) ?: "",
                    item.thumbnailPath,
                    item.mimeType?.startsWith("audio") == true,
                ) { onPick(item.toCastItem()) }
            }
        }
    }
}

private fun MediaItemEntity.toCastItem() = CastItem(title = title, uri = uri, mimeType = mimeType)

@Composable
private fun PickRow(title: String, subtitle: String, thumbnail: String?, isAudio: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaThumbnail(thumbnail, isAudio, Modifier.size(width = 80.dp, height = 45.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** TVs that answered on the Wi-Fi but can't take video from the app as they are set up, with the step that works. */
@Composable
private fun WifiTvsSection(tvs: List<NetworkTv>) {
    if (tvs.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf<NetworkTv?>(null) }
    SectionHeader("Other TVs on your Wi-Fi")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tvs.forEach { tv ->
            FeatureCard(
                Icons.Rounded.Tv,
                tv.name,
                listOfNotNull(listOfNotNull(tv.brand, tv.model).joinToString(" ").ifBlank { null }, tv.advice).joinToString(" · "),
                tint = MaterialTheme.colorScheme.tertiary,
                onClick = { open = tv },
            )
        }
    }
    open?.let { tv ->
        AlertDialog(
            onDismissRequest = { open = null },
            icon = { Icon(Icons.Rounded.Tv, contentDescription = null) },
            title = { Text(tv.name) },
            text = {
                Text(
                    listOfNotNull(
                        listOfNotNull(tv.brand, tv.model).joinToString(" ").ifBlank { null },
                        tv.host.ifBlank { null }?.let { "Address: $it" },
                        tv.advice,
                        "After changing a TV setting, come back here: the TV shows up under Play on a TV within about 15 seconds.",
                    ).joinToString("\n\n"),
                )
            },
            confirmButton = {
                TextButton(onClick = { open = null; openSystemMirroring(context) }) { Text("Open Smart View") }
            },
            dismissButton = { TextButton(onClick = { open = null }) { Text("Close") } },
        )
    }
}
