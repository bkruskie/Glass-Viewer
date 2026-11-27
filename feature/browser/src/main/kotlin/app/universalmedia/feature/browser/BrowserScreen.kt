package app.universalmedia.feature.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.database.BookmarkEntity
import app.universalmedia.core.ui.components.FeatureCard
import app.universalmedia.core.ui.components.GlowIcon
import app.universalmedia.core.ui.components.SectionHeader

internal enum class BrowserSheet { NONE, TABS, MEDIA, BOOKMARKS, HISTORY }

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    pool: WebViewPool,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tab = state.activeTab
    var sheet by rememberSaveable { mutableStateOf(BrowserSheet.NONE) }
    val webView = remember(tab.id) {
        val fresh = pool.peek(tab.id) == null
        pool.obtain(tab.id, viewModel).also {
            // Restore a tab whose WebView was destroyed (e.g. process trimmed it) but whose URL we still know.
            if (fresh && tab.pendingLoad == null && tab.url.isNotBlank()) it.loadUrl(tab.url)
        }
    }

    // Pause timers and media of a page that is not on screen.
    DisposableEffect(webView) {
        webView.onResume()
        onDispose { webView.onPause() }
    }

    // Keep WebViews in sync with open tabs and settings.
    LaunchedEffect(state.tabs.map { it.id }) { pool.retainOnly(state.tabs.map { it.id }.toSet()) }
    LaunchedEffect(webView, state.settings.javascriptEnabled, state.settings.blockThirdPartyCookies) {
        pool.applySettings(webView, state.settings)
    }
    LaunchedEffect(tab.id, tab.pendingLoad) {
        tab.pendingLoad?.let {
            webView.loadUrl(it)
            viewModel.consumePendingLoad(tab.id)
        }
    }

    BackHandler(enabled = tab.canGoBack && !tab.isStartPage) { webView.goBack() }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AddressBar(
                url = tab.url,
                isLoading = tab.isLoading,
                onSubmit = viewModel::submit,
                onReload = { webView.reload() },
                onStop = { webView.stopLoading() },
                modifier = Modifier.weight(1f),
            )
            TabCountButton(count = state.tabs.size, onClick = { sheet = BrowserSheet.TABS })
            BrowserMenu(
                tab = tab,
                isBookmarked = state.isBookmarked,
                onBack = { webView.goBack() },
                onForward = { webView.goForward() },
                onNewTab = viewModel::newTabAndSelect,
                onHome = viewModel::goHome,
                onToggleBookmark = viewModel::toggleBookmark,
                onBookmarks = { sheet = BrowserSheet.BOOKMARKS },
                onHistory = { sheet = BrowserSheet.HISTORY },
                onScan = { pool.scanForMedia(tab.id, viewModel) },
                onSettings = onOpenSettings,
            )
        }

        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (tab.isLoading) {
                LinearProgressIndicator(
                    progress = { tab.progress / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = Color.Transparent,
                )
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(tab.id) {
                AndroidView(
                    factory = { webView.apply { (parent as? ViewGroup)?.removeView(this) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (tab.isStartPage) {
                StartPage(
                    bookmarks = state.bookmarks,
                    onOpen = { viewModel.open(it) },
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                )
            }
            AnimatedVisibility(
                visible = tab.detected.isNotEmpty() && !tab.isStartPage,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { sheet = BrowserSheet.MEDIA },
                    icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                    text = {
                        val n = tab.detected.size
                        Text(if (n == 1) "1 media found" else "$n media found")
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }

    BrowserSheets(
        sheet = sheet,
        state = state,
        onDismiss = { sheet = BrowserSheet.NONE },
        viewModel = viewModel,
    )
}

@Composable
private fun AddressBar(
    url: String,
    isLoading: Boolean,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(AddressBarResolver.display(url))) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(url, focused) {
        if (!focused) value = TextFieldValue(AddressBarResolver.display(url))
    }

    val leading = when {
        focused || url.isBlank() -> Icons.Rounded.Search
        url.startsWith("https://") -> Icons.Rounded.Lock
        else -> Icons.Rounded.Public
    }

    TextField(
        value = value,
        onValueChange = { value = it },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = { Text("Search or enter address", style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(leading, contentDescription = null, modifier = Modifier.size(18.dp)) },
        trailingIcon = {
            when {
                focused && value.text.isNotEmpty() -> IconButton(onClick = { value = TextFieldValue("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear")
                }
                !focused && url.isNotBlank() -> IconButton(onClick = if (isLoading) onStop else onReload) {
                    Icon(
                        if (isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                        contentDescription = if (isLoading) "Stop" else "Reload",
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onGo = {
            onSubmit(value.text)
            focusManager.clearFocus()
            keyboard?.hide()
        }),
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.onFocusChanged { s ->
            if (s.isFocused && !focused) value = TextFieldValue(url, TextRange(0, url.length))
            focused = s.isFocused
        },
    )
}

@Composable
private fun TabCountButton(count: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(22.dp)
                .border(1.6.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BrowserMenu(
    tab: BrowserTab,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onHome: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onHistory: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Menu") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 8.dp)) {
                IconButton(onClick = { open = false; onBack() }, enabled = tab.canGoBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { open = false; onForward() }, enabled = tab.canGoForward) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward")
                }
                IconButton(onClick = { open = false; onToggleBookmark() }, enabled = tab.url.isNotBlank()) {
                    Icon(
                        if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = if (isBookmarked) "Remove bookmark" else "Add bookmark",
                    )
                }
                IconButton(onClick = { open = false; onHome() }) {
                    Icon(Icons.Rounded.Home, contentDescription = "Start page")
                }
            }
            HorizontalDivider()
            MenuItem("New tab", Icons.Rounded.Add) { open = false; onNewTab() }
            MenuItem("Scan page for media", Icons.Rounded.TravelExplore, enabled = tab.url.isNotBlank()) {
                open = false; onScan()
            }
            MenuItem("Bookmarks", Icons.Rounded.Bookmark) { open = false; onBookmarks() }
            MenuItem("History", Icons.Rounded.History) { open = false; onHistory() }
            MenuItem("Settings", Icons.Rounded.Settings) { open = false; onSettings() }
        }
    }
}

@Composable
private fun MenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartPage(bookmarks: List<BookmarkEntity>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        GlowIcon(Icons.Rounded.PlayCircle, size = 80)
        Spacer(Modifier.height(16.dp))
        Text("Universal Media", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Browse, find and keep the media you care about.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (bookmarks.isNotEmpty()) {
            SectionHeader("Bookmarks", Modifier.fillMaxWidth())
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                bookmarks.take(12).forEach { b ->
                    AssistChip(onClick = { onOpen(b.url) }, label = { Text(b.title, maxLines = 1) })
                }
            }
        }

        SectionHeader("How it works", Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FeatureCard(
                Icons.Rounded.TravelExplore,
                "Automatic detection",
                "Videos and audio on the pages you visit appear behind the media button.",
            )
            FeatureCard(
                Icons.Rounded.Share,
                "Share from any app",
                "In another app, tap Share → Universal Media to open and analyse a link here.",
                tint = MaterialTheme.colorScheme.secondary,
            )
            FeatureCard(
                Icons.Rounded.Shield,
                "Private by design",
                "No accounts, ads or analytics. History and files stay on this phone.",
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Shows a page's fullscreen video above the whole app. Place at the root of the UI. */
@Composable
fun FullscreenVideoHost(pool: WebViewPool) {
    val fullscreen = pool.fullscreen ?: return
    val activity = LocalContext.current.findActivity()

    DisposableEffect(fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler { pool.exitFullscreen() }

    key(fullscreen) {
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    (fullscreen.view.parent as? ViewGroup)?.removeView(fullscreen.view)
                    addView(
                        fullscreen.view,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                }
            },
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
