package app.universalmedia.navigation

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.database.DownloadEntity
import app.universalmedia.core.model.DownloadStatus
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.universalmedia.media.playback.PlayRequest
import app.universalmedia.media.cast.CastItem
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.MediaVariant
import app.universalmedia.media.download.MediaDownloadManager
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.universalmedia.AppContainer
import app.universalmedia.CrashReporter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import app.universalmedia.BuildConfig
import app.universalmedia.feature.browser.BrowserScreen
import app.universalmedia.feature.browser.BrowserViewModel
import app.universalmedia.feature.browser.FullscreenVideoHost
import app.universalmedia.feature.browser.WebViewPool
import app.universalmedia.feature.connect.ConnectScreen
import app.universalmedia.feature.downloads.DownloadsScreen
import app.universalmedia.feature.library.LibraryScreen
import app.universalmedia.feature.library.LibraryViewModel
import app.universalmedia.feature.player.PlayerScreen
import app.universalmedia.feature.settings.SettingsScreen

enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    BROWSER("browser", "Browser", Icons.Rounded.Language),
    DOWNLOADS("downloads", "Downloads", Icons.Rounded.Download),
    PLAYER("player", "Player", Icons.Rounded.PlayCircle),
    LIBRARY("library", "Library", Icons.Rounded.VideoLibrary),
    CONNECT("connect", "Connect", Icons.Rounded.Cast),
}

private const val SETTINGS_ROUTE = "settings"

@Composable
fun UniversalMediaRoot(
    container: AppContainer,
    webViewPool: WebViewPool,
    incomingUrl: String?,
    onIncomingConsumed: () -> Unit,
    inPictureInPicture: Boolean = false,
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Activity-scoped state holders: they outlive switching between sections.
    val browserViewModel: BrowserViewModel = viewModel(
        factory = BrowserViewModel.factory(
            container.database.bookmarkDao(),
            container.database.historyDao(),
            container.settings,
            container.detectionEngine,
        ),
    )
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModel.factory(
            context.applicationContext as Application,
            container.database.mediaItemDao(),
        ),
    )

    // ---- Saving media -------------------------------------------------------------
    val scope = rememberCoroutineScope()
    var duplicate by remember { mutableStateOf<Pair<DetectedMedia, MediaVariant?>?>(null) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val save: (DetectedMedia, MediaVariant?, Boolean) -> Unit = { media, variant, allowDuplicate ->
        scope.launch {
            when (val result = container.downloads.enqueue(media, variant, allowDuplicate)) {
                is MediaDownloadManager.EnqueueResult.Queued -> {
                    Toast.makeText(context, "Added to downloads", Toast.LENGTH_SHORT).show()
                    val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    if (needsPermission && !askedNotifications) {
                        askedNotifications = true
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                MediaDownloadManager.EnqueueResult.Duplicate -> duplicate = media to variant
                is MediaDownloadManager.EnqueueResult.NotSupported ->
                    Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- Playing media ------------------------------------------------------------
    var playerFullscreen by remember { mutableStateOf(false) }
    val playback = container.playback
    val playAndShow: (PlayRequest) -> Unit = { request ->
        playback.play(request)
        navController.navigateTopLevel(TopDestination.PLAYER.route)
    }
    // Fullscreen video: landscape, no system bars.
    DisposableEffect(playerFullscreen) {
        val activity = context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (playerFullscreen) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            // Fullscreen follows the video: wide videos landscape, vertical videos portrait.
            val size = playback.player.videoSize
            activity?.requestedOrientation = when {
                size.width <= 0 || size.height <= 0 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                size.width * size.pixelWidthHeightRatio >= size.height * 1.1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                size.height >= size.width * size.pixelWidthHeightRatio * 1.1f -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            if (playerFullscreen) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    // ---- Casting to a TV -----------------------------------------------------------
    var pendingCast by remember { mutableStateOf<CastItem?>(null) }
    val playingNow by playback.current.collectAsStateWithLifecycle()

    // When a download finishes while the app is open, ask how to watch it.
    val allDownloads by remember(container) { container.downloads.observe() }.collectAsStateWithLifecycle(emptyList())
    var seenCompleted by remember { mutableStateOf<Set<String>?>(null) }
    var finishedPrompt by remember { mutableStateOf<DownloadEntity?>(null) }
    LaunchedEffect(allDownloads) {
        val done = allDownloads.filter { it.status == DownloadStatus.COMPLETED.name }.map { it.id }.toSet()
        val before = seenCompleted
        if (before != null) {
            allDownloads.firstOrNull { it.id in done && it.id !in before && it.destinationUri != null }
                ?.let { finishedPrompt = it }
        }
        seenCompleted = done
    }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            browserViewModel.openShared(incomingUrl)
            navController.navigateTopLevel(TopDestination.BROWSER.route)
            onIncomingConsumed()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination?.route != SETTINGS_ROUTE && webViewPool.fullscreen == null &&
        !playerFullscreen && !inPictureInPicture
    val openSettings = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } }
    // Landscape phones and wide screens: tabs move to a side rail so content keeps its height.
    val configuration = LocalConfiguration.current
    val useRail = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenWidthDp >= 600

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar && !useRail) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        TopDestination.entries.forEach { item ->
                            val selected = destination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateTopLevel(item.route) },
                                icon = { Icon(item.icon, contentDescription = null) },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
          Row(Modifier.fillMaxSize().padding(padding)) {
            if (showBottomBar && useRail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.weight(1f))
                    TopDestination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = { navController.navigateTopLevel(item.route) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            colors = NavigationRailItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            NavHost(
                navController = navController,
                startDestination = TopDestination.BROWSER.route,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (useRail && showBottomBar) {
                            // Keep content clear of a navigation bar or camera cutout on the far side.
                            Modifier.windowInsetsPadding(
                                WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.End),
                            )
                        } else {
                            Modifier
                        },
                    ),
                enterTransition = { fadeIn(tween(160)) },
                exitTransition = { fadeOut(tween(160)) },
            ) {
                composable(TopDestination.BROWSER.route) {
                    BrowserScreen(
                        browserViewModel,
                        webViewPool,
                        onOpenSettings = openSettings,
                        onSave = { media, variant -> save(media, variant, false) },
                        onPlay = { media, variant ->
                            val format = variant?.format ?: media.format
                            playAndShow(
                                PlayRequest(
                                    uri = variant?.url ?: media.mediaUrl,
                                    title = media.title ?: "Video",
                                    mimeType = PlayRequest.mimeFor(format),
                                    referer = media.pageUrl,
                                ),
                            )
                        },
                    )
                }
                composable(TopDestination.DOWNLOADS.route) {
                    DownloadsScreen(
                        container.downloads,
                        onOpenBrowser = { navController.navigateTopLevel(TopDestination.BROWSER.route) },
                        onPlay = { item ->
                            item.destinationUri?.let { uri ->
                                playAndShow(PlayRequest(uri = uri, title = item.title, mimeType = item.mimeType))
                            }
                        },
                    )
                }
                composable(TopDestination.PLAYER.route) {
                    PlayerScreen(
                        playback = playback,
                        mediaItemDao = container.database.mediaItemDao(),
                        fullscreen = playerFullscreen,
                        inPictureInPicture = inPictureInPicture,
                        onFullscreenChange = { playerFullscreen = it },
                        onOpenLibrary = { navController.navigateTopLevel(TopDestination.LIBRARY.route) },
                        onCast = { request ->
                            pendingCast = CastItem(request.title, request.uri, request.mimeType)
                            navController.navigateTopLevel(TopDestination.CONNECT.route)
                        },
                    )
                }
                composable(TopDestination.LIBRARY.route) {
                    LibraryScreen(
                        libraryViewModel,
                        onOpenSettings = openSettings,
                        onPlay = { item ->
                            playAndShow(
                                PlayRequest(
                                    uri = item.uri,
                                    title = item.title,
                                    mimeType = item.mimeType,
                                    libraryItemId = item.id,
                                    startPositionMs = item.playbackPositionMs,
                                ),
                            )
                        },
                    )
                }
                composable(TopDestination.CONNECT.route) {
                    ConnectScreen(
                        cast = container.cast,
                        mirrorDiscovery = container.mirrorDiscovery,
                        mediaItemDao = container.database.mediaItemDao(),
                        nowPlaying = playingNow?.let { CastItem(it.title, it.uri, it.mimeType) },
                        pendingItem = pendingCast,
                        onPendingConsumed = {
                            pendingCast = null
                            playback.player.pause()
                        },
                        onOpenSettings = openSettings,
                    )
                }
                composable(SETTINGS_ROUTE) {
                    SettingsScreen(
                        repository = container.settings,
                        versionName = BuildConfig.VERSION_NAME,
                        onBack = { navController.popBackStack() },
                        onClearBrowsingData = {
                            webViewPool.clearBrowsingData()
                            browserViewModel.clearHistory()
                            Toast.makeText(context, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
          }
        }
        FullscreenVideoHost(webViewPool)
    }

    finishedPrompt?.let { item ->
        val uri = item.destinationUri
        AlertDialog(
            onDismissRequest = { finishedPrompt = null },
            title = { Text("Download finished") },
            text = { Text("“${item.title}” is saved to your library. How do you want to watch it?") },
            confirmButton = {
                Row {
                    TextButton(onClick = { finishedPrompt = null }) { Text("Later") }
                    TextButton(onClick = {
                        finishedPrompt = null
                        if (uri != null) openWithChooser(context, uri, item.mimeType, item.title)
                    }) { Text("Other app") }
                    TextButton(onClick = {
                        finishedPrompt = null
                        if (uri != null) playAndShow(PlayRequest(uri = uri, title = item.title, mimeType = item.mimeType))
                    }) { Text("Play here") }
                }
            },
        )
    }

    // Offer the report from a previous crash (stored on this device only).
    var crashReport by remember { mutableStateOf(CrashReporter.lastCrash(context)) }
    crashReport?.let { report ->
        val clipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = {
                CrashReporter.clear(context)
                crashReport = null
            },
            title = { Text("The app closed unexpectedly") },
            text = {
                Text(
                    "Universal Media stopped last time. You can copy the technical details and send them " +
                        "to whoever maintains the app. Nothing has been sent anywhere.\n\n" +
                        report.lineSequence().take(8).joinToString("\n"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(context, "Details copied", Toast.LENGTH_SHORT).show()
                    CrashReporter.clear(context)
                    crashReport = null
                }) { Text("Copy details") }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashReporter.clear(context)
                    crashReport = null
                }) { Text("Dismiss") }
            },
        )
    }

    duplicate?.let { (media, variant) ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("Already downloaded") },
            text = { Text("“${media.title ?: "This media"}” is already in your downloads. Download it again?") },
            confirmButton = {
                TextButton(onClick = {
                    duplicate = null
                    save(media, variant, true)
                }) { Text("Download again") }
            },
            dismissButton = { TextButton(onClick = { duplicate = null }) { Text("Cancel") } },
        )
    }
}

/** Lets the user pick any installed player (VLC, MX Player, gallery…) for a saved file. */
private fun openWithChooser(context: Context, uri: String, mimeType: String?, title: String) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(uri), mimeType ?: "video/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(view, title))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No other app can play this file", Toast.LENGTH_SHORT).show()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
