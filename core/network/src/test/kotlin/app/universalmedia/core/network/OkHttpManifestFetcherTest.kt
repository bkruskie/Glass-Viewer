package app.universalmedia.core.network

import app.universalmedia.media.detection.FetchResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpManifestFetcherTest {
    private val server = MockWebServer()
    private lateinit var fetcher: OkHttpManifestFetcher

    @Before
    fun setUp() {
        server.start()
        fetcher = OkHttpManifestFetcher(
            client = OkHttpManifestFetcher.defaultClient(),
            userAgent = { "TestAgent/1.0" },
            cookiesFor = { "session=abc" },
            maxBytes = 64,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sendsBrowserHeadersAndFollowsRedirects() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/real/master.m3u8"))
        server.enqueue(MockResponse().setBody("#EXTM3U").setHeader("Content-Type", "application/vnd.apple.mpegurl"))

        val result = fetcher.fetch(server.url("/master.m3u8").toString(), mapOf("Referer" to "https://page.example/"))
        result as FetchResult.Success
        assertEquals("#EXTM3U", result.body)
        assertTrue(result.finalUrl.endsWith("/real/master.m3u8"))

        val first = server.takeRequest()
        assertEquals("TestAgent/1.0", first.getHeader("User-Agent"))
        assertEquals("session=abc", first.getHeader("Cookie"))
        assertEquals("https://page.example/", first.getHeader("Referer"))
    }

    @Test
    fun reportsHttpErrorsAndOversizedBodies() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(FetchResult.HttpError(403), fetcher.fetch(server.url("/a").toString(), emptyMap()))

        server.enqueue(MockResponse().setBody("x".repeat(200)))
        assertTrue(fetcher.fetch(server.url("/b").toString(), emptyMap()) is FetchResult.Failure)
    }

    @Test
    fun probeReadsContentLength() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Length", "4096").setHeader("Content-Type", "video/mp4"))
        val probe = fetcher.probe(server.url("/v.mp4").toString(), emptyMap())!!
        assertEquals(200, probe.code)
        assertEquals(4096L, probe.contentLength)
        assertEquals("HEAD", server.takeRequest().method)
    }
}
