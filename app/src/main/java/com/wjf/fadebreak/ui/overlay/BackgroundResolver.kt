package com.wjf.fadebreak.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.wjf.fadebreak.core.BreakSettings
import com.wjf.fadebreak.data.SettingsRepository

/**
 * Decodes the overlay background. A single image uses the user-chosen focus; a folder
 * rotates through its images with center crop and persists the next index.
 *
 * The decoded bitmap is deliberately **not** cached here: a full-screen bitmap is a
 * multi-megabyte native allocation and the overlay is only visible for a few seconds.
 * The caller ([com.wjf.fadebreak.service.OverlayController]) recycles it as soon as the
 * overlay is removed.
 */
class BackgroundResolver(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {

    suspend fun resolve(settings: BreakSettings): Bitmap? {
        if (!settings.bgImageEnabled) return null
        if (settings.bgImageUri.isNotBlank()) {
            return decode(settings.bgImageUri, settings.bgFocusX, settings.bgFocusY)
        }
        if (settings.bgFolderUri.isNotBlank()) {
            val files = listFolderImages(settings.bgFolderUri)
            if (files.isEmpty()) return null
            val index = settings.bgFolderIndex.mod(files.size)
            val bitmap = decode(files[index].toString(), 0.5f, 0.5f)
            // Rotate to the next image for the next reminder.
            runCatching {
                settingsRepository.set(settings.copy(bgFolderIndex = (index + 1) % files.size))
            }
            return bitmap
        }
        return null
    }

    private fun listFolderImages(treeUri: String): List<Uri> = runCatching {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        tree?.listFiles()
            ?.filter { it.isFile && it.type?.startsWith("image/") == true }
            ?.map { it.uri }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun decode(uriString: String, focusX: Float, focusY: Float): Bitmap? = try {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            null
        } else {
            val metrics = context.resources.displayMetrics
            val targetWidth = metrics.widthPixels
            val targetHeight = metrics.heightPixels
            // Keep the decoded source only ~1.5x the screen. The old 2x bound could keep
            // a screen-sized portrait photo at full resolution (up to ~28 MB for RGB_565).
            // The overlay is translucent and blended, so the slight softening from the
            // tighter bound is invisible, while the peak allocation drops by ~2-3x.
            var sample = 1
            while (width / sample > targetWidth * DECODE_OVERSAMPLE ||
                height / sample > targetHeight * DECODE_OVERSAMPLE
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                // Half the memory of ARGB_8888; the overlay is blended anyway so the
                // colour-band reduction is invisible.
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (decoded == null) {
                null
            } else {
                val cropped = centerCrop(decoded, targetWidth, targetHeight, focusX, focusY)
                if (cropped !== decoded) decoded.recycle()
                cropped
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Cover-crops [source] to the screen size around the focus point in a **single**
     * allocation: the source is drawn straight into the target bitmap with a filtered
     * [Canvas], instead of `createScaledBitmap` + `createBitmap` (which held up to three
     * full-size bitmaps at once).
     */
    private fun centerCrop(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        focusX: Float,
        focusY: Float
    ): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source

        val scale = maxOf(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height
        )
        // Visible part of the source (in source pixels); never larger than the source
        // because scale is the cover ratio.
        val visibleWidth = (targetWidth / scale).coerceAtMost(source.width.toFloat())
        val visibleHeight = (targetHeight / scale).coerceAtMost(source.height.toFloat())
        val left = (source.width - visibleWidth) * focusX.coerceIn(0f, 1f)
        val top = (source.height - visibleHeight) * focusY.coerceIn(0f, 1f)

        val output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        canvas.drawBitmap(
            source,
            Rect(
                left.toInt(),
                top.toInt(),
                (left + visibleWidth).toInt(),
                (top + visibleHeight).toInt()
            ),
            RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return output
    }

    private companion object {
        /** Max ratio of a decoded dimension to the screen dimension. */
        const val DECODE_OVERSAMPLE = 1.5f
    }
}
