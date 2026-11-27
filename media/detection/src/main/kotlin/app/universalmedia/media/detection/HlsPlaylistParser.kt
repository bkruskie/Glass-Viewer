package app.universalmedia.media.detection

import app.universalmedia.core.model.MediaFormat
import app.universalmedia.core.model.MediaVariant
import app.universalmedia.core.model.Protection

data class HlsRendition(
    val type: String,
    val groupId: String,
    val name: String?,
    val language: String?,
    val uri: String?,
    val isDefault: Boolean = false,
)

sealed interface HlsPlaylist {
    val protection: Protection

    /** Multivariant ("master") playlist listing qualities. */
    data class Master(
        val variants: List<MediaVariant>,
        val renditions: List<HlsRendition>,
        override val protection: Protection,
        /** Variant URL → AUDIO group id, for streams that carry audio separately. */
        val audioGroups: Map<String, String> = emptyMap(),
    ) : HlsPlaylist {
        /** The separate audio playlist that goes with [variantUrl], if the stream has one. */
        fun audioFor(variantUrl: String): String? =
            variants.firstOrNull { it.url == variantUrl }?.let { audioFor(it) }

        /**
         * The separate audio playlist for [variant]: its AUDIO group's default track, or,
         * when the stream doesn't link them but the variant's codecs show it carries no
         * sound, the stream's default separate audio. Null when the sound is inside the video.
         */
        fun audioFor(variant: MediaVariant): String? {
            if (variant.audioOnly) return null
            val audio = renditions.filter { it.type == "AUDIO" }
            val group = audioGroups[variant.url]
            if (group != null) {
                val inGroup = audio.filter { it.groupId == group }
                val default = inGroup.firstOrNull { it.isDefault } ?: inGroup.firstOrNull()
                if (default != null && default.uri == null) return null // muxed into the video
                val withUri = inGroup.filter { it.uri != null }
                (withUri.firstOrNull { it.isDefault } ?: withUri.firstOrNull())?.let { return it.uri }
            }
            val codecs = variant.codecs?.lowercase() ?: return null
            val hasAudioCodec = AUDIO_CODECS.any { codecs.contains(it) }
            if (hasAudioCodec) return null
            val separate = audio.filter { it.uri != null }
            return (separate.firstOrNull { it.isDefault } ?: separate.firstOrNull())?.uri
        }

        /**
         * Finds the variant matching a previously chosen one. Links often carry expiring
         * tokens, so match by URL, then by path, then by resolution and bitrate.
         */
        fun match(url: String?, height: Int?, bitrate: Long?): MediaVariant? {
            val video = variants.filter { !it.audioOnly }.ifEmpty { variants }
            if (url != null) {
                video.firstOrNull { it.url == url }?.let { return it }
                val path = url.substringBefore('?')
                video.firstOrNull { it.url.substringBefore('?') == path }?.let { return it }
            }
            if (height == null && bitrate == null) return video.firstOrNull()
            return video.minWithOrNull(
                compareBy<MediaVariant>(
                    { kotlin.math.abs((it.height ?: 0) - (height ?: it.height ?: 0)) },
                    { kotlin.math.abs((it.bitrate ?: 0) - (bitrate ?: it.bitrate ?: 0)) },
                ),
            )
        }

        private companion object {
            val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "opus", "flac", "mp3", "dts")
        }
    }

    /** Media playlist listing segments of one rendition. */
    data class Media(
        val segmentCount: Int,
        val totalDurationSeconds: Double,
        val isLive: Boolean,
        override val protection: Protection,
    ) : HlsPlaylist
}

/** Parser for the subset of RFC 8216 needed to list qualities and detect protection. */
object HlsPlaylistParser {

    fun parse(text: String, playlistUrl: String): HlsPlaylist? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull()?.startsWith("#EXTM3U") != true) return null
        val isMaster = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        return if (isMaster) parseMaster(lines, playlistUrl) else parseMedia(lines)
    }

    private fun parseMaster(lines: List<String>, playlistUrl: String): HlsPlaylist.Master {
        val variants = mutableListOf<MediaVariant>()
        val audioGroups = mutableMapOf<String, String>()
        val renditions = mutableListOf<HlsRendition>()
        val protections = mutableListOf<Protection>()
        var pending: Map<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-STREAM-INF:") ->
                    pending = parseAttributes(line.substringAfter(':'))

                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = parseAttributes(line.substringAfter(':'))
                    renditions += HlsRendition(
                        type = a["TYPE"].orEmpty(),
                        groupId = a["GROUP-ID"].orEmpty(),
                        name = a["NAME"],
                        language = a["LANGUAGE"],
                        uri = a["URI"]?.let { UrlUtils.resolve(playlistUrl, it) },
                        isDefault = a["DEFAULT"].equals("YES", ignoreCase = true),
                    )
                }

                line.startsWith("#EXT-X-SESSION-KEY:") ->
                    protections += keyToProtection(parseAttributes(line.substringAfter(':')), playlistUrl)

                !line.startsWith("#") && pending != null -> {
                    val a = pending
                    val (w, h) = parseResolution(a["RESOLUTION"])
                    val codecs = a["CODECS"]
                    val variantUrl = UrlUtils.resolve(playlistUrl, line)
                    a["AUDIO"]?.let { audioGroups[variantUrl] = it }
                    variants += MediaVariant(
                        url = variantUrl,
                        format = MediaFormat.HLS,
                        width = w,
                        height = h,
                        bitrate = (a["AVERAGE-BANDWIDTH"] ?: a["BANDWIDTH"])?.toLongOrNull(),
                        codecs = codecs,
                        frameRate = a["FRAME-RATE"]?.toDoubleOrNull(),
                        audioOnly = h == null && codecs != null && isAudioOnlyCodecs(codecs),
                    )
                    pending = null
                }
            }
        }
        val sorted = variants.sortedWith(
            compareByDescending<MediaVariant> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 },
        )
        return HlsPlaylist.Master(sorted, renditions, DrmSystems.merge(protections), audioGroups)
    }

    private fun parseMedia(lines: List<String>): HlsPlaylist.Media {
        var segments = 0
        var duration = 0.0
        var ended = false
        var isVod = false
        val protections = mutableListOf<Protection>()
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    segments++
                    duration += line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                line.startsWith("#EXT-X-KEY:") ->
                    protections += keyToProtection(parseAttributes(line.substringAfter(':')), null)
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") ->
                    isVod = line.substringAfter(':').trim().equals("VOD", ignoreCase = true)
            }
        }
        return HlsPlaylist.Media(segments, duration, isLive = !(ended || isVod), DrmSystems.merge(protections))
    }

    private fun keyToProtection(a: Map<String, String>, baseUrl: String?): Protection {
        val method = a["METHOD"] ?: "NONE"
        if (method.equals("NONE", ignoreCase = true)) return Protection.None
        val drm = DrmSystems.fromHlsKeyFormat(a["KEYFORMAT"] ?: "identity")
        return if (drm != null) {
            Protection.Drm(setOf(drm))
        } else {
            val uri = a["URI"]
            Protection.StreamEncryption(method, if (uri != null && baseUrl != null) UrlUtils.resolve(baseUrl, uri) else uri)
        }
    }

    private fun parseResolution(value: String?): Pair<Int?, Int?> {
        val parts = value?.lowercase()?.split('x') ?: return null to null
        if (parts.size != 2) return null to null
        return parts[0].trim().toIntOrNull() to parts[1].trim().toIntOrNull()
    }

    private fun isAudioOnlyCodecs(codecs: String): Boolean =
        codecs.split(',').map { it.trim().lowercase() }.all {
            it.startsWith("mp4a") || it.startsWith("opus") || it.startsWith("ac-3") ||
                it.startsWith("ec-3") || it.startsWith("flac")
        }

    /** Parses an HLS attribute list, honouring quoted values that contain commas. */
    internal fun parseAttributes(input: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var i = 0
        while (i < input.length) {
            val eq = input.indexOf('=', i)
            if (eq < 0) break
            val key = input.substring(i, eq).trim()
            var j = eq + 1
            val value: String
            if (j < input.length && input[j] == '"') {
                val end = input.indexOf('"', j + 1).let { if (it < 0) input.length else it }
                value = input.substring(j + 1, end)
                j = (end + 1).coerceAtMost(input.length)
            } else {
                val end = input.indexOf(',', j).let { if (it < 0) input.length else it }
                value = input.substring(j, end).trim()
                j = end
            }
            if (key.isNotEmpty()) result[key.uppercase()] = value
            i = if (j < input.length && input[j] == ',') j + 1 else j
            if (j >= input.length) break
        }
        return result
    }
}
