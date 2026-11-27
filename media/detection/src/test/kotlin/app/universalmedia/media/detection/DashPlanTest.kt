package app.universalmedia.media.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashPlanTest {

    private val templateMpd = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- comment -->
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT10S">
          <BaseURL>https://cdn.example/content/</BaseURL>
          <Period>
            <AdaptationSet contentType="video" mimeType="video/mp4">
              <SegmentTemplate media="v/${'$'}RepresentationID${'$'}/seg-${'$'}Number%04d${'$'}.m4s"
                               initialization="v/${'$'}RepresentationID${'$'}/init.mp4"
                               startNumber="1" timescale="1000" duration="4000"/>
              <Representation id="720" bandwidth="2000000" height="720" codecs="avc1.64001f"/>
              <Representation id="1080" bandwidth="5000000" height="1080" codecs="avc1.640028"/>
            </AdaptationSet>
            <AdaptationSet mimeType="audio/mp4" lang="en">
              <Representation id="a1" bandwidth="128000">
                <SegmentTemplate media="a/t-${'$'}Time${'$'}.m4s" initialization="a/init.mp4" timescale="48000">
                  <SegmentTimeline>
                    <S t="0" d="96000" r="2"/>
                    <S d="48000"/>
                  </SegmentTimeline>
                </SegmentTemplate>
              </Representation>
            </AdaptationSet>
            <AdaptationSet contentType="text" mimeType="text/vtt">
              <Representation id="sub" bandwidth="100"><BaseURL>subs.vtt</BaseURL></Representation>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test
    fun expandsTemplatesAndTimelines() {
        val plan = DashSegmentPlan.parse(templateMpd, "https://site.example/m.mpd")!!
        assertFalse(plan.isLive)
        assertFalse(plan.isProtected)
        assertEquals(10_000L, plan.durationMs)
        assertEquals(2, plan.videoTracks.size)
        assertEquals(1, plan.audioTracks.size) // text track skipped

        val hd = plan.videoTracks.first { it.height == 1080 }
        assertEquals("https://cdn.example/content/v/1080/init.mp4", hd.initSegment!!.url)
        assertEquals(3, hd.segments.size) // ceil(10 s / 4 s)
        assertEquals("https://cdn.example/content/v/1080/seg-0003.m4s", hd.segments.last().url)

        val audio = plan.audioTracks.single()
        assertEquals(4, audio.segments.size)
        assertEquals("https://cdn.example/content/a/t-288000.m4s", audio.segments.last().url)
        assertEquals("en", audio.language)
    }

    @Test
    fun selectsClosestQualityPlusAudio() {
        val plan = DashSegmentPlan.parse(templateMpd, "https://site.example/m.mpd")!!
        val picked = plan.select(targetHeight = 700, targetBitrate = null, audioOnly = false)
        assertEquals(listOf("720", "a1"), picked.map { it.id })
        assertEquals(listOf("1080", "a1"), plan.select(null, null, false).map { it.id })
        assertEquals(listOf("a1"), plan.select(null, null, true).map { it.id })
    }

    @Test
    fun segmentListAndSingleFile() {
        val mpd = """
            <MPD mediaPresentationDuration="PT2S"><Period>
              <AdaptationSet mimeType="video/mp4">
                <Representation id="v" bandwidth="1" height="360">
                  <SegmentList>
                    <Initialization sourceURL="video.mp4" range="0-99"/>
                    <SegmentURL media="video.mp4" mediaRange="100-199"/>
                    <SegmentURL media="video.mp4" mediaRange="200-299"/>
                  </SegmentList>
                </Representation>
              </AdaptationSet>
              <AdaptationSet mimeType="audio/mp4">
                <Representation id="a" bandwidth="1"><BaseURL>audio &amp; more.m4a</BaseURL><SegmentBase indexRange="0-10"/></Representation>
              </AdaptationSet>
            </Period></MPD>
        """.trimIndent()
        val plan = DashSegmentPlan.parse(mpd, "https://e.com/x/m.mpd")!!
        val video = plan.videoTracks.single()
        assertEquals(ByteRange(0, 100), video.initSegment!!.byteRange)
        assertEquals(ByteRange(200, 100), video.segments[1].byteRange)
        val audio = plan.audioTracks.single()
        assertNull(audio.initSegment)
        assertEquals("https://e.com/x/audio & more.m4a", audio.segments.single().url)
    }

    @Test
    fun flagsProtectionAndTemplateFill() {
        val mpd = """<MPD type="dynamic"><Period><AdaptationSet mimeType="video/mp4"><ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/><Representation id="v" bandwidth="1"/></AdaptationSet></Period></MPD>"""
        val plan = DashSegmentPlan.parse(mpd, "https://e.com/m.mpd")!!
        assertTrue(plan.isProtected)
        assertTrue(plan.isLive)
        assertEquals("a_7_00042_${'$'}x${'$'}_$", DashSegmentPlan.fill("a_${'$'}Bandwidth${'$'}_${'$'}Number%05d${'$'}_${'$'}x${'$'}_${'$'}${'$'}", "id", 7, 42, null))
    }

    @Test
    fun hlsMasterAudioGroups() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="French",LANGUAGE="fr",URI="audio/fr.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aac"
            v/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            v/360.m3u8
        """.trimIndent()
        val p = HlsPlaylistParser.parse(master, "https://e.com/s/master.m3u8") as HlsPlaylist.Master
        assertEquals("https://e.com/s/audio/en.m3u8", p.audioFor("https://e.com/s/v/1080.m3u8"))
        // No codecs listed and the stream offers separate audio: include it rather than risk silence.
        assertEquals("https://e.com/s/audio/en.m3u8", p.audioFor("https://e.com/s/v/360.m3u8"))
    }
}
