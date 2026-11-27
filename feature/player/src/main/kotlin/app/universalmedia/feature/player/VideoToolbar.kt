package app.universalmedia.feature.player

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.universalmedia.media.playback.PlaybackManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Speeds offered, slowest first: super slow motion to 4×. */
private val SPEEDS = listOf(
    0.1f to "0.1× · Super slow-mo",
    0.25f to "0.25× · Slow-mo",
    0.5f to "0.5×",
    0.75f to "0.75×",
    1f to "1× · Normal",
    1.25f to "1.25×",
    1.5f to "1.5×",
    2f to "2×",
    3f to "3×",
    4f to "4×",
)

private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed.toString().trimEnd('0')}×"

/**
 * Extra controls shown with the player's own controls: speed (incl. slow motion),
 * frame stepping, fit / fill / stretch, loop and snapshot.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
internal fun VideoToolbar(
    playback: PlaybackManager,
    view: PlayerView?,
    gestures: VideoGestures?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speed by playback.speed.collectAsStateWithLifecycle()
    var speedMenu by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(view?.resizeMode ?: AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var loop by remember { mutableStateOf(playback.player.repeatMode == Player.REPEAT_MODE_ONE) }
    val white = Color.White
    val accent = MaterialTheme.colorScheme.secondary
    val subtitlePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
        val ok = playback.addSubtitle(uri.toString(), name)
        Toast.makeText(
            context,
            if (ok) "Subtitles added. Change them with the CC button." else "That isn't a subtitle file (.srt, .vtt, .ass, .ttml).",
            Toast.LENGTH_SHORT,
        ).show()
    }

    Row(
        modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { speedMenu = true }) {
                Text(speedLabel(speed), color = if (speed != 1f) accent else white, style = MaterialTheme.typography.labelLarge)
            }
            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                SPEEDS.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, color = if (value == speed) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                        onClick = {
                            playback.setSpeed(value)
                            speedMenu = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = { playback.stepFrame(-1) }) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous frame", tint = white)
        }
        IconButton(onClick = { playback.stepFrame(1) }) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Next frame", tint = white)
        }
        IconButton(onClick = {
            resizeMode = when (resizeMode) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            view?.resizeMode = resizeMode
            gestures?.resetZoom()
            Toast.makeText(
                context,
                when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Fill screen (crop)"
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                    else -> "Fit"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }) {
            Icon(
                when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> Icons.Rounded.Crop
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> Icons.Rounded.AspectRatio
                    else -> Icons.Rounded.FitScreen
                },
                contentDescription = "Fit, fill or stretch",
                tint = white,
            )
        }
        IconButton(onClick = { subtitlePicker.launch(arrayOf("*/*")) }) {
            Icon(Icons.Rounded.Subtitles, contentDescription = "Add subtitle file", tint = white)
        }
        IconButton(onClick = {
            loop = !loop
            playback.player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }) {
            Icon(
                if (loop) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = if (loop) "Loop on" else "Loop off",
                tint = if (loop) accent else white,
            )
        }
        IconButton(onClick = {
            val surface = view?.videoSurfaceView as? TextureView
            val bitmap = surface?.bitmap
            if (bitmap == null) {
                Toast.makeText(context, "No picture to capture yet", Toast.LENGTH_SHORT).show()
            } else {
                val title = playback.current.value?.title ?: "Snapshot"
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { saveSnapshot(context, bitmap, title) }
                    Toast.makeText(context, if (saved) "Snapshot saved to Pictures › Universal Media" else "Couldn't save the snapshot", Toast.LENGTH_SHORT).show()
                }
            }
        }) {
            Icon(Icons.Rounded.PhotoCamera, contentDescription = "Save this frame as a photo", tint = white)
        }
    }
}

/** Saves a frame to Pictures/Universal Media (or the app's Pictures folder on Android 8–9). */
private fun saveSnapshot(context: Context, bitmap: Bitmap, title: String): Boolean = try {
    val name = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").take(60).trim().ifBlank { "Snapshot" } +
        " " + System.currentTimeMillis() + ".jpg"
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Universal Media")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri != null && context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } == true
    } else {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Universal Media").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }
} catch (e: Exception) {
    false
} finally {
    bitmap.recycle()
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
