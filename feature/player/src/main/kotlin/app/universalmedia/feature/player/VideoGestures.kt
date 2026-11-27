package app.universalmedia.feature.player

import android.annotation.SuppressLint
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Touch gestures on the video:
 *  - pinch to zoom (1×–6×), drag to pan while zoomed, double-tap the middle to reset;
 *  - double-tap the left / right third to jump back / forward 10 s;
 *  - swipe up/down on the left half for brightness, right half for volume.
 * Single taps still reach the player, which shows and hides its controls.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class VideoGestures(
    private val view: PlayerView,
    private val player: () -> Player?,
    private val window: () -> Window?,
    private val show: (String) -> Unit,
) : View.OnTouchListener {

    private enum class Mode { NONE, SCALE, PAN, BRIGHTNESS, VOLUME }

    private val context = view.context
    private val audio = context.getSystemService(AudioManager::class.java)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    var zoom = 1f
        private set
    private var panX = 0f
    private var panY = 0f
    private var mode = Mode.NONE
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startValue = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = Mode.SCALE
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                applyTransform()
                show("Zoom %.1f×".format(zoom))
                return true
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val third = view.width / 3f
                when {
                    e.x < third -> seekBy(-SEEK_MS)
                    e.x > third * 2 -> seekBy(SEEK_MS)
                    zoom > 1f -> {
                        resetZoom()
                        show("Zoom reset")
                    }
                    else -> player()?.let { if (it.isPlaying) it.pause() else it.play() }
                }
                return true
            }
        },
    )

    fun resetZoom() {
        zoom = 1f
        panX = 0f
        panY = 0f
        applyTransform()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player() ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
        show(if (deltaMs < 0) "−${-deltaMs / 1000} s" else "+${deltaMs / 1000} s")
    }

    private fun applyTransform() {
        val surface = view.videoSurfaceView ?: return
        val maxX = (zoom - 1f) * surface.width / 2f
        val maxY = (zoom - 1f) * surface.height / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        surface.scaleX = zoom
        surface.scaleY = zoom
        surface.translationX = panX
        surface.translationY = panY
    }

    private fun currentBrightness(): Float {
        val value = window()?.attributes?.screenBrightness ?: -1f
        return if (value in 0f..1f) value else 0.5f
    }

    @SuppressLint("ClickableViewAccessibility") // taps fall through to PlayerView, which handles accessibility
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val doubleTap = tapDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                lastX = event.x
                lastY = event.y
                mode = Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.SCALE || event.pointerCount > 1) return true
                val dx = event.x - startX
                val dy = event.y - startY
                if (mode == Mode.NONE) {
                    if (zoom > 1f && hypot(dx, dy) > slop) {
                        mode = Mode.PAN
                    } else if (zoom == 1f && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.5f) {
                        if (startX < view.width / 2f) {
                            mode = Mode.BRIGHTNESS
                            startValue = currentBrightness()
                        } else {
                            mode = Mode.VOLUME
                            startValue = (audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat()
                        }
                    }
                }
                val travel = view.height * 0.8f
                when (mode) {
                    Mode.PAN -> {
                        panX += event.x - lastX
                        panY += event.y - lastY
                        applyTransform()
                    }
                    Mode.BRIGHTNESS -> {
                        val value = (startValue - dy / travel).coerceIn(0.01f, 1f)
                        window()?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value } }
                        show("Brightness ${(value * 100).roundToInt()}%")
                    }
                    Mode.VOLUME -> audio?.let { a ->
                        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val value = (startValue - dy / travel * max).roundToInt().coerceIn(0, max)
                        runCatching { a.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0) }
                        show("Volume $value / $max")
                    }
                    else -> Unit
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = mode != Mode.NONE
                mode = Mode.NONE
                if (consumed) return true
            }
        }
        return mode != Mode.NONE || doubleTap || event.pointerCount > 1
    }

    companion object {
        private const val MAX_ZOOM = 6f
        private const val SEEK_MS = 10_000L

        /** Gives the screen back its normal brightness when leaving the player. */
        fun restoreBrightness(window: Window?) {
            window ?: return
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}
