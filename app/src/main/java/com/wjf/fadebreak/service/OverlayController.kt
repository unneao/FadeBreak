package com.wjf.fadebreak.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import com.wjf.fadebreak.core.DebugLog
import com.wjf.fadebreak.ui.overlay.OverlayView
import kotlin.math.abs

/**
 * Owns the full-screen break overlay: a single trusted window that fades the background in
 * and out, and dismisses when tapped.
 *
 * The window is a [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY], added from the
 * accessibility service's own context. The system treats that type as a *trusted* overlay,
 * so the Android 12+ "untrusted touch" rule (`maximum_obscuring_opacity_for_touch`, 0.8)
 * does not apply:
 *
 *  - the configured opacity is honoured exactly (100% really is opaque), and the platform
 *    does not rewrite the window alpha when touches start passing through;
 *  - the dismissal switches the window to touch-transparent in the very frame it is tapped,
 *    at any opacity, with no window-level alpha change and therefore nothing to flash. The
 *    fade then runs on the drawn content alone.
 *
 * The *service* context is required rather than `applicationContext`: this window type needs
 * a window token, and only `AccessibilityService` supplies one (it overrides
 * `getSystemService` / `createWindowContext` for that). An application-context window of
 * this type fails with `BadTokenException: token null is not valid`.
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

    fun show(fadeDurationMs: Long = 3000L, maxAlpha: Float = 0.85f) {
        if (view != null) return

        val overlay = OverlayView(context).apply {
            onTouch = { event ->
                if (event.action == MotionEvent.ACTION_UP) dismiss()
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
            // Nothing was shown, so nothing will ever be tapped to dismiss it. Post the
            // callback instead of invoking it inline: the trigger is still running inside
            // BreakStateMachine.evaluate(), which only enters BREAK_ACTIVE afterwards, and
            // a dismiss at this point would be ignored, leaving the machine waiting.
            handler.post { onDismissed?.invoke() }
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
        // A trusted window may pass touches through at any opacity, so hand over right away.
        passThroughToApp(overlay)
        animate(overlay, shown, 0f, fadeOutMs) { finishDismiss() }
    }

    /** Make the window touch-transparent so gestures reach the app below. */
    private fun passThroughToApp(overlay: OverlayView) {
        val params = layoutParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(overlay, params) }
            .onFailure { DebugLog.e("overlay updateViewLayout failed", it) }
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
    }
}
