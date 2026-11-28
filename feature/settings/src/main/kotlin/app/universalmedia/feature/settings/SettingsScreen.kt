package app.universalmedia.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.universalmedia.core.datastore.SettingsRepository
import app.universalmedia.core.model.AppSettings
import app.universalmedia.core.model.SearchEngine
import app.universalmedia.core.ui.components.SectionHeader
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    versionName: String,
    onBack: () -> Unit,
    onClearBrowsingData: () -> Unit,
) {
    val settings by remember(repository) { repository.settings }.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { repository.update(transform) }
    }

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            update { it.copy(downloadTreeUri = uri.toString()) }
        }
    }

    var pickEngine by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
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
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            SectionHeader("Media detection")
            SwitchRow(
                "Detect media automatically",
                "Look for videos and audio on the pages you open in the app's browser",
                settings.detectionEnabled,
            ) { v -> update { it.copy(detectionEnabled = v) } }
            ClickRow(
                "See what other apps are playing",
                "Optional. Shows titles from other apps on the browser's start page. Turn on or off with Notification access",
            ) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }

            SectionHeader("Browser")
            ClickRow("Search engine", settings.searchEngine.displayName) { pickEngine = true }
            SwitchRow(
                "JavaScript",
                "Most video sites need it. Turning it off also turns off in-page media scanning",
                settings.javascriptEnabled,
            ) { v -> update { it.copy(javascriptEnabled = v) } }
            SwitchRow(
                "Block third-party cookies",
                "Stops most cross-site tracking",
                settings.blockThirdPartyCookies,
            ) { v -> update { it.copy(blockThirdPartyCookies = v) } }
            ClickRow("Clear browsing data", "Cookies, site data, cache and history") { confirmClear = true }

            SectionHeader("Downloads")
            ClickRow(
                "Save location",
                settings.downloadTreeUri?.let(::folderLabel) ?: DEFAULT_FOLDER_LABEL,
            ) { folderPicker.launch(null) }
            settings.downloadTreeUri?.let { current ->
                ClickRow("Use default location", DEFAULT_FOLDER_LABEL) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(current),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    update { it.copy(downloadTreeUri = null) }
                }
            }
            SwitchRow(
                "Download on Wi-Fi only",
                "Wait for Wi-Fi before starting downloads",
                settings.wifiOnlyDownloads,
            ) { v -> update { it.copy(wifiOnlyDownloads = v) } }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Simultaneous downloads: ${settings.maxConcurrentDownloads}", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = settings.maxConcurrentDownloads.toFloat(),
                    onValueChange = { v -> update { it.copy(maxConcurrentDownloads = v.roundToInt()) } },
                    valueRange = 1f..6f,
                    steps = 4,
                )
            }

            SectionHeader("Appearance")
            SwitchRow(
                "Pure black",
                "Darker background for OLED screens",
                settings.pureBlackTheme,
            ) { v -> update { it.copy(pureBlackTheme = v) } }

            SectionHeader("About")
            ClickRow("Universal Media $versionName", "Free and open. No accounts, ads, analytics or cloud services.") {}
            Text(
                "The app only connects to the websites you open and the devices you choose on your own network.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }

    if (pickEngine) {
        AlertDialog(
            onDismissRequest = { pickEngine = false },
            title = { Text("Search engine") },
            text = {
                Column {
                    SearchEngine.entries.forEach { engine ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = settings.searchEngine == engine,
                                    role = Role.RadioButton,
                                    onClick = {
                                        update { it.copy(searchEngine = engine) }
                                        pickEngine = false
                                    },
                                )
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = settings.searchEngine == engine, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(engine.displayName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickEngine = false }) { Text("Close") } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear browsing data?") },
            text = { Text("This signs you out of websites and deletes cookies, site data, cache and browsing history. It can't be undone. Your library and downloads are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearBrowsingData()
                    confirmClear = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private const val DEFAULT_FOLDER_LABEL = "Movies › Universal Media (music in Music › Universal Media)"

/** "primary:Download/Videos" → "Download/Videos". */
private fun folderLabel(treeUri: String): String = runCatching {
    DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)).substringAfter(':').ifBlank { "Internal storage" }
}.getOrDefault("Chosen folder")

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
