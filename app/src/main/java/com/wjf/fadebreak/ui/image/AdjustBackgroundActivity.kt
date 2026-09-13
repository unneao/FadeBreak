package com.wjf.fadebreak.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.wjf.fadebreak.R
import com.wjf.fadebreak.data.SettingsBridge
import com.wjf.fadebreak.ui.theme.FadeBreakTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the user pick which part of a large background image is shown. */
class AdjustBackgroundActivity : ComponentActivity() {

    private lateinit var repository: SettingsBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsBridge(applicationContext)
        val uri = intent.getStringExtra(EXTRA_URI)
        if (uri.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            FadeBreakTheme {
                // Seed the crop from the saved focus so re-adjusting keeps the position.
                val initialFocus by produceState<Pair<Float, Float>?>(initialValue = null) {
                    val saved = repository.current()
                    value = saved.bgFocusX to saved.bgFocusY
                }
                initialFocus?.let { (focusX, focusY) ->
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        AdjustBackgroundScreen(
                            uri = uri,
                            initialFocusX = focusX,
                            initialFocusY = focusY,
                            onDone = { fx, fy ->
                                lifecycleScope.launch {
                                    val current = repository.current()
                                    repository.set(
                                        current.copy(
                                            bgImageUri = uri,
                                            bgFolderUri = "",
                                            bgImageEnabled = true,
                                            bgFocusX = fx,
                                            bgFocusY = fy
                                        )
                                    )
                                }
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URI = "bg_image_uri"
    }
}

@Composable
private fun AdjustBackgroundScreen(
    uri: String,
    initialFocusX: Float,
    initialFocusY: Float,
    onDone: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val frameW = constraints.maxWidth
        val frameH = constraints.maxHeight

        val scaled by produceState<ImageBitmap?>(null, uri, frameW, frameH) {
            value = withContext(Dispatchers.IO) {
                decodeScaled(context, uri, frameW, frameH)?.asImageBitmap()
            }
        }

        var offsetX by remember { mutableFloatStateOf(-1f) }
        var offsetY by remember { mutableFloatStateOf(-1f) }

        val maxX = ((scaled?.width ?: frameW) - frameW).coerceAtLeast(0)
        val maxY = ((scaled?.height ?: frameH) - frameH).coerceAtLeast(0)

        LaunchedEffect(scaled) {
            if (scaled != null && offsetX < 0f) {
                offsetX = maxX * initialFocusX.coerceIn(0f, 1f)
                offsetY = maxY * initialFocusY.coerceIn(0f, 1f)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(scaled) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offsetX = (offsetX - drag.x).coerceIn(0f, maxX.toFloat())
                        offsetY = (offsetY - drag.y).coerceIn(0f, maxY.toFloat())
                    }
                }
        ) {
            val img = scaled ?: return@Canvas
            val sx = offsetX.toInt().coerceIn(0, maxX)
            val sy = offsetY.toInt().coerceIn(0, maxY)
            val w = size.width.toInt().coerceAtMost(img.width - sx)
            val h = size.height.toInt().coerceAtMost(img.height - sy)
            if (w > 0 && h > 0) {
                drawImage(
                    image = img,
                    srcOffset = IntOffset(sx, sy),
                    srcSize = IntSize(w, h),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(w, h)
                )
            }
        }

        Text(
            text = stringResource(R.string.bg_adjust_hint),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(24.dp)
        )

        Button(
            onClick = {
                val fx = if (maxX > 0) offsetX / maxX else 0.5f
                val fy = if (maxY > 0) offsetY / maxY else 0.5f
                onDone(fx.coerceIn(0f, 1f), fy.coerceIn(0f, 1f))
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text(stringResource(R.string.bg_adjust_done))
        }
    }
}

private fun decodeScaled(context: Context, uriString: String, targetW: Int, targetH: Int): Bitmap? {
    return try {
        val uri = Uri.parse(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        var sample = 1
        while (width / sample > targetW * 2 || height / sample > targetH * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val scale = maxOf(
            targetW.toFloat() / decoded.width,
            targetH.toFloat() / decoded.height
        )
        val scaledW = (decoded.width * scale).toInt().coerceAtLeast(targetW)
        val scaledH = (decoded.height * scale).toInt().coerceAtLeast(targetH)
        val scaled = if (scaledW == decoded.width && scaledH == decoded.height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, scaledW, scaledH, true)
        }
        if (scaled !== decoded) decoded.recycle()
        scaled
    } catch (e: Exception) {
        null
    }
}
