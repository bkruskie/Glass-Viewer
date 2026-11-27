package app.universalmedia.media.detection

import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.MediaVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaGroupingTest {

    private fun media(url: String, format: MediaFormat = MediaFormat.MP4, variants: List<MediaVariant> = emptyList(), page: String = "https://site.example/v/1") =
        DetectedMedia(
            id = url, mediaUrl = url, format = format, origin = DetectionOrigin.IN_APP_BROWSER,
            detectedAtMillis = 0, pageUrl = page, variants = variants, title = "Clip",
        )

    @Test
    fun readsResolutionFromLinks() {
        assertEquals(720, MediaGrouping.heightFromUrl("https://cdn.example/hls/720p/index.m3u8?t=1080"))
        assertEquals(1080, MediaGrouping.heightFromUrl("https://cdn.example/videos/clip_1080.mp4"))
        assertEquals(480, MediaGrouping.heightFromUrl("https://cdn.example/a/480P.mp4"))
        assertNull(MediaGrouping.heightFromUrl("https://cdn.example/videos/id10802/clip.mp4"))
        assertNull(MediaGrouping.heightFromUrl("https://cdn.example/x/video.mp4"))
    }

    @Test
    fun foldsQualityPlaylistsIntoTheirMaster() {
        val master = media(
            "https://cdn.example/s/master.m3u8", MediaFormat.HLS,
            listOf(
                MediaVariant("https://cdn.example/s/1080/index.m3u8", MediaFormat.HLS, height = 1080),
                MediaVariant("https://cdn.example/s/720/index.m3u8", MediaFormat.HLS, height = 720),
            ),
        )
        val child = media("https://cdn.example/s/720/index.m3u8", MediaFormat.HLS)
        val result = MediaGrouping.organize(listOf(child, master))
        assertEquals(1, result.size)
        assertEquals(listOf(1080, 720), result.single().variants.map { it.height })
    }

    @Test
    fun mergesSameVideoAtDifferentResolutionsAndSorts() {
        val items = listOf(
            media("https://cdn.example/v/clip_480p.mp4?sig=a"),
            media("https://cdn.example/v/song.mp3", MediaFormat.MP3),
            media("https://cdn.example/v/clip_1080p.mp4?sig=b"),
            media("https://cdn.example/v/clip_720p.mp4?sig=c"),
            media("https://cdn.example/other/trailer.webm", MediaFormat.WEBM),
        )
        val result = MediaGrouping.organize(items)
        assertEquals(3, result.size)
        val clip = result.first()
        assertEquals(listOf(1080, 720, 480), clip.variants.map { it.height })
        assertEquals("https://cdn.example/v/clip_1080p.mp4?sig=b", clip.mediaUrl)
        assertEquals(MediaFormat.WEBM, result[1].format)
        assertEquals(MediaFormat.MP3, result.last().format) // audio last
        assertEquals(3, MediaGrouping.urlsOf(clip).size) // 3 qualities; mediaUrl is the best one
    }
}

class PageSourceScannerTest {
    @Test
    fun findsEscapedManifestLinksInPlayerConfig() {
        val html = """
            <script>var flashvars = {"mediaDefinitions":[
              {"format":"hls","videoUrl":"https:\/\/cdn.example\/videos\/1080P_4000K_1.mp4\/master.m3u8?validfrom=1&hash=x","quality":"1080"},
              {"format":"hls","videoUrl":"https:\/\/cdn.example\/videos\/720P_4000K_1.mp4\/master.m3u8?validfrom=1&hash=y","quality":"720"},
              {"format":"mp4","videoUrl":"https:\/\/site.example\/video\/get_media?s=abc"}]};
            html5player.setVideoHLS('https://cdn2.example/hls/abc/hls.m3u8');
            </script>
            <img src="https://img.example/thumb.jpg"><a href="https://cdn.example/preview.mp4">x</a>
            <link href="https://cdn.example/dash/manifest.mpd">
        """.trimIndent()
        assertEquals(
            listOf(
                "https://cdn.example/videos/1080P_4000K_1.mp4/master.m3u8?validfrom=1&hash=x",
                "https://cdn.example/videos/720P_4000K_1.mp4/master.m3u8?validfrom=1&hash=y",
                "https://cdn2.example/hls/abc/hls.m3u8",
                "https://cdn.example/dash/manifest.mpd",
            ),
            PageSourceScanner.findManifestUrls(html),
        )
    }
}

class ManifestLinkTest {
    @Test
    fun groupedStreamQualitiesKeepTheirMaster() = kotlinx.coroutines.runBlocking {
        val master720 = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",DEFAULT=YES,URI=\"audio.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO=\"a\"\nvideo.m3u8\n"
        val fetcher = FakeFetcher(bodies = mapOf("https://cdn.example/720P_2000K/master.m3u8" to master720))
        val found = GenericMediaAdapter(fetcher).resolve(
            ResolveRequest("https://cdn.example/720P_2000K/master.m3u8", app.universalmedia.core.model.DetectionOrigin.IN_APP_BROWSER),
        ) as ResolveResult.Found
        val variant = found.media.single().variants.single()
        assertEquals("https://cdn.example/720P_2000K/video.m3u8", variant.url)
        assertEquals("https://cdn.example/720P_2000K/master.m3u8", variant.manifestUrl)
        // After grouping, the quality still points at its master.
        val organized = MediaGrouping.organize(found.media)
        assertEquals("https://cdn.example/720P_2000K/master.m3u8", organized.single().variants.single().manifestUrl)
    }
}
