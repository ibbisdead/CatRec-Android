package com.ibbie.catrec_screenrecorcer.ui.editor

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.ibbie.catrec_screenrecorcer.R
import com.ibbie.catrec_screenrecorcer.ui.settings.createCroppedBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

private enum class EditorPanel { Adjust, Crop, Annotate }

private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
    val w = src.width
    val h = src.height
    val m = Matrix().apply { postRotate(degrees, w / 2f, h / 2f) }
    val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
    m.mapRect(bounds)
    val nw = bounds.width().toInt().coerceAtLeast(1)
    val nh = bounds.height().toInt().coerceAtLeast(1)
    val out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.translate(-bounds.left, -bounds.top)
    c.concat(m)
    c.drawBitmap(src, 0f, 0f, null)
    return out
}

private suspend fun saveMergedToGallery(context: android.content.Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val name = "CatRec_edit_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/CatRec/Screenshots",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            resolver.openOutputStream(uri)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
            } ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditorScreen(
    encodedImageUri: String,
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(EditorPanel.Adjust) }
    var saving by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val cropSize = 220.dp
    val cropSizePx = with(LocalDensity.current) { cropSize.toPx() }

    var canvasRef by remember { mutableStateOf<ImageEditorAnnotationView?>(null) }

    LaunchedEffect(encodedImageUri) {
        loading = true
        loadError = false
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(Uri.decode(encodedImageUri))
                val stream: InputStream? = context.contentResolver.openInputStream(uri)
                val decoded = BitmapFactory.decodeStream(stream)
                stream?.close()
                withContext(Dispatchers.Main) {
                    bitmap = decoded
                    if (decoded == null) loadError = true
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { loadError = true }
            }
        }
        loading = false
    }

    fun rotate(degrees: Float) {
        val b = bitmap ?: return
        val nb = rotateBitmap(b, degrees)
        b.recycle()
        bitmap = nb
        canvasRef?.setBaseBitmap(nb)
    }

    fun applyCrop() {
        val b = bitmap ?: return
        scope.launch {
            val cropped = withContext(Dispatchers.Default) {
                createCroppedBitmap(b, offset, scale, cropSizePx)
            }
            if (cropped != null) {
                b.recycle()
                bitmap = cropped
                scale = 1f
                offset = Offset.Zero
                canvasRef?.setBaseBitmap(cropped)
                panel = EditorPanel.Adjust
            }
        }
    }

    fun saveImage() {
        val b = bitmap ?: return
        scope.launch {
            saving = true
            val merged = withContext(Dispatchers.Default) {
                canvasRef?.mergeToBitmap() ?: b.copy(Bitmap.Config.ARGB_8888, true)
            }
            val ok = saveMergedToGallery(context, merged)
            if (merged !== b) merged.recycle()
            saving = false
            if (ok) {
                Toast.makeText(context, context.getString(R.string.image_editor_saved), Toast.LENGTH_SHORT).show()
                navController.popBackStack()
            } else {
                Toast.makeText(context, context.getString(R.string.image_editor_save_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.image_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { saveImage() },
                        enabled = bitmap != null && !saving,
                    ) {
                        if (saving) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loadError -> Text(
                    stringResource(R.string.image_editor_load_failed),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                bitmap != null -> {
                    val bm = bitmap!!
                    Box(Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                ImageEditorAnnotationView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    setBaseBitmap(bm)
                                }.also { canvasRef = it }
                            },
                            update = { v ->
                                if (v.getBaseBitmap() !== bm) {
                                    v.setBaseBitmap(bm)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (panel == EditorPanel.Crop) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            scale *= zoom
                                            scale = max(0.5f, min(5f, scale))
                                            offset += pan
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = bm.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    },
                                )
                                Canvas(Modifier.fillMaxSize()) {
                                    val cw = size.width
                                    val ch = size.height
                                    val holeLeft = (cw - cropSizePx) / 2
                                    val holeTop = (ch - cropSizePx) / 2
                                    val holeRight = holeLeft + cropSizePx
                                    val holeBottom = holeTop + cropSizePx
                                    val holePath = Path().apply {
                                        addRect(Rect(holeLeft, holeTop, holeRight, holeBottom))
                                    }
                                    clipPath(holePath, clipOp = ClipOp.Difference) {
                                        drawRect(Color.Black.copy(alpha = 0.6f))
                                    }
                                    drawPath(
                                        holePath,
                                        color = Color.White,
                                        style = Stroke(width = 2.dp.toPx()),
                                    )
                                }
                                Row(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    IconButton(onClick = { panel = EditorPanel.Adjust }) {
                                        Icon(Icons.Default.Close, stringResource(R.string.action_close))
                                    }
                                    IconButton(onClick = { applyCrop() }) {
                                        Icon(Icons.Default.Check, stringResource(R.string.action_apply))
                                    }
                                }
                            }
                        }

                        Column(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                                .padding(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = panel == EditorPanel.Adjust,
                                    onClick = { panel = EditorPanel.Adjust },
                                    label = { Text(stringResource(R.string.image_editor_panel_adjust)) },
                                )
                                FilterChip(
                                    selected = panel == EditorPanel.Crop,
                                    onClick = { panel = EditorPanel.Crop },
                                    label = { Text(stringResource(R.string.image_editor_panel_crop)) },
                                )
                                FilterChip(
                                    selected = panel == EditorPanel.Annotate,
                                    onClick = { panel = EditorPanel.Annotate },
                                    label = { Text(stringResource(R.string.image_editor_panel_draw)) },
                                )
                            }
                            if (panel == EditorPanel.Adjust) {
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(onClick = { rotate(-90f) }) {
                                        Icon(Icons.AutoMirrored.Filled.RotateLeft, stringResource(R.string.image_editor_rotate_ccw))
                                    }
                                    IconButton(onClick = { rotate(90f) }) {
                                        Icon(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.image_editor_rotate_cw))
                                    }
                                }
                            }
                        }

                        if (panel == EditorPanel.Annotate) {
                            EditorBrushToolbar(
                                modifier = Modifier.align(Alignment.BottomCenter),
                                onTool = { canvasRef?.setTool(it) },
                                onUndo = { canvasRef?.rewind() },
                                onWidthDp = { canvasRef?.setStrokeWidthDp(it) },
                                onColor = { canvasRef?.setStrokeColor(it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorBrushToolbar(
    modifier: Modifier = Modifier,
    onTool: (EditorDrawTool) -> Unit,
    onUndo: () -> Unit,
    onWidthDp: (Float) -> Unit,
    onColor: (Int) -> Unit,
) {
    var widthDp by remember { mutableFloatStateOf(6f) }
    val palette = remember {
        intArrayOf(
            AndroidColor.RED, AndroidColor.WHITE, AndroidColor.BLACK, AndroidColor.YELLOW,
            AndroidColor.BLUE, 0xFFFFC0CB.toInt(), 0xFFAA00FF.toInt(), AndroidColor.GREEN,
        )
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        tonalElevation = 6.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onTool(EditorDrawTool.PEN) }) {
                    Icon(painterResource(R.drawable.ic_brush_tool_pen), contentDescription = stringResource(R.string.brush_tool_pen))
                }
                IconButton(onClick = { onTool(EditorDrawTool.ERASER) }) {
                    Icon(painterResource(R.drawable.ic_brush_tool_eraser), contentDescription = stringResource(R.string.brush_tool_eraser))
                }
                IconButton(onClick = onUndo) {
                    Icon(painterResource(R.drawable.ic_brush_undo), contentDescription = stringResource(R.string.brush_tool_undo))
                }
                IconButton(onClick = { onTool(EditorDrawTool.SQUARE) }) {
                    Icon(painterResource(R.drawable.ic_brush_shape_square), contentDescription = stringResource(R.string.brush_tool_square))
                }
                IconButton(onClick = { onTool(EditorDrawTool.CIRCLE) }) {
                    Icon(painterResource(R.drawable.ic_brush_shape_circle), contentDescription = stringResource(R.string.brush_tool_circle))
                }
                IconButton(onClick = { onTool(EditorDrawTool.ARROW) }) {
                    Icon(painterResource(R.drawable.ic_brush_shape_arrow), contentDescription = stringResource(R.string.brush_tool_arrow))
                }
                IconButton(onClick = { onTool(EditorDrawTool.BLUR) }) {
                    Icon(painterResource(R.drawable.ic_brush_pixelate), contentDescription = stringResource(R.string.brush_tool_mosaic))
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.brush_line_width), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = widthDp,
                    onValueChange = {
                        widthDp = it
                        onWidthDp(it)
                    },
                    valueRange = 2f..24f,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                palette.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(androidx.compose.ui.graphics.Color(c), CircleShape)
                            .clickable { onColor(c) },
                    )
                }
            }
        }
    }
}
