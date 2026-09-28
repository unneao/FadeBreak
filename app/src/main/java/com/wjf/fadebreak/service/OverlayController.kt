package com.wjf.fadebreak.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.wjf.fadebreak.core.DebugLog
import com.wjf.fadebreak.ui.overlay.OverlayView
import kotlin.math.abs

/**
 * Owns the full-screen break overlay.
 *
 * Two overlay windows are used so that letting touches through on dismissal never
 * disturbs the visible one:
 *  - the visible window draws the fading background and is created as
 *    [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE], so touches fall straight through it;
 *  - a fully transparent window on top only catches the tap that dismisses the break.
 *
 * Removing the transparent catcher on dismissal is invisible and instantly hands
 * gestures to the app underneath. Re-touching the *visible* window mid-fade (toggling
 * FLAG_NOT_TOUCHABLE with `updateViewLayout`) makes it flash on some ROMs, hence the
 * split.
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: OverlayView? = null
    private var catcher: View? = null
    private var fadeRunnable: Runnable? = null
    private var dismissing = false

    var fadeOutMs: Long = 800L
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(fadeDurationMs: Long = 3000L, maxAlpha: Float = 0.85f) {
        if (view != null || !canDraw()) return

        val overlay = OverlayView(context)
        val overlayAdded = runCatching {
            windowManager.addView(
                overlay,
                overlayParams(extraFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            )
        }
        if (overlayAdded.isFailure) {
            DebugLog.e("overlay addView failed", overlayAdded.exceptionOrNull())
            return
        }

        // The catcher sits above the (untouchable) overlay and owns the dismiss tap.
        // If it cannot be added the overlay would be impossible to dismiss by tap, so
        // roll the overlay back instead of leaving it stuck.
        val catcher = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) dismiss()
                return true
            }
        }
        val catcherAdded = runCatching {
            windowManager.addView(catcher, overlayParams(extraFlags = 0))
        }
        if (catcherAdded.isFailure) {
            DebugLog.e("overlay catcher addView failed", catcherAdded.exceptionOrNull())
            runCatching { windowManager.removeView(overlay) }
            return
        }
        DebugLog.d("overlay added")

        this.catcher = catcher
        view = overlay
        dismissing = false
        animate(overlay, 0f, maxAlpha.coerceIn(0f, 1f), fadeDurationMs)
    }

    fun dismiss() {
        val overlay = view ?: return
        if (dismissing) return
        DebugLog.d("overlay dismiss requested")
        dismissing = true
        // Drop the invisible catcher: gestures now reach the app below while the
        // visible window keeps fading, untouched.
        removeCatcher()
        animate(overlay, overlay.alphaFraction, 0f, fadeOutMs) { finishDismiss() }
    }

    private fun finishDismiss() {
        fadeRunnable = null
        dismissing = false
        view?.let {
            runCatching { windowManager.removeView(it) }
            releaseBackground(it)
        }
        view = null
        removeCatcher()
        DebugLog.d("overlay removed")
        onDismissed?.invoke()
    }

    fun hide() {
        cancelFade()
        dismissing = false
        if (view != null) DebugLog.d("overlay hidden")
        view?.let {
            runCatching { windowManager.removeView(it) }
            releaseBackground(it)
        }
        view = null
        removeCatcher()
    }

    fun updateBackground(bitmap: Bitmap?) {
        val overlay = view ?: return
        val old = overlay.background
        overlay.background = bitmap
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
    }

    /** The overlay owns its bitmap; free the native memory once it is detached. */
    private fun releaseBackground(overlay: OverlayView) {
        val bitmap = overlay.background
        overlay.background = null
        if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
    }

    private fun removeCatcher() {
        catcher?.let { runCatching { windowManager.removeView(it) } }
        catcher = null
    }

    private fun overlayParams(extraFlags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                extraFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Don't inset the window by system bars: cover status bar and navigation bar.
                setFitInsetsTypes(0)
            }
        }

    /** Drives [overlay].alphaFraction from [from] to [to]; [onEnd] runs on completion. */
    private fun animate(
        overlay: OverlayView,
        from: Float,
        to: Float,
        durationMs: Long,
        onEnd: (() -> Unit)? = null
    ) {
        cancelFade()
        if (durationMs <= 0L) {
            overlay.alphaFraction = to
            onEnd?.invoke()
            return
        }
        val start = SystemClock.uptimeMillis()
        val span = to - from
        // Progress is derived from the wall clock, so a main thread stalled by a window
        // change or GC would make the next frame jump straight to the alpha for the
        // elapsed time -- a visible flash. Cap how far one frame may move: the fade
        // stays smooth and simply takes a little longer when frames run late.
        val maxStep = abs(span) * (FRAME_MS.toFloat() / durationMs) * MAX_STEP_FACTOR
        var current = from
        val runnable = object : Runnable {
            override fun run() {
                val progress =
                    ((SystemClock.uptimeMillis() - start).toFloat() / durationMs)
                        .coerceIn(0f, 1f)
                val target = if (progress >= 1f) to else from + span * progress
                val remaining = target - current
                current = when {
                    abs(remaining) <= maxStep -> target
                    remaining > 0f -> current + maxStep
                    else -> current - maxStep
                }
                overlay.alphaFraction = current
                if (progress < 1f || current != to) {
                    handler.postDelayed(this, FRAME_MS)
                } else {
                    fadeRunnable = null
                    onEnd?.invoke()
                }
            }
        }
        fadeRunnable = runnable
        handler.post(runnable)
    }

    private fun cancelFade() {
        fadeRunnable?.let { handler.removeCallbacks(it) }
        fadeRunnable = null
    }

    private companion object {
        const val FRAME_MS = 16L

        /**
         * How much faster than the nominal per-frame step a frame is allowed to catch up.
         * 2x keeps the fade within ~1% opacity per frame at 3s/60fps, indistinguishable
         * from a constant-rate fade while still absorbing a stalled frame.
         */
        const val MAX_STEP_FACTOR = 2f
    }
}
