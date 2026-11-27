# Universal Media: Architecture & Technical Plan

Status: Milestone 1 (Foundation) implemented. Version `0.1.0-m1`.

This document covers the full architecture for every planned subsystem, the feasibility limits Android sets, and the plan for each milestone. Decisions are recorded as ADRs at the end.

---

## 1. Principles

| Principle | What it means in code |
|---|---|
| Zero cost, zero accounts | No backend, no paid APIs, no analytics SDKs. All dependencies are free; nearly all are open source (see §4). |
| Local first | Room + DataStore on device. The only network traffic is pages the user opens and devices the user picks on the LAN. |
| Modular | Feature modules never depend on each other. Engines (detection, downloads, casting...) sit behind interfaces. |
| Honest capability | The UI never implies a download is possible when it isn't. DRM is identified and explained, never removed. |
| Portable core | Models and the detection engine are pure Kotlin (no Android imports) so they can move to Kotlin Multiplatform for iOS/desktop. |
| Always shippable | Every milestone ends with a working, installable app. |

---

## 2. Project structure

```
UniversalMedia/
├── app/                       Application, MainActivity, navigation shell, DI container
├── core/
│   ├── model/                 [pure Kotlin] domain models: DetectedMedia, MediaFormat, Protection, AppSettings...
│   ├── ui/                    theme (dark "glass" palette), shared Compose components
│   ├── database/              Room database, entities, DAOs, exported schemas
│   └── datastore/             SettingsRepository (Preferences DataStore)
├── media/
│   ├── detection/             [pure Kotlin] classifier, HLS/DASH inspection, adapter registry   ← M1/M2
│   ├── download/              download engine, segment fetcher, remuxer                          ← M3
│   ├── playback/              Media3 player wrapper, session service                             ← M4
│   ├── server/                local HTTP media server (Range support) shared by cast/transfer   ← M5
│   ├── cast/                  Google Cast + DLNA/UPnP + web receiver behind one CastRoute API    ← M5
│   ├── mirror/                MediaProjection → MediaCodec → transports                          ← M6
│   └── transfer/              LAN discovery, pairing, file transfer                              ← M7
├── feature/
│   ├── browser/               WebView browser, tabs, history, bookmarks, in-page detection      ✅ M1
│   ├── downloads/             download list UI                                                   ✅ shell
│   ├── player/                player UI                                                          ✅ shell
│   ├── library/               library with import, search, filters, favourites                  ✅ M1
│   ├── connect/               cast / mirror / transfer hub                                       ✅ shell
│   └── settings/              all preferences                                                    ✅ M1
├── companion/                 (future) Android TV / Fire TV receiver app, desktop app
├── docs/ARCHITECTURE.md
└── .github/workflows/android.yml   free CI: tests + lint + debug APK
```

### Dependency rules

```
app ──► feature:* ──► core:ui, core:database, core:datastore, media:*
                        └──► core:model ◄── media:detection
```

- `feature:*` modules never depend on each other; the app module wires navigation between them.
- `media:*` engines expose interfaces and never import Compose.
- `core:model` and `media:detection` have no Android dependency (enforced by using the `kotlin.jvm` plugin).

---

## 3. Module responsibilities

| Module | Responsibility | Key types |
|---|---|---|
| `app` | Process entry, intent handling (share / VIEW), navigation graph, manual DI | `UniversalMediaApp`, `AppContainer`, `MainActivity`, `UniversalMediaRoot` |
| `core:model` | Immutable domain models shared everywhere | `DetectedMedia`, `MediaVariant`, `MediaFormat`, `Protection`, `Availability`, `AppSettings`, `DownloadStatus` |
| `core:ui` | Material 3 dark theme, typography, shapes, reusable components | `UniversalMediaTheme`, `EmptyState`, `FeatureCard`, `Pill`, `GlowIcon` |
| `core:database` | Persistent storage, schema migrations | `UniversalMediaDatabase`, 5 DAOs |
| `core:datastore` | User preferences | `SettingsRepository` |
| `media:detection` | Classify URLs, inspect manifests, route to site adapters, decide availability | `MediaDetectionEngine`, `SiteAdapter`, `GenericMediaAdapter`, `HlsPlaylistParser`, `DashManifestInspector`, `AvailabilityPolicy` |
| `feature:browser` | Media-oriented browser | `BrowserViewModel`, `WebViewPool`, `BrowserScreen` |
| `feature:library` | Local media management through the Storage Access Framework | `LibraryViewModel`, `LibraryScreen` |
| `feature:settings` | Preferences UI, clear browsing data | `SettingsScreen` |
| `feature:downloads/player/connect` | Screens that later milestones fill in | – |

---

## 4. Dependency selection

All versions are pinned in `gradle/libs.versions.toml`.

| Area | Library | License | Why / notes |
|---|---|---|---|
| Language | Kotlin 2.0.21 | Apache 2.0 | Compose compiler plugin built in |
| UI | Jetpack Compose (BOM 2024.12.01), Material 3, icons-extended | Apache 2.0 | Declarative, dark theming |
| Navigation | navigation-compose 2.8.5 | Apache 2.0 | String routes; one NavHost |
| Lifecycle | lifecycle 2.8.7, activity-compose 1.9.3 | Apache 2.0 | ViewModels, `collectAsStateWithLifecycle` |
| Database | Room 2.6.1 + KSP | Apache 2.0 | Flows, compile-time SQL checks, schema export |
| Settings | DataStore Preferences 1.1.1 | Apache 2.0 | Async, transactional |
| Browser | Android WebView + androidx.webkit 1.12.1 | Apache 2.0 | Safe Browsing toggle, feature detection |
| Async | kotlinx.coroutines 1.9.0 | Apache 2.0 | |
| **M2** HTTP | OkHttp 4.12 (+ MockWebServer for tests) | Apache 2.0 | Manifest fetch, downloads, Range requests |
| **M3** Background | WorkManager | Apache 2.0 | Resumable, constraint-aware (Wi-Fi only) |
| **M3/M4** Media | AndroidX Media3: exoplayer, hls, dash, session, ui, transformer, muxer | Apache 2.0 | Playback, HLS/DASH, remux segments to MP4 without FFmpeg |
| **M5** Cast | Google Cast SDK (play-services-cast-framework) | Free, proprietary | Uses the free Default Media Receiver; isolated in a `full` flavor so a fully open-source `foss` flavor can ship without it |
| **M5** LAN server | Ktor server (CIO engine) | Apache 2.0 | Range requests, WebSockets for the TV web receiver; Multiplatform for future desktop |
| **M5** DLNA | In-house SSDP + SOAP client (AVTransport, RenderingControl) | – | Small surface; avoids unmaintained Cling. jUPnP (EPL 2.0) is the fallback if TV quirks pile up |
| **M5/M7** Discovery | Android `NsdManager` (mDNS) | Platform | Companion apps and transfer peers |
| Tests | JUnit 4, androidx.test, Compose UI test, Room testing, Roborazzi (screenshots) | EPL/Apache | |

Deliberately excluded: FFmpeg (size and GPL/LGPL obligations; Media3 Transformer covers remuxing), Hilt (manual DI is enough at this size; see ADR-004), Firebase/Crashlytics/any analytics (privacy), AppCompat (Compose-only UI).

---

## 5. Core interfaces

Implemented now (`media:detection`):

```kotlin
interface SiteAdapter {
    val id: String
    val displayName: String
    fun matches(url: String): Boolean
    suspend fun resolve(request: ResolveRequest): ResolveResult
}

fun interface ManifestFetcher {            // OkHttp implementation in M2
    suspend fun fetchText(url: String, headers: Map<String, String>): String?
}

class MediaDetectionEngine(adapters: List<SiteAdapter>, fallback: SiteAdapter) {
    suspend fun resolve(request: ResolveRequest): ResolveResult   // Found | NotMedia | Unavailable
    fun isCandidate(url: String, mimeType: String?): Boolean      // cheap, thread-safe pre-filter
}
```

Planned (signatures fixed now so modules can be built independently):

```kotlin
// media:download (M3)
interface DownloadEngine {
    fun observe(): Flow<List<DownloadJob>>
    suspend fun enqueue(request: DownloadRequest): String   // returns job id
    suspend fun pause(id: String); suspend fun resume(id: String)
    suspend fun cancel(id: String); suspend fun retry(id: String)
}
interface DownloadStrategy {                  // one per delivery type
    fun supports(media: DetectedMedia): Boolean   // Progressive, HlsSegments, DashSegments
    suspend fun run(job: DownloadJob, sink: StorageSink, progress: (Long, Long?) -> Unit)
}

// media:playback (M4)
interface PlaybackController {
    val state: StateFlow<PlaybackState>
    fun play(item: PlayableItem, startPositionMs: Long = 0)
    fun pause(); fun seekTo(ms: Long); fun setSpeed(speed: Float)
    fun selectTrack(track: TrackRef)             // audio / subtitle
}

// media:cast (M5) – Chromecast, DLNA and the web receiver share one API
interface CastProvider {
    val discovered: Flow<List<CastDevice>>
    suspend fun connect(device: CastDevice): CastSession
}
interface CastSession {
    suspend fun load(item: PlayableItem)         // local files are served by media:server
    suspend fun play(); suspend fun pause(); suspend fun seekTo(ms: Long)
    val status: StateFlow<RemotePlaybackStatus>
    suspend fun disconnect()
}

// media:mirror (M6)
interface MirrorTransport {                      // companion TCP, browser WebSocket/MSE, DLNA HTTP-TS
    suspend fun open(target: CastDevice, config: EncoderConfig)
    fun sendVideo(frame: EncodedFrame); fun sendAudio(frame: EncodedFrame)
    suspend fun close()
}

// media:transfer (M7)
interface TransferService {
    val peers: Flow<List<Peer>>
    suspend fun pair(peer: Peer, code: String): PairedPeer
    fun send(peer: PairedPeer, files: List<Uri>): Flow<TransferProgress>
}
```

---

## 6. Data models

`core:model` (pure Kotlin, immutable):

- `MediaFormat`: MP4, WebM, MKV, MOV, 3GP, M4A, MP3, AAC, Ogg, Opus, FLAC, WAV, HLS, DASH, VTT, SRT, each with extensions and MIME types, and a `MediaKind` (VIDEO, AUDIO, STREAM_MANIFEST, SUBTITLE).
- `DetectedMedia`: id, media URL, page URL, title, host, format, duration, variants, `Protection`, `Availability`, origin, note.
- `MediaVariant`: URL, width/height, bitrate, codecs, frame rate, estimated size, audio-only flag, display label ("1080p · 5.0 Mbps").
- `Protection`: `None` | `StreamEncryption(method, keyUri)` (standard HLS AES) | `Drm(systems)`.
- `Availability`: SUPPORTED, UNSUPPORTED, INACCESSIBLE, AUTH_REQUIRED, PROTECTED (the five states the spec requires).
- `DownloadRequest`, `DownloadStatus`, `AppSettings`, `SearchEngine`.

---

## 7. Database design (Room, version 1)

| Table | Columns (key ones) | Indices | Notes |
|---|---|---|---|
| `bookmarks` | id, url, title, createdAt | url UNIQUE | Re-bookmarking replaces |
| `history` | id, url, title, visitedAt | visitedAt, url | Visits to the same URL within 30 min merge |
| `downloads` | id (UUID), mediaUrl, pageUrl, title, fileName, format, variantLabel, destinationUri, status, totalBytes, downloadedBytes, errorMessage, createdAt, updatedAt | status, mediaUrl, createdAt | Duplicate detection uses mediaUrl |
| `media_items` | id, uri (content://), title, mimeType, sizeBytes, durationMs, addedAt, lastPlayedAt, playbackPositionMs, isFavorite, folder, downloadId | uri UNIQUE, addedAt, lastPlayedAt | Downloads and imported files; persisted SAF permissions |
| `playlists` | id, name, createdAt | – | |
| `playlist_items` | playlistId, mediaId, position | PK(playlistId, mediaId), mediaId | Both FKs cascade on delete |

Planned additions: `download_segments` (M3, per-segment state for resumable HLS/DASH), `cast_devices` (M5, remembered receivers), `paired_peers` (M7, certificate fingerprints).

Migration policy: schemas are exported to `core/database/schemas/` and committed. Every version bump ships an explicit `Migration` plus a `MigrationTestHelper` test. Destructive migration is never enabled.

Settings live in DataStore, not Room (key/value, observed as `Flow<AppSettings>`).

---

## 8. Navigation

```
Bottom bar (always visible except Settings and fullscreen video)
├── Browser     start destination; tabs persist across sections
├── Downloads
├── Player
├── Library
└── Connect
Settings        pushed screen, reachable from Browser menu, Library and Connect
```

- Top-level switches use `popUpTo(start) { saveState = true }` + `restoreState`, so each section keeps its scroll and state.
- `BrowserViewModel` and `LibraryViewModel` are Activity-scoped; WebViews live in an Activity-scoped `WebViewPool`, so switching to Library and back never reloads a page.
- Incoming links (Share → Universal Media, or "open with" for http/https) open in a new browser tab and switch to Browser.
- Fullscreen web video is drawn above the whole app by `FullscreenVideoHost`, with system bars hidden.

---

## 9. Media discovery architecture

```
                 ┌───────────────────────── sources ─────────────────────────┐
 WebView requests│ shouldInterceptRequest (observe only)                     │
 In-page probe   │ <video>/<audio> currentSrc after load + "Scan page"       │
 Share intent    │ ACTION_SEND text → first URL                              │
 VIEW intent     │ http/https links opened with Universal Media              │
 (M2) Extension  │ Firefox add-on → universalmedia:// deep link              │
                 └───────────────┬───────────────────────────────────────────┘
                                 ▼
             MediaDetectionEngine.isCandidate()   ← cheap, on network thread
                                 ▼
             SiteAdapter registry → first match, else GenericMediaAdapter
                                 ▼
        classify → (HLS) HlsPlaylistParser / (DASH) DashManifestInspector
                                 ▼
        Protection → AvailabilityPolicy → DetectedMedia (+ variants)
                                 ▼
        Per-tab list → "N media found" button → sheet (quality, format, status)
```

Key behaviours:

- Segment requests (`.ts`, `.m4s`) are classified as `Segment` and ignored; only manifests and whole files are shown.
- `blob:` URLs (Media Source Extensions players) are not downloadable by themselves. The manifest that fed the player is normally also requested by the page and is caught by request observation.
- Requests continue to the network untouched; the app never rewrites or injects into page traffic.
- Site adapters are plain classes registered in `AppContainer`. Adding one touches no UI, browser or download code. Adapters that would violate a site's terms (e.g. YouTube stream deciphering) are not part of the project.
- Milestone 2 plugs in an OkHttp `ManifestFetcher` that forwards the page's cookies and referer, so quality lists appear for HLS/DASH and sign-in-only media resolves with the user's own session (`AUTH_REQUIRED` when it can't).

---

## 10. System-wide detection: feasibility

Android gives no app access to another app's network traffic, decoded video buffers or protected surfaces. The table separates what works across apps from what needs an integration and what is blocked.

| Method | Works across apps? | Gets an actual media source? | Verdict |
|---|---|---|---|
| In-app browser request observation | Inside our browser | Yes, direct files and manifests | ✅ Implemented (M1) |
| Share intent (`ACTION_SEND`) | Any app with a Share button | Gets the page link; the engine then resolves it | ✅ Implemented (M1); M2 adds instant analysis + originating app via `getReferrer()` |
| "Open with" for web links (`ACTION_VIEW`) | Any app that opens links | Same as share | ✅ Implemented (M1) |
| Clipboard check on app open | When our app is in the foreground (Android 10+ blocks background reads) | Link only | ✅ M2: "Analyse copied link?" chip |
| Firefox for Android extension | Firefox only (Chrome for Android has no extensions) | Yes, via the add-on's request observation | 🔶 M7: companion add-on sends a `universalmedia://` deep link |
| MediaSession (via Notification access) | Apps that publish a media session (most players) | No URL: title, artist, duration, package name only | 🔶 Optional, opt-in: "You're watching *X* in App Y" with a suggestion to share the link. Cannot download by itself |
| Accessibility service reading browser address bars | Technically yes | Page URL only | ⛔ Not shipped: privacy-invasive and against Google Play's Accessibility API policy. May be reconsidered only as an opt-in in sideloaded builds |
| MediaProjection + visual recognition | Anything on screen (not FLAG_SECURE / DRM, which capture as black) | No. Pixels only, never the original file | ⛔ Not used for "downloads". Screen recording is not an original-quality copy and is never presented as one |
| Reading other apps' traffic (VPN-based sniffing, root, private APIs) | – | – | ⛔ Out of scope by design (HTTPS hides URLs anyway; invasive) |

Product consequence: the fast path from any app is **Share → Universal Media** (one tap), the in-app browser gives fully automatic detection, and Firefox users get automatic detection through the add-on.

---

## 11. Protected media

The engine identifies protection and explains it; it never removes it.

| Detected | Example signal | Result |
|---|---|---|
| None | plain MP4, clear HLS/DASH | SUPPORTED |
| Standard HLS encryption | `#EXT-X-KEY:METHOD=AES-128` with identity key format | SUPPORTED: keys are delivered by the site to the user's own session, exactly as in a browser |
| DRM | HLS `KEYFORMAT` FairPlay/Widevine/PlayReady, DASH `ContentProtection` system IDs, generic CENC | PROTECTED: watch on the site; the sheet points to the service's own offline feature. No key extraction, no licence-server emulation |
| Needs sign-in | 401/403 from manifest fetch (M2) | AUTH_REQUIRED: sign in on the site in our browser, then retry |

Ownership, access authorisation and authorisation to remove a technical protection are tracked as three separate questions in the code (`Protection` vs `Availability` vs site adapter results). Only the first two can be satisfied by the app.

---

## 12. TVs: making "any TV" work

No single protocol is spoken by every TV, so casting uses layered routes behind one `CastProvider` API. The Connect screen shows whichever routes find a device.

| Route | Covers | Cost | Phone free during playback? |
|---|---|---|---|
| Google Cast (Default Media Receiver) | Chromecast, Google TV, Android TV, TVs with Chromecast built in | Free (no receiver registration needed) | Yes: TV pulls from the phone's local server |
| DLNA / UPnP AVTransport | Most Samsung, LG, Sony, Panasonic, Philips, Roku TVs, consoles, AV receivers | Free | Yes |
| Web receiver | Any TV, console or computer with a browser (Fire TV Silk, older smart TVs) | Free | Yes; the TV opens a short local address like `http://192.168.1.20:8080` |
| Companion receiver app | Android TV, Google TV, Fire TV | Free (our own app) | Yes; lowest latency, needed for mirroring |
| HDMI | Any TV with HDMI | Adapter | Works today via Android's own video output |

Local files are served by `media:server` (Ktor CIO) with HTTP Range support, a random port and a per-session token in the URL so other devices on the network can't browse the library. Online streams are cast by URL when the receiver can fetch them directly (with headers where the protocol allows), otherwise proxied through the phone.

---

## 13. Screen mirroring (M6)

Pipeline: `MediaProjection` (system consent each session; foreground service type `mediaProjection`) → `VirtualDisplay` → `MediaCodec` H.264 surface encoder (adaptive bitrate, 480p–1080p, 30/60 fps) → transport.

| Target | Transport | Expected latency |
|---|---|---|
| Companion app (Android/Fire TV) | H.264 Annex-B over TCP, decoded by MediaCodec | ~100–250 ms |
| TV browser | Fragmented MP4 over WebSocket into Media Source Extensions | ~0.5–1.5 s |
| DLNA renderer | Live MPEG-TS over HTTP | 2–5 s (TV buffering); fine for slideshows, poor for games |
| Chromecast | System "Cast screen" (opened via `Settings.ACTION_CAST_SETTINGS`) | System-managed |

Limits: apps using `FLAG_SECURE` and DRM video appear black (enforced by Android). Internal audio uses `AudioPlaybackCapture` (Android 10+) and only captures apps that allow it. Android removed Miracast for third-party apps, so Miracast-only TVs are reached through the system "Cast screen" where the phone maker supports it.

---

## 14. Local network transfer (M7)

Discovery via mDNS (`_universalmedia._tcp`). Pairing: the receiver shows a 6-digit code / QR containing its TLS certificate fingerprint; the sender pins it. Transfers are HTTPS over the LAN with chunked, resumable uploads (Range/offset), progress and cancellation. A computer without the app uses a browser page served by the phone (upload/download), so "phone ↔ computer" works before any desktop companion exists.

---

## 15. Privacy & security

- No analytics, crash reporting or ad SDKs. No network call happens except pages the user opens and devices on the user's LAN.
- `allowBackup=false`; history, cookies and library stay on device. "Clear browsing data" wipes cookies, site storage, cache and history.
- WebView: Safe Browsing on, file and content access off, no automatic popups, third-party cookies blocked by default, app-launching schemes (`intent:`, `market:`) blocked, typed `javascript:` treated as a search.
- Library uses the Storage Access Framework with persisted per-file permissions; no broad storage permission is requested.
- Local servers bind to the LAN only, use random ports and per-session tokens, and stop when casting/transfer ends.

---

## 16. Testing strategy

| Layer | Tool | Scope |
|---|---|---|
| Pure logic (JVM) | JUnit 4 | URL classification, HLS/DASH parsing, URL resolution, engine routing, availability policy, address-bar resolution. **15 detection tests + 4 browser tests exist today** |
| Manifest corpus | JUnit + fixture files | Real-world playlists (multi-audio, I-frame, CMAF, live, encrypted) added as regressions |
| Database | androidx.test + in-memory Room | DAO behaviour (3 tests exist); `MigrationTestHelper` for each schema bump |
| ViewModels | JUnit + fake DAOs + `kotlinx-coroutines-test` | Tab lifecycle, history merging, detection de-duplication (M2) |
| Browser integration | Instrumented + OkHttp MockWebServer serving test pages | Pages with MP4, HLS, DASH, DRM manifests → expected detections |
| UI | Compose UI tests; Roborazzi screenshot tests | Navigation, settings persistence, empty states |
| Device matrix (manual, per milestone) | Android 8, 10, 12, 14, 15; Pixel + Samsung | Plus TVs for M5–M6: Chromecast with Google TV, Samsung (Tizen), LG (webOS), Fire TV, a DLNA-only TV |
| CI | GitHub Actions (free) | `test`, `lintDebug`, `assembleDebug`, APK uploaded as an artifact on every push |

---

## 17. Milestone plan

| # | Milestone | Status | Exit criteria |
|---|---|---|---|
| 1 | Foundation | ✅ Done | Multi-module build, theme, navigation, settings, database, browser with tabs/history/bookmarks, share intent, library import, detection engine core with tests |
| 2 | Media detection | Next | OkHttp `ManifestFetcher` with page cookies; quality lists for HLS/DASH; AUTH_REQUIRED handling; clipboard chip; share flow shows a detection card immediately; MutationObserver probe for late players; first site adapters for open platforms (e.g. Vimeo, Internet Archive, PeerTube) |
| 3 | Download engine | | WorkManager + foreground service; progressive with Range resume; HLS/DASH segment download → Media3 remux to MP4; queue, pause/resume/retry, Wi-Fi-only, concurrency limit; SAF destination; duplicate detection; auto-add to library |
| 4 | Media player | | Media3 player: local + online, subtitles, audio tracks, speed, gestures, PiP, background audio via `MediaSessionService`, resume position |
| 5 | Casting | | Local server; Google Cast (`full` flavor); DLNA discovery + control; web receiver; remote controls in a notification |
| 6 | Screen mirroring | | Companion receiver app (Android TV/Fire TV); browser receiver; adaptive bitrate; connection quality |
| 7 | Ecosystem | | LAN transfer with pairing; browser upload page for computers; Firefox add-on; more adapters; playlists UI |
| 8 | Release | | R8 enabled and verified; security review; performance; accessibility pass; F-Droid metadata; signed releases on GitHub |

### Milestone 2 task list

1. `media:net`: OkHttp client, `OkHttpManifestFetcher` (cookies from `CookieManager`, referer = page URL, 5 MB cap, 10 s timeout).
2. Wire the fetcher into `AppContainer`; handle 401/403 → `Availability.AUTH_REQUIRED`.
3. Media sheet: quality picker from `variants`, size estimate (bitrate × duration).
4. Share flow: analyse the shared URL directly; if it is a page, open it in a tab and show results as they arrive.
5. In-page probe upgrade: `MutationObserver` + `play` event listener via `WebViewCompat.addDocumentStartJavaScript`.
6. Clipboard chip on the start page when a link was copied.
7. Adapter framework docs + first adapters; manifest fixture corpus; MockWebServer browser tests.

---

## 18. Architecture decision records

- **ADR-001 Native Kotlin + Compose.** Best access to WebView, MediaProjection, MediaCodec and Media3. Shared logic is kept platform-free for a later Kotlin Multiplatform step instead of choosing Flutter/React Native now.
- **ADR-002 Pure-Kotlin domain and detection.** No Android types in `core:model` and `media:detection`; enables JVM tests and future iOS reuse.
- **ADR-003 WebView, not GeckoView.** WebView ships with every device (no 50 MB engine), gets security updates through the Play Store and offers request observation. GeckoView remains an option if request visibility proves insufficient.
- **ADR-004 Manual DI.** One `AppContainer` with lazy singletons. Fewer build steps and dependencies than Hilt; revisit if the graph grows complex.
- **ADR-005 Activity-scoped browser.** Browser state and WebViews outlive navigation between sections; the activity handles configuration changes itself so pages are not reloaded on rotation.
- **ADR-006 No FFmpeg.** Media3 Transformer/Muxer handles remuxing; avoids APK size and copyleft obligations.
- **ADR-007 Two product flavors from M5.** `full` (with Google Cast) and `foss` (DLNA + web receiver only) so a 100% open-source build can be published on F-Droid.
- **ADR-008 Never circumvent DRM.** Identification only; clearly explained in UI.

---

## 19. Known limitations (current)

- Downloading is not available yet (M3); the media sheet offers Copy link and Open in another app.
- HLS/DASH quality lists need the M2 fetcher; until then streams show as a single entry.
- Players that hide media behind `blob:` URLs and fetch manifests through unusual endpoints (no `.m3u8`/`.mpd` extension and no MIME type visible to request observation) are not detected yet; site adapters address these case by case.
- The project was written without access to the Android SDK in the authoring environment. The pure-Kotlin modules were compiled and their tests run; the Android modules are verified by the CI workflow on first push or by opening the project in Android Studio.

## 20. Distribution note

Free distribution channels: GitHub Releases and F-Droid (both zero cost). Google Play needs a one-time developer fee and its policies prohibit apps that download from YouTube or that misuse Accessibility, which this design already avoids; downloader apps still receive extra review there.
