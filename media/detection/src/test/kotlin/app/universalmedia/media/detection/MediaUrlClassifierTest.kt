package app.universalmedia.media.detection

import app.universalmedia.core.model.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlClassifierTest {

    private fun formatOf(url: String, mime: String? = null): MediaFormat? =
        (MediaUrlClassifier.classify(url, mime) as? MediaUrlClassifier.Result.Media)?.format

    @Test
    fun classifiesDirectFilesByExtension() {
        assertEquals(MediaFormat.MP4, formatOf("https://cdn.example.com/v/clip.MP4?sig=abc#t=10"))
        assertEquals(MediaFormat.WEBM, formatOf("https://example.com/a/b/movie.webm"))
        assertEquals(MediaFormat.MP3, formatOf("http://example.com/podcast/ep1.mp3"))
    }

    @Test
    fun classifiesManifests() {
        assertEquals(MediaFormat.HLS, formatOf("https://example.com/live/master.m3u8?token=1"))
        assertEquals(MediaFormat.DASH, formatOf("https://example.com/vod/manifest.mpd"))
    }

    @Test
    fun mimeTypeTakesPriorityOverExtension() {
        assertEquals(MediaFormat.HLS, formatOf("https://example.com/playlist?id=7", "application/vnd.apple.mpegurl"))
        assertEquals(MediaFormat.MP4, formatOf("https://example.com/stream", "video/mp4; codecs=avc1"))
    }

    @Test
    fun ignoresSegmentsAndNonHttp() {
        assertEquals(MediaUrlClassifier.Result.Segment, MediaUrlClassifier.classify("https://e.com/seg_001.ts"))
        assertEquals(MediaUrlClassifier.Result.Segment, MediaUrlClassifier.classify("https://e.com/chunk-5.m4s"))
        assertEquals(MediaUrlClassifier.Result.NotMedia, MediaUrlClassifier.classify("blob:https://e.com/1234"))
        assertEquals(MediaUrlClassifier.Result.NotMedia, MediaUrlClassifier.classify("https://e.com/index.html"))
        assertEquals(MediaUrlClassifier.Result.NotMedia, MediaUrlClassifier.classify("https://e.com/"))
    }

    @Test
    fun subtitlesAreRecognisedButNotActionable() {
        val result = MediaUrlClassifier.classify("https://e.com/subs/en.vtt")
        assertEquals(MediaFormat.VTT, (result as MediaUrlClassifier.Result.Media).format)
        assertFalse(MediaUrlClassifier.isActionable(result))
        assertTrue(MediaUrlClassifier.isActionable(MediaUrlClassifier.classify("https://e.com/x.m3u8")))
    }
}
