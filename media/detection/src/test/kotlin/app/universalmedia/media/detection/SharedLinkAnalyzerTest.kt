package app.universalmedia.media.detection

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLinkAnalyzerTest {
    private fun analyzer(fetcher: FakeFetcher) =
        SharedLinkAnalyzer(MediaDetectionEngine(fallback = GenericMediaAdapter(fetcher)), fetcher)

    @Test
    fun findsTheVideoAPageDeclares() = runBlocking {
        val fetcher = FakeFetcher(
            bodies = mapOf(
                "https://example.com/watch/1" to
                    "<title>t</title><meta property=\"og:title\" content=\"Beach day\"><meta property=\"og:video\" content=\"https://cdn.example.com/1.mp4\">",
            ),
            probes = mapOf("https://cdn.example.com/1.mp4" to ProbeResult(200, 5_000_000, "video/mp4")),
        )
        val outcome = analyzer(fetcher).analyze("https://example.com/watch/1")
        assertTrue(outcome is SharedLinkAnalyzer.Outcome.Found)
        outcome as SharedLinkAnalyzer.Outcome.Found
        assertEquals("Beach day", outcome.pageTitle)
        assertEquals("https://cdn.example.com/1.mp4", outcome.media.single().mediaUrl)
    }

    @Test
    fun scriptOnlyPagesNeedTheBrowser() = runBlocking {
        val fetcher = FakeFetcher(bodies = mapOf("https://example.com/app" to "<div id=root></div><script src=app.js></script>"))
        assertEquals(SharedLinkAnalyzer.Outcome.NeedsBrowser, analyzer(fetcher).analyze("https://example.com/app"))
        assertEquals(SharedLinkAnalyzer.Outcome.NeedsBrowser, analyzer(FakeFetcher()).analyze("https://example.com/missing"))
    }
}
