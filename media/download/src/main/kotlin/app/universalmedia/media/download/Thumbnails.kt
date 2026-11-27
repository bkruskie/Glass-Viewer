package app.universalmedia.media.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes small preview images for saved media: a frame from about 10% into a
 * video, or a song's embedded cover art. Stored privately in app storage.
 */
object Thumbnails {
    private const val WIDTH = 480
    private const val HEIGHT = 270

    fun dir(context: Context): File = File(context.filesDir, "thumbnails").apply { mkdirs() }

    /** Creates the preview for [uri] and returns its file path, or null if none could be made. */
    suspend fun create(context: Context, uri: Uri, key: String, isAudio: Boolean): String? = withContext(Dispatchers.IO) {
        val bitmap = (if (isAudio) coverArt(context, uri) else videoFrame(context, uri)) ?: return@withContext null
        val file = File(dir(context), "${key.filter { it.isLetterOrDigit() || it == '-' }.take(64)}.jpg")
        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            file.absolutePath
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun videoFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val at = (durationUs / 10).coerceAtMost(30_000_000L) // ~10% in, at most 30 s, to skip black intros
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, HEIGHT)
            } else {
                retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (frame != null) return frame
        } catch (e: Exception) {
            // fall through to the system thumbnail
        } finally {
            runCatching { retriever.release() }
        }
        return systemThumbnail(context, uri)
    }

    private fun coverArt(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { bytes ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun systemThumbnail(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(WIDTH, HEIGHT), null) }.getOrNull()
        } else {
            null
        }
}
