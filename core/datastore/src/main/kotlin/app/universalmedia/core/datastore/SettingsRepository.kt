package app.universalmedia.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.universalmedia.core.model.AppSettings
import app.universalmedia.core.model.SearchEngine
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Single source of truth for [AppSettings]. Stored on-device only. */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.settingsDataStore

    private object Keys {
        val detectionEnabled = booleanPreferencesKey("detection_enabled")
        val detectionNotifications = booleanPreferencesKey("detection_notifications")
        val searchEngine = stringPreferencesKey("search_engine")
        val javascriptEnabled = booleanPreferencesKey("javascript_enabled")
        val blockThirdPartyCookies = booleanPreferencesKey("block_third_party_cookies")
        val wifiOnlyDownloads = booleanPreferencesKey("wifi_only_downloads")
        val maxConcurrentDownloads = intPreferencesKey("max_concurrent_downloads")
        val pureBlackTheme = booleanPreferencesKey("pure_black_theme")
        val downloadTreeUri = stringPreferencesKey("download_tree_uri")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            detectionEnabled = this[Keys.detectionEnabled] ?: d.detectionEnabled,
            detectionNotifications = this[Keys.detectionNotifications] ?: d.detectionNotifications,
            searchEngine = this[Keys.searchEngine]
                ?.let { name -> SearchEngine.entries.firstOrNull { it.name == name } }
                ?: d.searchEngine,
            javascriptEnabled = this[Keys.javascriptEnabled] ?: d.javascriptEnabled,
            blockThirdPartyCookies = this[Keys.blockThirdPartyCookies] ?: d.blockThirdPartyCookies,
            wifiOnlyDownloads = this[Keys.wifiOnlyDownloads] ?: d.wifiOnlyDownloads,
            maxConcurrentDownloads = (this[Keys.maxConcurrentDownloads] ?: d.maxConcurrentDownloads).coerceIn(1, 6),
            pureBlackTheme = this[Keys.pureBlackTheme] ?: d.pureBlackTheme,
            downloadTreeUri = this[Keys.downloadTreeUri]?.ifBlank { null },
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.detectionEnabled] = s.detectionEnabled
        this[Keys.detectionNotifications] = s.detectionNotifications
        this[Keys.searchEngine] = s.searchEngine.name
        this[Keys.javascriptEnabled] = s.javascriptEnabled
        this[Keys.blockThirdPartyCookies] = s.blockThirdPartyCookies
        this[Keys.wifiOnlyDownloads] = s.wifiOnlyDownloads
        this[Keys.maxConcurrentDownloads] = s.maxConcurrentDownloads.coerceIn(1, 6)
        this[Keys.pureBlackTheme] = s.pureBlackTheme
        val tree = s.downloadTreeUri
        if (tree != null) this[Keys.downloadTreeUri] = tree else remove(Keys.downloadTreeUri)
    }
}
