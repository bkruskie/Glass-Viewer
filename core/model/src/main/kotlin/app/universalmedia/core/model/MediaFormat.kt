package app.universalmedia.core.model

/** Broad category of a media resource. */
enum class MediaKind { VIDEO, AUDIO, STREAM_MANIFEST, SUBTITLE, UNKNOWN }

/**
 * Container / delivery formats the app understands.
 * Extensions and MIME types are used by the detection engine to classify URLs.
 */
enum class MediaFormat(
    val displayName: String,
    val kind: MediaKind,
    val extensions: Set<String>,
    val mimeTypes: Set<String>,
) {
    MP4("MP4", MediaKind.VIDEO, setOf("mp4", "m4v"), setOf("video/mp4")),
    WEBM("WebM", MediaKind.VIDEO, setOf("webm"), setOf("video/webm")),
    MKV("MKV", MediaKind.VIDEO, setOf("mkv"), setOf("video/x-matroska")),
    MOV("MOV", MediaKind.VIDEO, setOf("mov"), setOf("video/quicktime")),
    THREE_GP("3GP", MediaKind.VIDEO, setOf("3gp"), setOf("video/3gpp")),
    M4A("M4A", MediaKind.AUDIO, setOf("m4a"), setOf("audio/mp4", "audio/x-m4a")),
    MP3("MP3", MediaKind.AUDIO, setOf("mp3"), setOf("audio/mpeg", "audio/mp3")),
    AAC("AAC", MediaKind.AUDIO, setOf("aac"), setOf("audio/aac")),
    OGG("Ogg", MediaKind.AUDIO, setOf("ogg", "oga"), setOf("audio/ogg")),
    OPUS("Opus", MediaKind.AUDIO, setOf("opus"), setOf("audio/opus")),
    FLAC("FLAC", MediaKind.AUDIO, setOf("flac"), setOf("audio/flac", "audio/x-flac")),
    WAV("WAV", MediaKind.AUDIO, setOf("wav"), setOf("audio/wav", "audio/x-wav")),
    HLS(
        "HLS stream", MediaKind.STREAM_MANIFEST, setOf("m3u8"),
        setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl"),
    ),
    DASH("DASH stream", MediaKind.STREAM_MANIFEST, setOf("mpd"), setOf("application/dash+xml")),
    VTT("WebVTT", MediaKind.SUBTITLE, setOf("vtt"), setOf("text/vtt")),
    SRT("SubRip", MediaKind.SUBTITLE, setOf("srt"), setOf("application/x-subrip")),
    UNKNOWN("Unknown", MediaKind.UNKNOWN, emptySet(), emptySet());

    val isDownloadableKind: Boolean
        get() = kind == MediaKind.VIDEO || kind == MediaKind.AUDIO || kind == MediaKind.STREAM_MANIFEST

    companion object {
        fun fromExtension(ext: String): MediaFormat? {
            val e = ext.lowercase()
            return entries.firstOrNull { e in it.extensions }
        }

        fun fromMimeType(mime: String): MediaFormat? {
            val m = mime.substringBefore(';').trim().lowercase()
            if (m.isEmpty()) return null
            return entries.firstOrNull { m in it.mimeTypes }
        }
    }
}
