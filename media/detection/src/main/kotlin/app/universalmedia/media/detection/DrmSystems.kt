package app.universalmedia.media.detection

import app.universalmedia.core.model.DrmSystem
import app.universalmedia.core.model.Protection

/** Maps DASH system IDs and HLS KEYFORMAT strings to [DrmSystem]. Identification only. */
internal object DrmSystems {
    private val byUuid = mapOf(
        "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed" to DrmSystem.WIDEVINE,
        "9a04f079-9840-4286-ab92-e65be0885f95" to DrmSystem.PLAYREADY,
        "94ce86fb-07ff-4f43-adb8-93d2fa968ca2" to DrmSystem.FAIRPLAY,
        "e2719d58-a985-b3c9-781a-b030af78d30e" to DrmSystem.CLEARKEY,
        "1077efec-c0b2-4d02-ace3-3c1e52e2fb4b" to DrmSystem.CLEARKEY,
    )

    fun fromUuid(value: String): DrmSystem? =
        byUuid[value.lowercase().removePrefix("urn:uuid:").trim()]

    /** HLS KEYFORMAT attribute. "identity" (or absent) means standard AES, not DRM. */
    fun fromHlsKeyFormat(keyFormat: String): DrmSystem? {
        val f = keyFormat.lowercase()
        return when {
            f == "identity" -> null
            f == "com.apple.streamingkeydelivery" -> DrmSystem.FAIRPLAY
            f == "com.microsoft.playready" -> DrmSystem.PLAYREADY
            f == "org.w3.clearkey" -> DrmSystem.CLEARKEY
            f.startsWith("urn:uuid:") -> fromUuid(f) ?: DrmSystem.UNKNOWN
            else -> DrmSystem.UNKNOWN
        }
    }

    /** Combines protections found in several places of one manifest. DRM wins. */
    fun merge(items: List<Protection>): Protection {
        val drm = items.filterIsInstance<Protection.Drm>().flatMap { it.systems }.toSet()
        if (drm.isNotEmpty()) return Protection.Drm(drm)
        return items.firstOrNull { it is Protection.StreamEncryption } ?: Protection.None
    }
}
