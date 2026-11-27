package app.universalmedia.media.detection

import app.universalmedia.core.model.DrmSystem
import app.universalmedia.core.model.Protection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1280000,AVERAGE-BANDWIDTH=1000000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2",AUDIO="aud"
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080,FRAME-RATE=29.970,CODECS="avc1.640028,mp4a.40.2"
        https://other.cdn.com/1080p/index.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=86000,URI="iframe.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.5"
        ../audio-only/index.m3u8
    """.trimIndent()

    @Test
    fun parsesMasterVariantsSortedByQuality() {
        val p = HlsPlaylistParser.parse(master, "https://cdn.example.com/show/ep1/master.m3u8?t=1")
        p as HlsPlaylist.Master
        assertEquals(3, p.variants.size)
        assertEquals(1080, p.variants[0].height)
        assertEquals("https://other.cdn.com/1080p/index.m3u8", p.variants[0].url)
        assertEquals(360, p.variants[1].height)
        assertEquals(1_000_000L, p.variants[1].bitrate) // AVERAGE-BANDWIDTH preferred
        assertEquals("https://cdn.example.com/show/ep1/360p/index.m3u8", p.variants[1].url)
        assertTrue(p.variants[2].audioOnly)
        assertEquals("https://cdn.example.com/show/audio-only/index.m3u8", p.variants[2].url)
        assertEquals("https://cdn.example.com/show/ep1/audio/en.m3u8", p.renditions.single().uri)
        assertEquals(Protection.None, p.protection)
    }

    @Test
    fun parsesMediaPlaylistWithAesEncryption() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x1234
            #EXTINF:6.0,
            s1.ts
            #EXTINF:4.5,
            s2.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, "https://e.com/v/index.m3u8") as HlsPlaylist.Media
        assertEquals(2, p.segmentCount)
        assertEquals(10.5, p.totalDurationSeconds, 0.001)
        assertFalse(p.isLive)
        val enc = p.protection as Protection.StreamEncryption
        assertEquals("AES-128", enc.method)
    }

    @Test
    fun detectsFairPlayAndWidevineAsDrm() {
        val text = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://abc",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
            #EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AAAA",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
            #EXTINF:6.0,
            s1.mp4
        """.trimIndent()
        val p = HlsPlaylistParser.parse(text, "https://e.com/index.m3u8") as HlsPlaylist.Media
        assertTrue(p.isLive)
        assertEquals(setOf(DrmSystem.FAIRPLAY, DrmSystem.WIDEVINE), (p.protection as Protection.Drm).systems)
    }

    @Test
    fun rejectsNonPlaylists() {
        assertNull(HlsPlaylistParser.parse("<html></html>", "https://e.com/x.m3u8"))
    }

    @Test
    fun attributeParserHandlesQuotedCommas() {
        val a = HlsPlaylistParser.parseAttributes("BANDWIDTH=1,CODECS=\"avc1,mp4a\",NAME=\"A, B\",X=y")
        assertEquals("avc1,mp4a", a["CODECS"])
        assertEquals("A, B", a["NAME"])
        assertEquals("y", a["X"])
    }
}
