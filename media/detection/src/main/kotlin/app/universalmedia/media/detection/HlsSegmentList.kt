package app.universalmedia.media.detection

/** Byte range within a resource (EXT-X-BYTERANGE / EXT-X-MAP BYTERANGE). */
data class ByteRange(val offset: Long, val length: Long) {
    val rangeHeader: String get() = "bytes=$offset-${offset + length - 1}"
}

/** AES-128 key that applies to a run of segments. */
data class SegmentKey(val method: String, val uri: String, val iv: ByteArray?) {
    override fun equals(other: Any?): Boolean =
        other is SegmentKey && other.method == method && other.uri == uri && (other.iv?.contentEquals(iv) ?: (iv == null))

    override fun hashCode(): Int = 31 * (31 * method.hashCode() + uri.hashCode()) + (iv?.contentHashCode() ?: 0)
}

data class HlsSegment(
    val url: String,
    val durationSeconds: Double,
    val sequence: Long,
    val key: SegmentKey?,
    val byteRange: ByteRange? = null,
) {
    /** IV per RFC 8216 §5.2: explicit IV, else the media sequence number as a 128-bit big-endian value. */
    fun effectiveIv(): ByteArray = key?.iv ?: ByteArray(16).also { buf ->
        var n = sequence
        for (i in 15 downTo 8) {
            buf[i] = (n and 0xFF).toByte()
            n = n shr 8
        }
    }
}

/** A media playlist fully expanded for downloading. */
data class HlsSegmentList(
    val segments: List<HlsSegment>,
    /** EXT-X-MAP initialisation section (fragmented MP4 streams). */
    val initUrl: String?,
    val initByteRange: ByteRange?,
    val isLive: Boolean,
    /** Non-null when the stream can't be saved, with a user-facing reason. */
    val unsupportedReason: String?,
) {
    val isFragmentedMp4: Boolean get() = initUrl != null
    val totalDurationSeconds: Double get() = segments.sumOf { it.durationSeconds }

    companion object {
        /** Parses a media playlist. Returns null for master playlists or non-HLS text. */
        fun parse(text: String, playlistUrl: String): HlsSegmentList? {
            val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return null
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) return null

            val segments = mutableListOf<HlsSegment>()
            var sequence = 0L
            var pendingDuration: Double? = null
            var pendingRange: ByteRange? = null
            var lastRangeEnd = 0L
            var key: SegmentKey? = null
            var initUrl: String? = null
            var initRange: ByteRange? = null
            var ended = false
            var vod = false
            var unsupported: String? = null

            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                        sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    line.startsWith("#EXTINF:") ->
                        pendingDuration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    line.startsWith("#EXT-X-BYTERANGE:") -> {
                        val spec = line.substringAfter(':').trim()
                        val length = spec.substringBefore('@').toLongOrNull() ?: 0L
                        val offset = if (spec.contains('@')) spec.substringAfter('@').toLongOrNull() ?: 0L else lastRangeEnd
                        pendingRange = ByteRange(offset, length)
                        lastRangeEnd = offset + length
                    }
                    line.startsWith("#EXT-X-KEY:") -> {
                        val a = HlsPlaylistParser.parseAttributes(line.substringAfter(':'))
                        val method = a["METHOD"]?.uppercase() ?: "NONE"
                        val format = a["KEYFORMAT"] ?: "identity"
                        key = when {
                            method == "NONE" -> null
                            !format.equals("identity", ignoreCase = true) -> {
                                unsupported = "This stream is protected by DRM and can't be saved."
                                null
                            }
                            method != "AES-128" -> {
                                unsupported = "This stream uses $method encryption, which can't be saved yet."
                                null
                            }
                            else -> SegmentKey(method, UrlUtils.resolve(playlistUrl, a["URI"].orEmpty()), a["IV"]?.let(::parseIv))
                        }
                    }
                    line.startsWith("#EXT-X-MAP:") -> {
                        val a = HlsPlaylistParser.parseAttributes(line.substringAfter(':'))
                        initUrl = a["URI"]?.let { UrlUtils.resolve(playlistUrl, it) }
                        initRange = a["BYTERANGE"]?.let { spec ->
                            val length = spec.substringBefore('@').toLongOrNull() ?: return@let null
                            ByteRange(spec.substringAfter('@', "0").toLongOrNull() ?: 0L, length)
                        }
                    }
                    line.startsWith("#EXT-X-ENDLIST") -> ended = true
                    line.startsWith("#EXT-X-PLAYLIST-TYPE:") ->
                        vod = line.substringAfter(':').trim().equals("VOD", ignoreCase = true)
                    !line.startsWith("#") -> {
                        val duration = pendingDuration
                        if (duration != null) {
                            segments += HlsSegment(UrlUtils.resolve(playlistUrl, line), duration, sequence, key, pendingRange)
                            sequence++
                        }
                        pendingDuration = null
                        pendingRange = null
                    }
                }
            }
            val live = !(ended || vod)
            return HlsSegmentList(
                segments = segments,
                initUrl = initUrl,
                initByteRange = initRange,
                isLive = live,
                unsupportedReason = unsupported ?: if (live) "Live streams can't be saved yet." else null,
            )
        }

        private fun parseIv(value: String): ByteArray? {
            val hex = value.removePrefix("0x").removePrefix("0X")
            if (hex.length != 32) return null
            return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }
    }
}
