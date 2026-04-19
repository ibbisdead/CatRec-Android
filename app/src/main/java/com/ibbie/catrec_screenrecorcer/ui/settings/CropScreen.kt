package com.ibbie.catrec_screenrecorcer.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ibbie.catrec_screenrecorcer.ui.editor.CropGeometry
import com.ibbie.catrec_screenrecorcer.ui.editor.EditorFreeformCropContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import androidx.core.net.toUri

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
    val cropGeometry: MutableState<CropGeometry?> = remember { mutableStateOf(null) }

    LaunchedEffect(imageUriString) {
        withContext(Dispatchers.IO) {
            try {
                val uri = imageUriString.toUri()
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
                FloatingActionButton(
                    onClick = onCancel,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
                FloatingActionButton(
                    onClick = {
                        val bm = bitmap ?: return@FloatingActionButton
                        val g = cropGeometry.value ?: return@FloatingActionButton
                        scope.launch {
                            val uri = saveCroppedWatermarkPng(context, bm, g)
                            if (uri != null) onCropDone(uri)
                        }
                    },
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color.Black),
        ) {
            when {
                isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                bitmap == null -> { }
                else -> {
                    val bm = bitmap!!
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            key(bm.width, bm.height, imageUriString) {
                                EditorFreeformCropContent(
                                    bitmap = bm,
                                    modifier = Modifier.fillMaxSize(),
                                    geometryOut = cropGeometry,
                                    onCancel = {},
                                    onApply = {},
                                    showBottomBarActions = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val CROP_MATH_TAG = "CatRecCrop"

/**
 * Maps the on-screen crop rect to a new bitmap (same math as the image editor crop apply).
 *
 * [cropLeft]–[cropBottom] share the same coordinate space as the fitted on-screen image
 * ([imageBoundsLeft], [imageBoundsTop], [imageDisplayWidth] × [imageDisplayHeight]), matching
 * [com.ibbie.catrec_screenrecorcer.ui.editor.EditorFreeformCropContent] geometry.
 *
 * Pixel selection: left/top use [floor] (inclusive), right/bottom use [ceil] so partial edge
 * pixels at fractional boundaries match the visible overlay.
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
        val sw = source.width
        val sh = source.height
        if (sw <= 0 || sh <= 0) return null
        val imgR = imageBoundsLeft + imageDisplayWidth
        val imgB = imageBoundsTop + imageDisplayHeight
        val cl = max(imageBoundsLeft, min(cropLeft, cropRight))
        val ct = max(imageBoundsTop, min(cropTop, cropBottom))
        val cr = min(imgR, max(cropLeft, cropRight))
        val cb = min(imgB, max(cropTop, cropBottom))
        if (cr - cl < 1f || cb - ct < 1f) return null
        val relL = (cl - imageBoundsLeft) / imageDisplayWidth * sw.toFloat()
        val relT = (ct - imageBoundsTop) / imageDisplayHeight * sh.toFloat()
        val relR = (cr - imageBoundsLeft) / imageDisplayWidth * sw.toFloat()
        val relB = (cb - imageBoundsTop) / imageDisplayHeight * sh.toFloat()
        val x0 = floor(relL).toInt().coerceIn(0, sw - 1)
        val y0 = floor(relT).toInt().coerceIn(0, sh - 1)
        val x1 = ceil(relR).toInt().coerceIn(x0 + 1, sw)
        val y1 = ceil(relB).toInt().coerceIn(y0 + 1, sh)
        if (Log.isLoggable(CROP_MATH_TAG, Log.VERBOSE)) {
            Log.v(
                CROP_MATH_TAG,
                "bitmap=${sw}x$sh display=${imageDisplayWidth}x$imageDisplayHeight " +
                    "cropPx x0=$x0 y0=$y0 x1=$x1 y1=$y1 relL=$relL relT=$relT relR=$relR relB=$relB",
            )
        }
        Bitmap.createBitmap(source, x0, y0, x1 - x0, y1 - y0)
    } catch (_: Exception) {
        null
    }
}

private suspend fun saveCroppedWatermarkPng(
    context: Context,
    source: Bitmap,
    g: CropGeometry,
): Uri? =
    withContext(Dispatchers.IO) {
        try {
            val cropped =
                createCroppedBitmapFromOverlay(
                    source,
                    g.imageLeft,
                    g.imageTop,
                    g.imageDisplayWidth,
                    g.imageDisplayHeight,
                    g.cropLeft,
                    g.cropTop,
                    g.cropRight,
                    g.cropBottom,
                ) ?: return@withContext null
            val file = File(context.filesDir, "custom_watermark.png")
            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (cropped !== source) cropped.recycle()
            Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
