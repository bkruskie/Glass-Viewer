package app.universalmedia.media.detection

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.media.detection.adapters.InternetArchiveAdapter
import app.universalmedia.media.detection.adapters.PeerTubeAdapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Milestone2Test {

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080
        hd/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        sd/index.m3u8
    """.trimIndent()

    private val hdMedia = """
        #EXTM3U
        #EXT-X-TARGETDURATION:10
        #EXTINF:10.0,
        a.ts
        #EXTINF:10.0,
        b.ts
        #EXT-X-ENDLIST
    """.trimIndent()

    private fun req(url: String, page: String? = "https://site.example/watch") =
        ResolveRequest(url, DetectionOrigin.IN_APP_BROWSER, pageUrl = page)

    private fun found(r: ResolveResult) = (r as ResolveResult.Found).media.single()

    @Test
    fun hlsMasterGetsQualitiesDurationAndSizesAndFollowsRedirects() = runBlocking {
        val fetcher = FakeFetcher(
            bodies = mapOf(
                "https://cdn.example/v2/master.m3u8" to master,
                "https://cdn.example/v2/hd/index.m3u8" to hdMedia,
            ),
            redirects = mapOf("https://cdn.example/v/master.m3u8" to "https://cdn.example/v2/master.m3u8"),
        )
        val media = found(GenericMediaAdapter(fetcher).resolve(req("https://cdn.example/v/master.m3u8")))
        assertEquals(20_000L, media.durationMs)
        assertEquals(listOf(1080, 360), media.variants.map { it.height })
        // 8 Mbps × 20 s / 8 = 20 MB
        assertEquals(20_000_000L, media.variants.first().estimatedBytes)
        assertEquals(Availability.SUPPORTED, media.availability)
        // Referer comes from the page.
        assertEquals("https://site.example/watch", fetcher.requested.first().second["Referer"])
    }

    @Test
    fun forbiddenManifestMeansSignInRequired() = runBlocking {
        val fetcher = FakeFetcher(errors = mapOf("https://cdn.example/p.m3u8" to 403))
        val media = found(GenericMediaAdapter(fetcher).resolve(req("https://cdn.example/p.m3u8")))
        assertEquals(Availability.AUTH_REQUIRED, media.availability)
    }

    @Test
    fun directFileProbeGivesSize() = runBlocking {
        val fetcher = FakeFetcher(probes = mapOf("https://cdn.example/a.mp4" to ProbeResult(200, 1234L, "video/mp4")))
        val media = found(GenericMediaAdapter(fetcher).resolve(req("https://cdn.example/a.mp4")))
        assertEquals(1234L, media.variants.single().estimatedBytes)
    }

    @Test
    fun segmentExtensionBeatsMime() {
        assertEquals(MediaUrlClassifier.Result.Segment, MediaUrlClassifier.classify("https://e.com/c/seg-9.m4s", "video/mp4"))
    }

    @Test
    fun miniJsonParsesNestedValues() {
        val v = MiniJson.parse("""{"a":[1,2.5,"x\"y",true,null],"b":{"c":"é"},"n":-3e2}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, "x\"y", true, null), v["a"])
        assertEquals("é", (v["b"] as Map<*, *>)["c"])
        assertEquals(-300.0, v["n"])
        assertNull(MiniJson.parseOrNull("{bad"))
    }

    // ---- Internet Archive ------------------------------------------------------

    private val iaJson = """
        {"metadata":{"identifier":"night_of_the_living_dead","title":["Night of the Living Dead"]},
         "files":[
           {"name":"night_of_the_living_dead_512kb.mp4","format":"h.264","size":"171000000","length":"5737.21","height":"240","width":"320"},
           {"name":"night_of_the_living_dead.mp4","size":"900000000","length":"01:35:37","height":"480","width":"640"},
           {"name":"night_of_the_living_dead.thumbs/x_thumb.jpg"},
           {"name":"night_of_the_living_dead_meta.xml"},
           {"name":"audio track.mp3","size":"5000"}
         ]}
    """.trimIndent()

    @Test
    fun internetArchiveListsFiles() = runBlocking {
        val fetcher = FakeFetcher(bodies = mapOf("https://archive.org/metadata/night_of_the_living_dead" to iaJson))
        val adapter = InternetArchiveAdapter(fetcher)
        val engine = MediaDetectionEngine(listOf(adapter), GenericMediaAdapter(fetcher))
        val page = "https://archive.org/details/night_of_the_living_dead?x=1"
        assertTrue(engine.hasPageAdapter(page))
        val media = found(engine.resolvePage(ResolveRequest(page, DetectionOrigin.SHARE_INTENT)))
        assertEquals("Night of the Living Dead", media.title)
        assertEquals(3, media.variants.size)
        assertEquals(480, media.variants.first().height)
        assertEquals("https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead.mp4", media.mediaUrl)
        assertTrue(media.variants.last().audioOnly)
        assertEquals("https://archive.org/download/night_of_the_living_dead/audio%20track.mp3", media.variants.last().url)
        assertEquals(5_737_210L, media.durationMs)
    }

    @Test
    fun internetArchiveRestrictedItem() = runBlocking {
        val json = """{"metadata":{"title":"Book","access-restricted-item":"true"},"files":[]}"""
        val fetcher = FakeFetcher(bodies = mapOf("https://archive.org/metadata/somebook" to json))
        val r = InternetArchiveAdapter(fetcher).resolve(ResolveRequest("https://archive.org/details/somebook", DetectionOrigin.MANUAL))
        assertEquals(Availability.AUTH_REQUIRED, (r as ResolveResult.Unavailable).availability)
    }

    // ---- PeerTube --------------------------------------------------------------

    private val ptJson = """
        {"name":"Open source talk","duration":600,"isLive":false,
         "files":[],
         "streamingPlaylists":[{"playlistUrl":"https://tube.example/static/hls/abc/master.m3u8",
            "files":[
              {"resolution":{"id":720,"label":"720p"},"size":50000000,"fps":30,"fileUrl":"https://tube.example/static/hls/abc/720.mp4","fileDownloadUrl":"https://tube.example/download/abc-720.mp4"},
              {"resolution":{"id":1080,"label":"1080p"},"size":90000000,"fps":30,"fileUrl":"https://tube.example/static/hls/abc/1080.mp4","fileDownloadUrl":"https://tube.example/download/abc-1080.mp4"},
              {"resolution":{"id":0,"label":"Audio"},"size":5000000,"fileUrl":"https://tube.example/static/hls/abc/0.mp4"}
            ]}]}
    """.trimIndent()

    @Test
    fun peerTubeResolvesAnyInstance() = runBlocking {
        val fetcher = FakeFetcher(bodies = mapOf("https://tube.example/api/v1/videos/kzXkX4cv8qqQ5pUGs2hHaK" to ptJson))
        val adapter = PeerTubeAdapter(fetcher)
        val media = found(adapter.resolve(ResolveRequest("https://tube.example/w/kzXkX4cv8qqQ5pUGs2hHaK", DetectionOrigin.IN_APP_BROWSER)))
        assertEquals("Open source talk", media.title)
        assertEquals(600_000L, media.durationMs)
        assertEquals(listOf(1080, 720, null), media.variants.map { it.height })
        assertEquals("https://tube.example/download/abc-1080.mp4", media.mediaUrl)
        assertEquals(MediaFormat.MP4, media.format)
        assertTrue(media.variants.last().audioOnly)
    }

    @Test
    fun peerTubePathShapeOnOtherSitesIsNotMedia() = runBlocking {
        val adapter = PeerTubeAdapter(FakeFetcher())
        assertTrue(adapter.matches("https://wiki.example/w/SomeArticle"))
        assertEquals(ResolveResult.NotMedia, adapter.resolve(ResolveRequest("https://wiki.example/w/SomeArticle", DetectionOrigin.MANUAL)))
        assertTrue(!adapter.matches("https://en.wikipedia.org/w/index.php?title=X"))
    }
}
