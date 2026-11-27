package app.universalmedia.media.cast

data class RemoteStatus(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val ended: Boolean = false,
)
