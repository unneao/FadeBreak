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
 * Owns the full-screen break overlay: a single window that fades the background in and
 * out, and dismisses when tapped.
 *
 * Two window types are supported, and the difference matters:
 *
 *  - [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] (preferred). The app *is* an
 *    accessibility service, and the system treats this type as a **trusted overlay**: the
 *    Android 12+ "untrusted touch" rule (`maximum_obscuring_opacity_for_touch`, 0.8) does
 *    not apply. So the dismissal can switch to touch-transparent the very instant it is
 *    tapped, at any opacity, with no visual change, and the configured opacity is honoured
 *    exactly (100% really is opaque). The catch is the window token: only the
 *    `AccessibilityService` context carries one (`AccessibilityService` overrides
 *    `getSystemService`/`createWindowContext` for that); an `applicationContext` window of
 *    this type fails with `BadTokenException: token null is not valid`. That is why the
 *    service passes itself in.
 *  - [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] (fallback, used only if the
 *    platform refuses the trusted type). Here the platform clamps the window alpha to
 *    [trustCap] as soon as it becomes touch-transparent, so the hand-over has to wait for
 *    the fade to reach that opacity first ("[dismiss]"), and the drawn alpha is scaled up
 *    by `1/trustCap` to keep the brightness continuous across the switch.
 */
class OverlayController(
    private val context: Context,
    private val preferTrustedOverlay: Boolean = true
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: OverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var fadeRunnable: Runnable? = null
    private var dismissing = false
    private var usingTrustedOverlay = false

    var fadeOutMs: Long = 800L
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    /**
     * Whether an overlay can be shown at all. The trusted type needs no permission: the
     * accessibility service being connected is the prerequisite, and the controller is
     * created from `onServiceConnected`. [Settings.canDrawOverlays] only matters for the
     * fallback path.
     */
    fun canDraw(): Boolean = preferTrustedOverlay || Settings.canDrawOverlays(context)

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
            if (preferTrustedOverlay) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            },
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

        var added = runCatching { windowManager.addView(overlay, params) }
        usingTrustedOverlay = preferTrustedOverlay
        if (added.isFailure && preferTrustedOverlay) {
            // Some platform/ROM combination refused the trusted type; fall back to the
            // permission-based overlay, which still works but hands touches over late.
            DebugLog.w("trusted overlay refused, falling back: ${added.exceptionOrNull()}")
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            added = runCatching { windowManager.addView(overlay, params) }
            usingTrustedOverlay = false
        }
        if (added.isFailure) {
            DebugLog.e("overlay addView failed", added.exceptionOrNull())
            return
        }
        DebugLog.d("overlay added (trusted=$usingTrustedOverlay)")

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

        if (usingTrustedOverlay) {
            // A trusted window may pass touches through at any opacity, so hand over right
            // away. The fade then runs on the drawn content alone: no window alpha change,
            // nothing to flash, and gestures reach the app below immediately.
            passThroughToApp(overlay)
            animate(overlay, shown, 0f, fadeOutMs) { finishDismiss() }
            return
        }

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

    /** Make the window touch-transparent so gestures reach the app below. */
    private fun passThroughToApp(overlay: OverlayView) {
        val params = layoutParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(overlay, params) }
            .onFailure { DebugLog.e("overlay updateViewLayout failed", it) }
    }

    /**
     * Legacy hand-over for the untrusted window type: make the window touch-transparent
     * (the platform clamps it to [trustCap]), then fade the (still visible) overlay out.
     * The drawn alpha is scaled back up by [trustCap] because the window itself is now
     * composited at that factor, keeping the brightness continuous.
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
     * Highest opacity the platform lets an *untrusted* overlay that passes touches through
     * have. Read from the system so a device that raised it (or a future default) is handled.
     * Only the [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] fallback uses this.
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
