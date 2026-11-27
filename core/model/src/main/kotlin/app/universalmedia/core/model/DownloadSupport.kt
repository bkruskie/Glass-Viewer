package app.universalmedia.core.model

/** What the download engine can save. Shared by the UI (Save button) and the engine. */
object DownloadSupport {

    private val progressive = setOf(
        MediaFormat.MP4, MediaFormat.WEBM, MediaFormat.MKV, MediaFormat.MOV, MediaFormat.THREE_GP,
        MediaFormat.M4A, MediaFormat.MP3, MediaFormat.AAC, MediaFormat.OGG, MediaFormat.OPUS,
        MediaFormat.FLAC, MediaFormat.WAV,
    )

    fun isProgressive(format: MediaFormat): Boolean = format in progressive

    /** Null when the selected source can be saved, else a short user-facing reason. */
    fun reasonNotSupported(media: DetectedMedia, variant: MediaVariant?): String? {
        when (media.availability) {
            Availability.PROTECTED -> return "Protected by DRM. It can only be watched on the site."
            Availability.AUTH_REQUIRED -> return "Sign in on the site first, then try again."
            Availability.INACCESSIBLE -> return "The source isn't reachable right now."
            Availability.UNSUPPORTED -> return "This source isn't supported yet."
            Availability.SUPPORTED -> Unit
        }
        if (media.note == "Live stream") return "Live streams can't be saved yet."
        val format = variant?.format ?: media.format
        return when {
            isProgressive(format) -> null
            format == MediaFormat.HLS -> null
            format == MediaFormat.DASH -> null
            else -> "This format can't be saved."
        }
    }
}
