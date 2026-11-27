package app.universalmedia.navigation

import android.app.Application
import android.widget.Toast
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            browserViewModel.openShared(incomingUrl)
            navController.navigateTopLevel(TopDestination.BROWSER.route)
            onIncomingConsumed()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showBottomBar = destination?.route != SETTINGS_ROUTE && webViewPool.fullscreen == null
    val openSettings = { navController.navigate(SETTINGS_ROUTE) { launchSingleTop = true } }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
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
            NavHost(
                navController = navController,
                startDestination = TopDestination.BROWSER.route,
                modifier = Modifier.padding(padding),
                enterTransition = { fadeIn(tween(160)) },
                exitTransition = { fadeOut(tween(160)) },
            ) {
                composable(TopDestination.BROWSER.route) {
                    BrowserScreen(browserViewModel, webViewPool, onOpenSettings = openSettings)
                }
                composable(TopDestination.DOWNLOADS.route) {
                    DownloadsScreen(
                        container.database.downloadDao(),
                        onOpenBrowser = { navController.navigateTopLevel(TopDestination.BROWSER.route) },
                    )
                }
                composable(TopDestination.PLAYER.route) {
                    PlayerScreen(
                        container.database.mediaItemDao(),
                        onOpenLibrary = { navController.navigateTopLevel(TopDestination.LIBRARY.route) },
                    )
                }
                composable(TopDestination.LIBRARY.route) {
                    LibraryScreen(libraryViewModel, onOpenSettings = openSettings)
                }
                composable(TopDestination.CONNECT.route) {
                    ConnectScreen(onOpenSettings = openSettings)
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
        FullscreenVideoHost(webViewPool)
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
