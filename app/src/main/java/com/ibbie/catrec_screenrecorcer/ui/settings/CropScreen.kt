package com.ibbie.catrec_screenrecorcer.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

@Composable
fun CropScreen(
    imageUriString: String,
    onCropDone: (Uri) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Transformation State
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Crop area size (square)
    val cropSize = 200.dp
    val cropSizePx = with(LocalDensity.current) { cropSize.toPx() }

    // Load Bitmap
    LaunchedEffect(imageUriString) {
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(imageUriString)
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isLoading = false
        }
    }

    Scaffold(
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FloatingActionButton(onClick = onCancel, containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Icon(Icons.Default.Close, "Cancel")
                }
                FloatingActionButton(onClick = {
                    scope.launch {
                        bitmap?.let { bm ->
                            val croppedUri = saveCroppedImage(context, bm, offset, scale, cropSizePx)
                            if (croppedUri != null) {
                                onCropDone(croppedUri)
                            }
                        }
                    }
                }) {
                    Icon(Icons.Default.Check, "Save")
                }
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale *= zoom
                            scale = max(0.5f, min(5f, scale))
                            offset += pan
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                bitmap?.let { bm ->
                    Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y,
                                ),
                    )
                }
            }

            // Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val holeLeft = (canvasWidth - cropSizePx) / 2
                val holeTop = (canvasHeight - cropSizePx) / 2
                val holeRight = holeLeft + cropSizePx
                val holeBottom = holeTop + cropSizePx

                // Draw dimmed background with hole
                val holePath =
                    Path().apply {
                        addRect(Rect(holeLeft, holeTop, holeRight, holeBottom))
                    }

                clipPath(holePath, clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.6f))
                }

                // Draw border
                drawPath(
                    holePath,
                    color = Color.White,
                    style =
                        androidx.compose.ui.graphics.drawscope
                            .Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Maps the on-screen crop square to a new bitmap (same math as [saveCroppedImage]).
 *
 * Freeform crop: [cropLeft]–[cropBottom] are in the same coordinate space as the on-screen
 * image bounds ([imageBoundsLeft], [imageBoundsTop], [imageDisplayWidth] × [imageDisplayHeight]).
 */
fun createCroppedBitmapFromOverlay(
    source: Bitmap,
    imageBoundsLeft: Float,
    imageBoundsTop: Float,
    imageDisplayWidth: Float,
    imageDisplayHeight: Float,
    cropLeft: Float,
    cropTop: Float,
    cropRight: Float,
    cropBottom: Float,
): Bitmap? {
    return try {
        if (imageDisplayWidth <= 0f || imageDisplayHeight <= 0f) return null
        val imgR = imageBoundsLeft + imageDisplayWidth
        val imgB = imageBoundsTop + imageDisplayHeight
        val cl = max(imageBoundsLeft, min(cropLeft, cropRight))
        val ct = max(imageBoundsTop, min(cropTop, cropBottom))
        val cr = min(imgR, max(cropLeft, cropRight))
        val cb = min(imgB, max(cropTop, cropBottom))
        if (cr - cl < 1f || cb - ct < 1f) return null
        val relL = (cl - imageBoundsLeft) / imageDisplayWidth * source.width
        val relT = (ct - imageBoundsTop) / imageDisplayHeight * source.height
        val relR = (cr - imageBoundsLeft) / imageDisplayWidth * source.width
        val relB = (cb - imageBoundsTop) / imageDisplayHeight * source.height
        val x0 = relL.toInt().coerceIn(0, source.width - 1)
        val y0 = relT.toInt().coerceIn(0, source.height - 1)
        val x1 = relR.toInt().coerceIn(x0 + 1, source.width)
        val y1 = relB.toInt().coerceIn(y0 + 1, source.height)
        Bitmap.createBitmap(source, x0, y0, x1 - x0, y1 - y0)
    } catch (_: Exception) {
        null
    }
}

fun createCroppedBitmap(
    source: Bitmap,
    offset: Offset,
    scale: Float,
    cropSizePx: Float,
): Bitmap? {
    return try {
        val bw = source.width
        val bh = source.height
        val startX = ((-cropSizePx / 2f - offset.x) / scale) + bw / 2f
        val startY = ((-cropSizePx / 2f - offset.y) / scale) + bh / 2f
        val cropW = cropSizePx / scale
        val cropH = cropSizePx / scale
        val finalX = max(0, startX.toInt())
        val finalY = max(0, startY.toInt())
        val finalW = min(bw - finalX, cropW.toInt())
        val finalH = min(bh - finalY, cropH.toInt())
        if (finalW <= 0 || finalH <= 0) return null
        Bitmap.createBitmap(source, finalX, finalY, finalW, finalH)
    } catch (_: Exception) {
        null
    }
}

suspend fun saveCroppedImage(
    context: Context,
    source: Bitmap,
    offset: Offset,
    scale: Float,
    cropSizePx: Float,
): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val croppedBm = createCroppedBitmap(source, offset, scale, cropSizePx) ?: return@withContext null
            val file = File(context.filesDir, "custom_watermark.png")
            val out = FileOutputStream(file)
            croppedBm.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
            if (croppedBm !== source) croppedBm.recycle()
            Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
