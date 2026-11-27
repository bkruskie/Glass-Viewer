package app.universalmedia.core.model

/** Known DRM systems, identified by their DASH/HLS system identifiers. */
enum class DrmSystem(val displayName: String) {
    WIDEVINE("Widevine"),
    PLAYREADY("PlayReady"),
    FAIRPLAY("FairPlay"),
    CLEARKEY("ClearKey"),
    UNKNOWN("Unknown DRM"),
}

/**
 * Technical protection detected on a media source.
 *
 * The app distinguishes three separate questions (see ARCHITECTURE.md §6):
 *  1. Does the user own / have rights to the content?
 *  2. Is the user authorized to access the media (signed in, purchased)?
 *  3. Is removing a technical protection measure authorized?
 * Only the first two can ever be satisfied by the app; DRM is never removed.
 */
sealed interface Protection {
    /** Plain, unencrypted media. */
    data object None : Protection

    /**
     * Standard HLS segment encryption (AES-128 / SAMPLE-AES with the "identity"
     * key format). Keys are delivered by the origin to the user's own session and
     * consumed by standard players exactly as a browser would.
     */
    data class StreamEncryption(val method: String, val keyUri: String?) : Protection

    /** Licence-based DRM (Widevine, PlayReady, FairPlay...). Never decrypted by the app. */
    data class Drm(val systems: Set<DrmSystem>) : Protection
}

/** Outcome of evaluating whether a source can be acted upon. */
enum class Availability(val label: String) {
    SUPPORTED("Available"),
    UNSUPPORTED("Not supported yet"),
    INACCESSIBLE("Not reachable"),
    AUTH_REQUIRED("Sign-in required"),
    PROTECTED("DRM protected"),
}
