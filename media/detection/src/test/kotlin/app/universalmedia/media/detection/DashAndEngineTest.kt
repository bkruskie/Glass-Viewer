package app.universalmedia.media.detection

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.core.model.DrmSystem
import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.Protection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashAndEngineTest {

    private val clearMpd = """
        <?xml version="1.0"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT1H2M3.5S">
          <Period>
            <AdaptationSet mimeType="video/mp4">
              <Representation id="v1" bandwidth="800000" width="854" height="480" codecs="avc1.4d401f"/>
              <Representation id="v2" bandwidth="5000000" width="1920" height="1080" frameRate="30000/1001"/>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4">
              <Representation id="a1" mimeType="audio/mp4" bandwidth="128000" audioSamplingRate="48000"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private val drmMpd = """
        <MPD type="dynamic">
          <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
          <ContentProtection schemeIdUri="urn:uuid:EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"/>
          <Representation bandwidth="1" height="720"/>
        </MPD>
    """.trimIndent()

    @Test
    fun inspectsClearDash() {
        val s = DashManifestInspector.inspect(clearMpd, "https://e.com/m.mpd")!!
        assertEquals(3_723_500L, s.durationMs)
        assertEquals(false, s.isLive)
        assertEquals(1080, s.variants.first().height)
        assertTrue(s.variants.last().audioOnly)
        assertEquals(29.97, s.variants.first().frameRate!!, 0.01)
        assertEquals(Protection.None, s.protection)
    }

    @Test
    fun detectsDashDrm() {
        val s = DashManifestInspector.inspect(drmMpd, "https://e.com/m.mpd")!!
        assertTrue(s.isLive)
        assertEquals(setOf(DrmSystem.WIDEVINE), (s.protection as Protection.Drm).systems)
    }

    @Test
    fun engineMarksDrmSourcesAsProtected() = runBlocking {
        val engine = MediaDetectionEngine(fallback = GenericMediaAdapter(fetcher = FakeFetcher(mapOf("https://e.com/v/m.mpd" to drmMpd)), clock = { 42L }))
        val result = engine.resolve(ResolveRequest("https://e.com/v/m.mpd", DetectionOrigin.IN_APP_BROWSER, pageUrl = "https://www.e.com/watch"))
        val media = (result as ResolveResult.Found).media.single()
        assertEquals(MediaFormat.DASH, media.format)
        assertEquals(Availability.PROTECTED, media.availability)
        assertEquals("www.e.com", media.sourceHost)
        assertEquals(42L, media.detectedAtMillis)
    }

    @Test
    fun engineRoutesToMatchingAdapterFirst() = runBlocking {
        val custom = object : SiteAdapter {
            override val id = "custom"
            override val displayName = "Custom"
            override fun matches(url: String) = UrlUtils.hostOf(url) == "special.example"
            override suspend fun resolve(request: ResolveRequest) =
                ResolveResult.Unavailable(Availability.AUTH_REQUIRED, "Sign in first")
        }
        val engine = MediaDetectionEngine(listOf(custom))
        assertTrue(engine.resolve(ResolveRequest("https://special.example/v.mp4", DetectionOrigin.MANUAL)) is ResolveResult.Unavailable)
        assertTrue(engine.resolve(ResolveRequest("https://other.example/v.mp4", DetectionOrigin.MANUAL)) is ResolveResult.Found)
        assertEquals(ResolveResult.NotMedia, engine.resolve(ResolveRequest("https://other.example/page", DetectionOrigin.MANUAL)))
    }

    @Test
    fun urlResolution() {
        assertEquals("https://a.com/x/b.m3u8", UrlUtils.resolve("https://a.com/x/y.m3u8?tok=1", "b.m3u8"))
        assertEquals("https://a.com/b.m3u8", UrlUtils.resolve("https://a.com/x/y.m3u8", "/b.m3u8"))
        assertEquals("https://a.com/b.m3u8", UrlUtils.resolve("https://a.com/x/y.m3u8", "../b.m3u8"))
        assertEquals("http://cdn.com/z", UrlUtils.resolve("http://a.com/x/y", "//cdn.com/z"))
        assertEquals("https://a.com/ref", UrlUtils.resolve("https://a.com", "ref"))
        assertEquals("example.com", UrlUtils.hostOf("https://user:pw@Example.com:8443/p"))
    }
}
