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
    onCancel: () -> Unit
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
        }
    ) { padding ->
        Box(
            modifier = Modifier
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
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                bitmap?.let { bm ->
                    Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
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
                val holePath = Path().apply {
                    addRect(Rect(holeLeft, holeTop, holeRight, holeBottom))
                }
                
                clipPath(holePath, clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.6f))
                }
                
                // Draw border
                drawPath(holePath, color = Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

suspend fun saveCroppedImage(
    context: Context, 
    source: Bitmap, 
    offset: Offset, 
    scale: Float, 
    cropSizePx: Float
): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val bw = source.width
            val bh = source.height
            
            // Formula derived from mapping screen crop rect to bitmap coords
            val startX = ((-cropSizePx/2f - offset.x) / scale) + bw/2f
            val startY = ((-cropSizePx/2f - offset.y) / scale) + bh/2f
            
            val cropW = cropSizePx / scale
            val cropH = cropSizePx / scale
            
            // Safe bounds
            val finalX = max(0, startX.toInt())
            val finalY = max(0, startY.toInt())
            val finalW = min(bw - finalX, cropW.toInt())
            val finalH = min(bh - finalY, cropH.toInt())
            
            if (finalW <= 0 || finalH <= 0) return@withContext null
            
            val croppedBm = Bitmap.createBitmap(source, finalX, finalY, finalW, finalH)
            
            // Save to internal storage
            val file = File(context.filesDir, "custom_watermark.png")
            val out = FileOutputStream(file)
            croppedBm.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
            
            Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
