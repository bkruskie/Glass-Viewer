package app.universalmedia.media.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Moves finished files to where the user can find them. */
internal class DownloadStorage(private val context: Context) {

    /**
     * Copies [source] to the user's chosen folder ([treeUri], via the Storage Access
     * Framework) or, by default, to Movies/Universal Media (Music/… for audio).
     * Returns a content:// URI other apps can open.
     */
    suspend fun publish(source: File, fileName: String, mime: String, isAudio: Boolean, treeUri: String?): Uri =
        withContext(Dispatchers.IO) {
            if (treeUri != null) {
                runCatching { publishToTree(source, fileName, mime, Uri.parse(treeUri)) }.getOrNull()?.let { return@withContext it }
                // Folder gone or permission revoked: fall back to the default location.
            }
            if (Build.VERSION.SDK_INT >= 29) publishToMediaStore(source, fileName, mime, isAudio)
            else publishToAppFolder(source, fileName, isAudio)
        }

    private fun publishToTree(source: File, fileName: String, mime: String, tree: Uri): Uri {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(resolver, parent, mime, fileName)
            ?: throw DownloadException("Couldn't create the file in the chosen folder.")
        copy(source, doc)
        return doc
    }

    private fun publishToMediaStore(source: File, fileName: String, mime: String, isAudio: Boolean): Uri {
        val resolver = context.contentResolver
        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/Universal Media"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw DownloadException("Couldn't save to your media folder.")
        try {
            copy(source, uri)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** Android 8–9: app-owned public folder, shared through FileProvider (no storage permission needed). */
    private fun publishToAppFolder(source: File, fileName: String, isAudio: Boolean): Uri {
        val base = context.getExternalFilesDir(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val dir = File(base, "Universal Media").apply { mkdirs() }
        var dest = File(dir, fileName)
        var n = 1
        while (dest.exists()) {
            dest = File(dir, fileName.substringBeforeLast('.') + " ($n)." + fileName.substringAfterLast('.', "bin"))
            n++
        }
        source.copyTo(dest)
        return FileProvider.getUriForFile(context, context.packageName + ".files", dest)
    }

    private fun copy(source: File, target: Uri) {
        val out = context.contentResolver.openOutputStream(target, "w")
            ?: throw DownloadException("Couldn't write the file.")
        out.use { o -> source.inputStream().use { it.copyTo(o, 256 * 1024) } }
    }

    fun readDurationMs(uri: Uri): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * Repackages an MPEG-TS file as MP4 without re-encoding, using Media3 Transformer.
 * Returns false if the streams can't be transmuxed; the caller then keeps the .ts.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal suspend fun remuxToMp4(context: Context, input: File, output: File): Boolean = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { cont ->
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            .build()
        val main = Handler(Looper.getMainLooper())
        cont.invokeOnCancellation { main.post { runCatching { transformer.cancel() } } }
        try {
            transformer.start(MediaItem.fromUri(Uri.fromFile(input)), output.absolutePath)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }
}

internal object FileNames {
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")

    fun build(title: String?, quality: String?, extension: String): String {
        val base = (title ?: "").replace(illegal, " ").replace(spaces, " ").trim().take(80).ifBlank { "Video" }
        val q = quality?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return "$base$q.$extension"
    }
}
