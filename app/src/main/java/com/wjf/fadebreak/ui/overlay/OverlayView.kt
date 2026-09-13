package com.wjf.fadebreak.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
class OverlayView(context: Context) : View(context) {

    var bgColor: Int = 0xFF008040.toInt()

    var alphaFraction: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** Owned/cached by the resolver, so it is not recycled here (only dropped). */
    var background: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var onDismiss: (() -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val a = (alphaFraction.coerceIn(0f, 1f) * 255).toInt()
        val bmp = background
        if (bmp != null && !bmp.isRecycled) {
            val scale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = (width - dw) / 2f
            val top = (height - dh) / 2f
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(left, top, left + dw, top + dh)
            bgPaint.alpha = a
            canvas.drawBitmap(bmp, srcRect, dstRect, bgPaint)
        } else {
            bgPaint.color = bgColor
            bgPaint.alpha = a
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onDismiss?.invoke()
            return true
        }
        return true
    }
}
