package app.universalmedia.core.model

enum class DownloadStatus(val label: String) {
    QUEUED("Queued"),
    RUNNING("Downloading"),
    PAUSED("Paused"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    val isActive: Boolean get() = this == QUEUED || this == RUNNING

    companion object {
        fun parse(value: String): DownloadStatus = entries.firstOrNull { it.name == value } ?: FAILED
    }
}

/** What the user asked the download engine to fetch (implemented in Milestone 3). */
data class DownloadRequest(
    val media: DetectedMedia,
    val variant: MediaVariant?,
    val fileName: String,
    /** Storage Access Framework tree URI, or null for the app's default folder. */
    val destinationTreeUri: String? = null,
    val audioOnly: Boolean = false,
)
