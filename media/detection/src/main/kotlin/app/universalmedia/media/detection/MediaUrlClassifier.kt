package app.universalmedia.media.detection

import app.universalmedia.core.model.MediaFormat

/**
 * Fast, allocation-light classifier used on the browser's network thread for
 * every request. It must never block or do I/O.
 */
object MediaUrlClassifier {

    sealed interface Result {
        data class Media(val format: MediaFormat) : Result

        /** A fragment of a stream (HLS .ts, DASH .m4s). Not useful on its own. */
        data object Segment : Result

        data object NotMedia : Result
    }

    private val segmentExtensions = setOf("ts", "m4s", "cmfv", "cmfa", "aac.part")
    private val segmentMimeTypes = setOf("video/mp2t", "video/iso.segment", "audio/iso.segment")

    fun classify(url: String, mimeType: String? = null): Result {
        if (!UrlUtils.isHttp(url.trim())) return Result.NotMedia

        if (!mimeType.isNullOrBlank()) {
            val mime = mimeType.substringBefore(';').trim().lowercase()
            if (mime in segmentMimeTypes) return Result.Segment
            MediaFormat.fromMimeType(mime)?.let { return Result.Media(it) }
        }

        val ext = UrlUtils.extensionOf(url) ?: return Result.NotMedia
        if (ext in segmentExtensions) return Result.Segment
        return MediaFormat.fromExtension(ext)?.let { Result.Media(it) } ?: Result.NotMedia
    }

    /** True for results worth showing to the user (video, audio, stream manifests). */
    fun isActionable(result: Result): Boolean =
        result is Result.Media && result.format.isDownloadableKind
}
