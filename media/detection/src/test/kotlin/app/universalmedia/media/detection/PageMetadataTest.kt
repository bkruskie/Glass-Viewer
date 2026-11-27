package app.universalmedia.media.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMetadataTest {
    private val page = "https://videos.example.com/watch/42"

    @Test
    fun readsOpenGraphTwitterJsonLdAndTags() {
        val html = """
            <html><head><title>Fallback title</title>
            <meta property="og:title" content="Sunset &amp; Waves">
            <meta property="og:video:secure_url" content="https://cdn.example.com/v/42/720.mp4">
            <meta property="og:image" content="https://cdn.example.com/v/42/poster.jpg">
            <meta name="twitter:player:stream" content="https://cdn.example.com/v/42/master.m3u8?sig=a&amp;exp=9">
            <meta property="og:video" content="https://videos.example.com/embed/42">
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"VideoObject","name":"x",
              "contentUrl":"https:\/\/cdn.example.com\/v\/42\/1080.mp4","duration":"PT1M30S"}]}</script>
            </head><body>
            <video src="/media/42/480.webm" poster="/p.jpg"></video>
            <script>jwplayer("p").setup({file: "https://cdn.example.com/v/42/hls/index.m3u8", image: "x.jpg"});</script>
            </body></html>
        """.trimIndent()
        val d = PageMetadata.read(html, page)
        assertEquals("Sunset & Waves", d.title)
        assertEquals(90_000L, d.durationMs)
        assertEquals(
            listOf(
                "https://cdn.example.com/v/42/720.mp4",
                "https://cdn.example.com/v/42/master.m3u8?sig=a&exp=9",
                "https://videos.example.com/media/42/480.webm",
                "https://cdn.example.com/v/42/1080.mp4",
                "https://cdn.example.com/v/42/hls/index.m3u8",
            ),
            d.urls,
        )
    }

    @Test
    fun ignoresPagesImagesAndFallsBackToTitleTag() {
        val d = PageMetadata.read("<title> Just a page </title><meta property=\"og:video\" content=\"https://x.com/embed/1\"><img src=\"a.mp4.jpg\">", page)
        assertTrue(d.urls.isEmpty())
        assertEquals("Just a page", d.title)
        assertFalse(PageMetadata.looksLikeMedia("https://x.com/watch?v=movie.mp4"))
        assertTrue(PageMetadata.looksLikeMedia("https://x.com/a/movie.MP4?token=1"))
    }

    @Test
    fun parsesIsoDurations() {
        assertEquals(3_723_000L, PageMetadata.isoDurationMs("PT1H2M3S"))
        assertEquals(95_500L, PageMetadata.isoDurationMs("PT95.5S"))
        assertEquals(null, PageMetadata.isoDurationMs("P"))
    }
}
