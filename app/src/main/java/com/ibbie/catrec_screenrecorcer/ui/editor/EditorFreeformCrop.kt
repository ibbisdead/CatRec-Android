package com.ibbie.catrec_screenrecorcer.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ibbie.catrec_screenrecorcer.R
import kotlin.math.min

/** Live crop rectangle + fitted image placement (screen px, same as [BoxWithConstraints] space). */
data class CropGeometry(
    val imageLeft: Float,
    val imageTop: Float,
    val imageDisplayWidth: Float,
    val imageDisplayHeight: Float,
    val cropLeft: Float,
    val cropTop: Float,
    val cropRight: Float,
    val cropBottom: Float,
)

private enum class CropDrag { None, Move, TL, TR, BL, BR }

private fun clampCropRect(
    rect: Rect,
    bounds: Rect,
    minSide: Float,
): Rect {
    var l = rect.left
    var t = rect.top
    var r = rect.right
    var b = rect.bottom
    if (r < l) {
        val tmp = l
        l = r
        r = tmp
    }
    if (b < t) {
        val tmp = t
        t = b
        b = tmp
    }
    val minW = minSide.coerceAtMost(bounds.width)
    val minH = minSide.coerceAtMost(bounds.height)
    l = l.coerceIn(bounds.left, bounds.right - minW)
    t = t.coerceIn(bounds.top, bounds.bottom - minH)
    r = r.coerceIn(l + minW, bounds.right)
    b = b.coerceIn(t + minH, bounds.bottom)
    return Rect(l, t, r, b)
}

private fun hitTestCrop(
    p: Offset,
    crop: Rect,
    handleRadius: Float,
): CropDrag {
    val corners =
        listOf(
            Triple(crop.left, crop.top, CropDrag.TL),
            Triple(crop.right, crop.top, CropDrag.TR),
            Triple(crop.left, crop.bottom, CropDrag.BL),
            Triple(crop.right, crop.bottom, CropDrag.BR),
        )
    for ((x, y, kind) in corners) {
        if ((Offset(x, y) - p).getDistance() <= handleRadius) return kind
    }
    if (p.x >= crop.left && p.x <= crop.right && p.y >= crop.top && p.y <= crop.bottom) {
        return CropDrag.Move
    }
    return CropDrag.None
}

/**
 * Image fitted to the box, dimmed outside a draggable rectangular crop with corner handles.
 */
@Composable
fun EditorFreeformCropContent(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    geometryOut: MutableState<CropGeometry?>,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    val density = LocalDensity.current
    val minCropPx = with(density) { 48.dp.toPx() }
    val handleRadius = with(density) { 32.dp.toPx() }
    val handleDraw = with(density) { 10.dp.toPx() }

    var cropRect by remember(bitmap.width, bitmap.height) { mutableStateOf(Rect.Zero) }
    var dragKind by remember { mutableStateOf(CropDrag.None) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (constraints.maxWidth == Constraints.Infinity ||
            constraints.maxHeight == Constraints.Infinity
        ) {
            Box(Modifier.fillMaxSize())
            return@BoxWithConstraints
        }
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        if (boxW <= 0f || boxH <= 0f || bw <= 0f || bh <= 0f) return@BoxWithConstraints

        val fit = min(boxW / bw, boxH / bh)
        val dispW = bw * fit
        val dispH = bh * fit
        val imgLeft = (boxW - dispW) / 2f
        val imgTop = (boxH - dispH) / 2f
        val imgRight = imgLeft + dispW
        val imgBottom = imgTop + dispH
        val imgBounds = Rect(imgLeft, imgTop, imgRight, imgBottom)

        LaunchedEffect(bitmap, boxW, boxH) {
            val margin = min(dispW, dispH) * 0.08f
            cropRect =
                clampCropRect(
                    Rect(imgLeft + margin, imgTop + margin, imgRight - margin, imgBottom - margin),
                    imgBounds,
                    minCropPx,
                )
        }

        SideEffect {
            geometryOut.value =
                CropGeometry(
                    imgLeft,
                    imgTop,
                    dispW,
                    dispH,
                    cropRect.left,
                    cropRect.top,
                    cropRect.right,
                    cropRect.bottom,
                )
        }

        val wDp = with(density) { dispW.toDp() }
        val hDp = with(density) { dispH.toDp() }

        Box(
            Modifier
                .requiredSizeIn(maxWidth = maxWidth, maxHeight = maxHeight)
                .fillMaxSize(),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(wDp, hDp),
                contentScale = ContentScale.Fit,
            )

            Canvas(Modifier.fillMaxSize()) {
                val hole = Path().apply { addRect(cropRect) }
                clipPath(hole, clipOp = ClipOp.Difference) {
                    drawRect(Color.Black.copy(alpha = 0.62f))
                }
                drawPath(
                    hole,
                    color = Color.White,
                    style = Stroke(width = 2.dp.toPx()),
                )
                val hs = handleDraw
                listOf(
                    Offset(cropRect.left, cropRect.top),
                    Offset(cropRect.right, cropRect.top),
                    Offset(cropRect.left, cropRect.bottom),
                    Offset(cropRect.right, cropRect.bottom),
                ).forEach { c ->
                    drawCircle(Color.White, hs, c)
                    drawCircle(Color.Black.copy(alpha = 0.35f), hs * 0.45f, c)
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(cropRect, imgBounds, minCropPx) {
                        detectDragGestures(
                            onDragStart = { start ->
                                dragKind = hitTestCrop(start, cropRect, handleRadius)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (dragKind == CropDrag.None) return@detectDragGestures
                                var r = cropRect
                                when (dragKind) {
                                    CropDrag.Move ->
                                        r = r.translate(Offset(dragAmount.x, dragAmount.y))
                                    CropDrag.TL ->
                                        r =
                                            Rect(
                                                r.left + dragAmount.x,
                                                r.top + dragAmount.y,
                                                r.right,
                                                r.bottom,
                                            )
                                    CropDrag.TR ->
                                        r =
                                            Rect(
                                                r.left,
                                                r.top + dragAmount.y,
                                                r.right + dragAmount.x,
                                                r.bottom,
                                            )
                                    CropDrag.BL ->
                                        r =
                                            Rect(
                                                r.left + dragAmount.x,
                                                r.top,
                                                r.right,
                                                r.bottom + dragAmount.y,
                                            )
                                    CropDrag.BR ->
                                        r =
                                            Rect(
                                                r.left,
                                                r.top,
                                                r.right + dragAmount.x,
                                                r.bottom + dragAmount.y,
                                            )
                                    CropDrag.None -> Unit
                                }
                                cropRect = clampCropRect(r, imgBounds, minCropPx)
                            },
                            onDragEnd = { dragKind = CropDrag.None },
                            onDragCancel = { dragKind = CropDrag.None },
                        )
                    },
            )

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, stringResource(R.string.action_close))
                }
                IconButton(onClick = onApply) {
                    Icon(Icons.Default.Check, stringResource(R.string.action_apply))
                }
            }
        }
    }
}
