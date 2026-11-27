package app.universalmedia.feature.browser

import app.universalmedia.core.model.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressBarResolverTest {
    private val ddg = SearchEngine.DUCKDUCKGO

    @Test
    fun keepsExplicitWebUrls() {
        assertEquals("https://example.com/a?b=1", AddressBarResolver.resolve("  https://example.com/a?b=1 ", ddg))
        assertEquals("http://example.com", AddressBarResolver.resolve("http://example.com", ddg))
    }

    @Test
    fun addsHttpsToBareHosts() {
        assertEquals("https://example.com", AddressBarResolver.resolve("example.com", ddg))
        assertEquals("https://sub.example.co.uk/path", AddressBarResolver.resolve("sub.example.co.uk/path", ddg))
        assertEquals("https://192.168.1.20:8080", AddressBarResolver.resolve("192.168.1.20:8080", ddg))
        assertEquals("https://localhost:3000/x", AddressBarResolver.resolve("localhost:3000/x", ddg))
    }

    @Test
    fun searchesEverythingElse() {
        assertEquals("https://duckduckgo.com/?q=cat+videos", AddressBarResolver.resolve("cat videos", ddg))
        assertEquals("https://duckduckgo.com/?q=kotlin", AddressBarResolver.resolve("kotlin", ddg))
        assertEquals(
            "https://duckduckgo.com/?q=javascript%3Aalert%281%29",
            AddressBarResolver.resolve("javascript:alert(1)", ddg),
        )
    }

    @Test
    fun extractsSharedUrls() {
        assertEquals("https://vimeo.com/123", SharedTextParser.firstUrl("Watch this: https://vimeo.com/123."))
        assertNull(SharedTextParser.firstUrl("no link here"))
    }
}
