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
import android.view.WindowManager
import com.wjf.fadebreak.core.DebugLog
import com.wjf.fadebreak.ui.overlay.OverlayView

class OverlayController(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var view: OverlayView? = null
    private var fadeRunnable: Runnable? = null
    private var dismissing = false

    var fadeOutMs: Long = 800L
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun show(fadeDurationMs: Long = 3000L, maxAlpha: Float = 0.85f) {
        if (view != null || !canDraw()) return

        val overlay = OverlayView(context).apply {
            onDismiss = { dismiss() }
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
        dismissing = false
        animate(overlay, 0f, maxAlpha.coerceIn(0f, 1f), fadeDurationMs)
    }

    fun dismiss() {
        val overlay = view ?: return
        if (dismissing) return
        DebugLog.d("overlay dismiss requested")
        dismissing = true
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
        val runnable = object : Runnable {
            override fun run() {
                val progress =
                    ((SystemClock.uptimeMillis() - start).toFloat() / durationMs)
                        .coerceIn(0f, 1f)
                overlay.alphaFraction = from + (to - from) * progress
                if (progress < 1f) {
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
    }
}
