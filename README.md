# Universal Media

A free, private, all-in-one media app for Android: browse, detect, download, play, cast, mirror and transfer video and audio. No accounts, no ads, no cloud.

**Current version:** `0.4.0`: downloads and the built-in player. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full design and roadmap.

## What works in this build

- **Browser** with tabs, address bar (search or URL), history, bookmarks, fullscreen video, Safe Browsing and third-party cookie blocking.
- **Automatic media detection** in the browser: video, audio, HLS and DASH sources appear behind a "N media found" button, including players that load late. Streams show their qualities, duration and estimated size; DRM and sign-in requirements are identified and explained.
- **Site support** for Internet Archive and PeerTube (any instance) pages.
- **Share to Universal Media** from any app, open web links with it, or tap "Open copied link". Shared links are analysed right away.
- **Library**: add videos and music from your phone, search, filter, favourites. Files stay where they are.
- **Settings**: detection, search engine, JavaScript, cookies, download preferences, pure-black theme, clear browsing data.
- **Downloads**: MP4, WebM, MKV, audio, HLS (incl. standard encryption and separate audio) and DASH; pause, resume, retry, Wi-Fi only; saved to Movies/Music › Universal Media or a folder you choose, and added to the library.
- **Built-in player**: plays everything above, with speed, subtitles, audio tracks, fullscreen, picture-in-picture and resume.
- **Connect**: play on Chromecast, DLNA smart TVs, Roku (experimental) or any TV browser; see every TV on your Wi-Fi with the step that works for it; mirror the screen to the receiver app, a browser or Smart View.
- **Wi-Fi transfer**: any computer or phone on the same Wi-Fi opens an address (or scans a QR code) in its browser to download your library or send files to the phone. No app, account or cloud on the other side.

## Build

Requirements: JDK 17 and the Android SDK (API 35). Android Studio Ladybug or newer opens the project directly.

```bash
./gradlew test                # JVM unit tests
./gradlew :app:assembleDebug  # APK in app/build/outputs/apk/debug/
./gradlew connectedCheck      # instrumented tests (device or emulator)
```

### No computer? Build on GitHub for free

1. Create a GitHub repository and upload this folder.
2. GitHub Actions runs `.github/workflows/android.yml` on every push: tests, lint and a debug APK.
3. Open the run under **Actions → Build & test** and download `universal-media-debug-apk` on your phone to install it.

## Project layout

```
app/                 entry point, navigation, DI
core/model           domain models (pure Kotlin)
core/ui              theme and shared components
core/database        Room database
core/datastore       settings
media/detection      detection engine (pure Kotlin, unit tested)
feature/*            browser, downloads, player, library, connect, settings
```

## License

Choose a license before publishing (Apache 2.0 or GPL-3.0 are common for apps like this). All current dependencies are Apache 2.0.
