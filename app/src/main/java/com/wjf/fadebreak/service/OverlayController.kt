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
import android.view.WindowManager
import com.wjf.fadebreak.core.DebugLog
import com.wjf.fadebreak.ui.overlay.OverlayView
import kotlin.math.abs

/**
 * Owns the full-screen break overlay: a single [TYPE_APPLICATION_OVERLAY] window that
 * fades the background in and out, and dismisses when tapped.
 *
 * While it is showing, the window is touchable, so the configured opacity is honoured
 * exactly (100% really is opaque). Tapping starts the dismissal: the fade brings the
 * opacity down to [trustCap] first, then the window is switched to touch-transparent and
 * faded to zero. That hand-over exists because of Android 12+ "untrusted touch"
 * protection: an overlay that lets touches pass through may not be more than
 * `maximumObscuringOpacityForTouch` (0.8 by default) opaque, otherwise touches to the app
 * underneath are blocked. Switching the flag on a fully opaque window would make it jump
 * 1.0 -> 0.8 (a flash), so the drop is done at the same rate as the rest of the fade,
 * which for 100% means gestures are handed over after the first fifth of the fade.
 */
class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: OverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var fadeRunnable: Runnable? = null
    private var dismissing = false

    var fadeOutMs: Long = 800L
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(fadeDurationMs: Long = 3000L, maxAlpha: Float = 0.85f) {
        if (view != null || !canDraw()) return

        val overlay = OverlayView(context).apply {
            onTouch = { event ->
                if (event.action == MotionEvent.ACTION_UP) dismiss()
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
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

        val added = runCatching { windowManager.addView(overlay, params) }
        if (added.isFailure) {
            DebugLog.e("overlay addView failed", added.exceptionOrNull())
            return
        }
        DebugLog.d("overlay added")

        view = overlay
        layoutParams = params
        dismissing = false
        animate(overlay, 0f, maxAlpha.coerceIn(0f, 1f), fadeDurationMs)
    }

    fun dismiss() {
        val overlay = view ?: return
        if (dismissing) return
        DebugLog.d("overlay dismiss requested")
        dismissing = true
        val shown = overlay.alphaFraction.coerceIn(0f, 1f)
        val cap = trustCap
        if (fadeOutMs <= 0L || shown <= cap) {
            handOverToApp(overlay, shown, fadeOutMs)
            return
        }
        // Keep the whole dismissal at one constant fade rate. The platform only lets
        // touches through at <= trustCap opacity, so bring the current opacity down to
        // that in the same proportion of time the rest of the fade takes:
        //   blend / fadeOutMs == (shown - cap) / shown
        // With 100% and a 3s fade that is 600ms, then the remaining 2.4s finishes 80%->0.
        val blend = (fadeOutMs * (shown - cap) / shown).toLong().coerceIn(1L, fadeOutMs)
        animate(overlay, shown, cap, blend) {
            handOverToApp(overlay, cap, fadeOutMs - blend)
        }
    }

    /**
     * Make the window touch-transparent so gestures reach the app below, then fade the
     * (still visible) overlay out. The drawn alpha is scaled back up by [trustCap] because
     * the window itself is now composited at that factor, keeping the brightness continuous.
     */
    private fun handOverToApp(overlay: OverlayView, composite: Float, remainingMs: Long) {
        val params = layoutParams
        if (params != null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            params.alpha = trustCap
            runCatching { windowManager.updateViewLayout(overlay, params) }
                .onFailure { DebugLog.e("overlay updateViewLayout failed", it) }
        }
        val drawn = (composite / trustCap).coerceIn(0f, 1f)
        animate(overlay, drawn, 0f, remainingMs) { finishDismiss() }
    }

    private fun finishDismiss() {
        fadeRunnable = null
        dismissing = false
        view?.let {
            runCatching { windowManager.removeView(it) }
            releaseBackground(it)
        }
        view = null
        layoutParams = null
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
        layoutParams = null
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

    /**
     * Lowest opacity the platform lets an overlay that passes touches through have.
     * Read from the system so a device that raised it (or a future default) is handled.
     */
    private val trustCap: Float
        get() = runCatching {
            Settings.Secure.getFloat(
                context.contentResolver,
                "maximum_obscuring_opacity_for_touch",
                DEFAULT_TRUST_CAP
            )
        }.getOrDefault(DEFAULT_TRUST_CAP).coerceIn(0.1f, 1f)

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
        // A stalled main thread (window relayout, GC) must not teleport the fade: cap how
        // far one frame may move, so the fade just takes a little longer instead of jumping.
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
        const val MAX_STEP_FACTOR = 2f
        const val DEFAULT_TRUST_CAP = 0.8f
    }
}
