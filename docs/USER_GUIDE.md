# Universal Media: user guide

Free, private, no account. Everything stays on your phone and your own Wi-Fi.

## Install and update

- **Phone:** open https://github.com/bkruskie/Glass-Viewer/releases/latest/download/UniversalMedia.apk on the phone and install it. New versions install over the old one; your library and settings stay.
- **If install is blocked:** Samsung *Auto Blocker* (Settings › Security and privacy) or Google *Play Protect* may block apps from outside the Play Store. Allow this one install, or turn the blocker off while installing.
- **Android TV / Google TV / Fire TV (optional):** install `UniversalMediaReceiver.apk` from the same release page on the TV to mirror your phone's screen to it.

## Browser: find and save video

1. Open **Browser** and go to any site.
2. When a page has video or audio, a **media** button appears with the number found.
3. Tap it to see each source grouped by quality (resolution, bitrate, estimated size, length).
4. Choose **Play** to watch now or **Save** to download.

The app looks at the requests a page makes, the stream links in its code, and what the page declares about its own video (the tags sites use for link previews and search engines), so the media button often lights up before you press play.

**Playing in other apps (optional):** turn on *See what other apps are playing* (Settings, or the card on the browser start page). The start page then lists what other apps are playing; tap one to open its link when the app shares one, or to search the web for it. Android only shares titles, not the video itself; the permission is called *Notification access*, and Universal Media never reads your notifications.

You can also **share** a link to Universal Media from any app, or open copied links.

Videos protected by DRM (most paid streaming services) can't be saved; the app says so instead of saving a broken file.

## Downloads

- Several at once, with pause, resume and retry. Optionally Wi-Fi only (Settings).
- Saved to **Movies › Universal Media** (music to **Music › Universal Media**), or a folder you choose in Settings, and added to the Library.
- Streams (HLS) are saved as `.ts` files, which play in this app, VLC and most TVs.
- When a download finishes, the app offers to play it.
- If something goes wrong, tap the download, then **Technical details**, and share what it says.

## Player

- Swipe up/down on the left for brightness, on the right for volume. Double-tap the sides to skip 10 s.
- **Pinch** to zoom, drag to move around a zoomed picture.
- Toolbar: speed from **0.1× (super slow motion)** to 4×, frame-by-frame step, fit / fill / stretch, loop, and **snapshot** (saves the current frame to Pictures).
- **Subtitles:** the subtitle button adds a .srt, .vtt, .ass or .ttml file; switch subtitles and audio tracks with the CC and settings buttons.
- Turn the phone sideways for fullscreen; picture-in-picture when you leave the app.
- Playlists: Previous / Next and **Up next** appear while a list plays.

## Library

- Everything you download, plus files you add with **Add files**. Search, filter (video, audio, favorites) and **Sort**.
- ⋮ on any item: **Add to playlist**, Rename, **Details** (resolution, codecs, bitrate, where it's saved), Share, Open with…, Remove from library (file stays), **Delete from phone** (asks first).
- **Folders:** ⋮ › *Put in folder…* sorts items into your own folders (chips above the list; tap a selected folder chip to rename or remove it). *Copy / Move to phone folder…* puts the actual file anywhere on the phone or SD card.
- **Playlists** chip: create playlists, reorder, Play all or Shuffle. **Play all** plays whatever list you are looking at.

## Connect: TVs

Your phone and TV must be on the same Wi-Fi.

| Your TV | What to do |
|---|---|
| Chromecast, Google TV, TVs with "Chromecast built-in" | Listed under **Play on a TV**. Tap it and pick a video. |
| Smart TVs with media sharing (DLNA): many Samsung, LG, Sony, Panasonic, Philips | Listed under **Play on a TV**. If it shows under **Other TVs on your Wi-Fi** instead, turn on media sharing in the TV's settings; tap the TV in the app to see where. |
| Roku | Listed under **Play on a TV** (experimental). If refused, on the Roku set Settings › System › Advanced system settings › Control by mobile apps › Network access to *Default* or *Permissive*. |
| Any TV with a web browser | Open the address shown under **Play on a TV** in the TV's browser. |
| Android TV / Fire TV | Install the receiver app for screen mirroring (below). |
| Apple TV / AirPlay-only TVs | Not supported for sending video; use Smart View or HDMI. |

While something plays on the TV, Connect shows a remote: play/pause, seek, ±10 s, volume, stop. The phone stays free for other things.

## Connect: mirror your whole screen

- **Receiver app** on Android TV / Google TV / Fire TV: appears automatically; tap it. With receiver 1.1+ and a phone on Android 10+, **sound plays on the TV** too (the app asks for audio permission once; it captures what apps play, never the microphone). Calls and some streaming apps don't allow their sound to be captured.
- **Any browser:** choose the browser option and open the address shown (it ends in a short code) on the TV or computer.
- **Smart View / Screen cast:** the phone's own mirroring (Samsung, Pixel and others), opened from the app. Works with most Samsung and Miracast TVs.
- **HDMI:** a USB-C to HDMI adapter works on phones that support video output.

## Connect: Wi-Fi transfer

1. Connect › **Send to a computer or phone** › **Start sharing**.
2. On the computer or other phone, open the address shown in a browser (or scan the QR code).
3. Download files from the phone's library, or drop files onto the page to send them to the phone.

Only someone with that address can connect. Sharing stops when you tap Stop or after 20 minutes without use. Keep the panel open during large transfers.

## Privacy

- No accounts, ads, analytics or cloud. Nothing leaves the phone except pages you open and devices on your own Wi-Fi.
- The phone's small servers (casting, transfer, browser mirroring) use random ports and secret addresses, and stop when you stop.
- Settings › Clear browsing data removes cookies, site data, cache and history.

## Problems

- **TV not listed:** check the TV is on and on the same Wi-Fi (not a guest network). Some routers block device discovery ("AP / client isolation"); turn that off on the router.
- **No sound or stuck video:** update to the latest version; then send **Technical details** from the download.
- **The app closed unexpectedly:** next launch shows a report you can copy and send.
