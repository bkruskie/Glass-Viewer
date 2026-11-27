package app.universalmedia.media.detection

import app.universalmedia.core.model.Availability
import app.universalmedia.core.model.DetectedMedia
import app.universalmedia.core.model.DetectionOrigin
import app.universalmedia.core.model.DownloadSupport
import app.universalmedia.core.model.MediaFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSegmentListTest {

    @Test
    fun parsesSegmentsKeysAndSequenceIvs() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-KEY:METHOD=AES-128,URI="../keys/k1"
            #EXTINF:6.0,
            s7.ts
            #EXT-X-KEY:METHOD=AES-128,URI="k2",IV=0x000102030405060708090A0B0C0D0E0F
            #EXTINF:4.0,
            s8.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:2.0,
            s9.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val list = HlsSegmentList.parse(text, "https://cdn.example/v/hd/index.m3u8")!!
        assertNull(list.unsupportedReason)
        assertEquals(3, list.segments.size)
        assertEquals(12.0, list.totalDurationSeconds, 0.001)
        val first = list.segments[0]
        assertEquals("https://cdn.example/v/hd/s7.ts", first.url)
        assertEquals("https://cdn.example/v/keys/k1", first.key!!.uri)
        // IV from the sequence number 7.
        assertArrayEquals(ByteArray(15) + byteArrayOf(7), first.effectiveIv())
        assertArrayEquals(ByteArray(16) { it.toByte() }, list.segments[1].effectiveIv())
        assertNull(list.segments[2].key)
        assertTrue(!list.isFragmentedMp4)
    }

    @Test
    fun parsesFragmentedMp4WithByteRanges() {
        val text = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-MAP:URI="main.mp4",BYTERANGE="720@0"
            #EXTINF:5.0,
            #EXT-X-BYTERANGE:1000@720
            main.mp4
            #EXTINF:5.0,
            #EXT-X-BYTERANGE:2000
            main.mp4
        """.trimIndent()
        val list = HlsSegmentList.parse(text, "https://e.com/a/index.m3u8")!!
        assertTrue(list.isFragmentedMp4)
        assertEquals(ByteRange(0, 720), list.initByteRange)
        assertEquals(ByteRange(720, 1000), list.segments[0].byteRange)
        assertEquals(ByteRange(1720, 2000), list.segments[1].byteRange)
        assertEquals("bytes=1720-3719", list.segments[1].byteRange!!.rangeHeader)
        assertNull(list.unsupportedReason)
    }

    @Test
    fun flagsLiveDrmAndSampleAes() {
        val live = HlsSegmentList.parse("#EXTM3U\n#EXTINF:6,\na.ts\n", "https://e.com/i.m3u8")!!
        assertNotNull(live.unsupportedReason)
        val drm = HlsSegmentList.parse(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST",
            "https://e.com/i.m3u8",
        )!!
        assertTrue(drm.unsupportedReason!!.contains("DRM"))
        val sample = HlsSegmentList.parse("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:6,\na.ts\n#EXT-X-ENDLIST", "https://e.com/i.m3u8")!!
        assertTrue(sample.unsupportedReason!!.contains("SAMPLE-AES"))
        assertNull(HlsSegmentList.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8", "https://e.com/m.m3u8"))
    }

    @Test
    fun downloadSupportRules() {
        fun media(format: MediaFormat, availability: Availability = Availability.SUPPORTED) = DetectedMedia(
            id = "x", mediaUrl = "https://e.com/x", format = format, origin = DetectionOrigin.MANUAL,
            detectedAtMillis = 0, availability = availability,
        )
        assertNull(DownloadSupport.reasonNotSupported(media(MediaFormat.MP4), null))
        assertNull(DownloadSupport.reasonNotSupported(media(MediaFormat.HLS), null))
        assertNull(DownloadSupport.reasonNotSupported(media(MediaFormat.DASH), null))
        assertNotNull(DownloadSupport.reasonNotSupported(media(MediaFormat.MP4, Availability.PROTECTED), null))
    }
}
