package com.ibbie.catrec_screenrecorcer.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class EditorDrawTool { PEN, ERASER, SQUARE, CIRCLE, ARROW, BLUR }

private sealed class EditorBrushAction {
    data class Stroke(
        val path: Path,
        val paint: Paint,
    ) : EditorBrushAction()

    data class RectOutline(
        val rect: RectF,
        val paint: Paint,
    ) : EditorBrushAction()

    data class OvalOutline(
        val rect: RectF,
        val paint: Paint,
    ) : EditorBrushAction()

    data class ArrowLine(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val paint: Paint,
    ) : EditorBrushAction()

    data class BlurTiles(
        val rect: RectF,
        val cell: Int,
    ) : EditorBrushAction()
}

/**
 * Brush / shapes on top of a photo in **bitmap pixel space** (same behavior as overlay brush, mapped from view touches).
 */
@SuppressLint("ViewConstructor")
class ImageEditorAnnotationView(
    context: Context,
) : View(context) {
    private var imageBitmap: Bitmap? = null
    private var annotationBitmap: Bitmap? = null
    private var annotationCanvas: Canvas? = null
    private val actions = mutableListOf<EditorBrushAction>()

    private var currentTool = EditorDrawTool.PEN
    private var strokeColor = Color.RED
    private var strokeWidthImagePx = 8f

    private val destRect = RectF()
    private val imageToViewMatrix = Matrix()

    /** Used for base + annotation bitmaps scaled into [destRect] (fit-center); filtering avoids crunchy scaling after rotate. */
    private val filterPaint =
        Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

    private val currentPath = Path()
    private var strokeHadSegment = false
    private val penPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    private val eraserPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
    private val shapePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private val previewRect = RectF()
    private var shapeEndX = 0f
    private var shapeEndY = 0f

    fun setBaseBitmap(bitmap: Bitmap) {
        if (imageBitmap === bitmap) return
        imageBitmap?.recycle()
        annotationBitmap?.recycle()
        imageBitmap = bitmap
        annotationBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        annotationCanvas = Canvas(annotationBitmap!!)
        actions.clear()
        currentPath.reset()
        strokeHadSegment = false
        previewRect.setEmpty()
        requestLayout()
        // Bitmap aspect can change (e.g. 90°/270° rotate) without view size changing — onSizeChanged
        // would not run; recompute fit-center destRect so drawBitmap is not stretched into stale bounds.
        if (width > 0 && height > 0) {
            updateDestRect(width, height)
        }
        syncStrokeWidths()
        invalidate()
    }

    fun getBaseBitmap(): Bitmap? = imageBitmap

    fun setTool(tool: EditorDrawTool) {
        currentTool = tool
        invalidate()
    }

    fun setStrokeColor(color: Int) {
        strokeColor = color
        shapePaint.color = color
        invalidate()
    }

    /** Line width in **dp** (scaled into bitmap pixels when laid out). */
    fun setStrokeWidthDp(dp: Float) {
        strokeWidthDisplayDp = dp
        syncStrokeWidths()
        invalidate()
    }

    private var strokeWidthDisplayDp = 4f

    private fun syncStrokeWidths() {
        val ib = imageBitmap ?: return
        val scaleImgPerPx = if (destRect.width() > 1f) ib.width / destRect.width() else 1f
        strokeWidthImagePx = (strokeWidthDisplayDp * resources.displayMetrics.density * scaleImgPerPx).coerceIn(2f, 120f)
        penPaint.strokeWidth = strokeWidthImagePx
        eraserPaint.strokeWidth = strokeWidthImagePx * 1.4f
        shapePaint.strokeWidth = strokeWidthImagePx
    }

    fun rewind() {
        if (actions.isEmpty()) return
        actions.removeAt(actions.lastIndex)
        currentPath.reset()
        strokeHadSegment = false
        previewRect.setEmpty()
        shapeStartX = 0f
        shapeStartY = 0f
        shapeEndX = 0f
        shapeEndY = 0f
        redrawAnnotations()
        invalidate()
    }

    fun clearAnnotations() {
        actions.clear()
        currentPath.reset()
        redrawAnnotations()
        invalidate()
    }

    private fun redrawAnnotations() {
        val b = annotationBitmap ?: return
        b.eraseColor(Color.TRANSPARENT)
        val c = annotationCanvas ?: return
        for (a in actions) {
            when (a) {
                is EditorBrushAction.Stroke -> c.drawPath(a.path, a.paint)
                is EditorBrushAction.RectOutline -> c.drawRect(a.rect, a.paint)
                is EditorBrushAction.OvalOutline -> c.drawOval(a.rect, a.paint)
                is EditorBrushAction.ArrowLine -> drawArrowOnCanvas(c, a.x1, a.y1, a.x2, a.y2, a.paint)
                is EditorBrushAction.BlurTiles -> drawBlurCheckerboard(c, a.rect, a.cell)
            }
        }
    }

    private fun drawBlurCheckerboard(
        c: Canvas,
        rect: RectF,
        cell: Int,
    ) {
        var y = rect.top
        var row = 0
        while (y < rect.bottom) {
            var x = rect.left
            var col = 0
            val y2 = min(y + cell, rect.bottom)
            while (x < rect.right) {
                val x2 = min(x + cell, rect.right)
                val p =
                    Paint().apply {
                        style = Paint.Style.FILL
                        isAntiAlias = false
                    }
                p.color = if ((row + col) % 2 == 0) Color.BLACK else Color.WHITE
                c.drawRect(x, y, x2, y2, p)
                x += cell
                col++
            }
            y += cell
            row++
        }
    }

    private fun drawArrowOnCanvas(
        c: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        p: Paint,
    ) {
        c.drawLine(x1, y1, x2, y2, p)
        val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val head = (strokeWidthImagePx * 3f).coerceIn(12f, 80f)
        val spread = 0.45
        val a1 = ang + Math.PI - spread
        val a2 = ang + Math.PI + spread
        c.drawLine(x2, y2, (x2 + head * cos(a1)).toFloat(), (y2 + head * sin(a1)).toFloat(), p)
        c.drawLine(x2, y2, (x2 + head * cos(a2)).toFloat(), (y2 + head * sin(a2)).toFloat(), p)
    }

    /** Fit-center: same scale for width and height so aspect ratio is preserved (including after 90°/270° bitmap swaps). */
    private fun updateDestRect(
        w: Int,
        h: Int,
    ) {
        val ib = imageBitmap ?: return
        val iw = ib.width.toFloat()
        val ih = ib.height.toFloat()
        if (iw <= 0f || ih <= 0f || w <= 0 || h <= 0) return
        val s = min(w / iw, h / ih)
        val dw = iw * s
        val dh = ih * s
        destRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        imageToViewMatrix.setRectToRect(
            RectF(0f, 0f, iw, ih),
            destRect,
            Matrix.ScaleToFit.CENTER,
        )
        syncStrokeWidths()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDestRect(w, h)
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        if (w > 0 && h > 0) {
            updateDestRect(w, h)
        }
    }

    private fun viewToImage(
        vx: Float,
        vy: Float,
    ): Pair<Float, Float> {
        val ib = imageBitmap ?: return vx to vy
        val iw = ib.width.toFloat()
        val ih = ib.height.toFloat()
        val ix = ((vx - destRect.left) / destRect.width()) * iw
        val iy = ((vy - destRect.top) / destRect.height()) * ih
        return ix.coerceIn(0f, iw - 1f) to iy.coerceIn(0f, ih - 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ib = imageBitmap ?: return
        val ab = annotationBitmap ?: return
        canvas.drawBitmap(ib, null, destRect, filterPaint)
        canvas.drawBitmap(ab, null, destRect, filterPaint)

        canvas.save()
        canvas.concat(imageToViewMatrix)
        shapePaint.color = strokeColor
        when (currentTool) {
            EditorDrawTool.PEN -> {
                penPaint.color = strokeColor
                canvas.drawPath(currentPath, penPaint)
            }
            EditorDrawTool.ERASER -> {
                val preview =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = penPaint.strokeWidth
                        color = Color.argb(140, 255, 100, 100)
                        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
                    }
                canvas.drawPath(currentPath, preview)
            }
            EditorDrawTool.SQUARE -> canvas.drawRect(previewRect, shapePaint)
            EditorDrawTool.CIRCLE -> canvas.drawOval(previewRect, shapePaint)
            EditorDrawTool.ARROW -> drawArrowOnCanvas(canvas, shapeStartX, shapeStartY, shapeEndX, shapeEndY, shapePaint)
            EditorDrawTool.BLUR -> {
                shapePaint.style = Paint.Style.STROKE
                canvas.drawRect(previewRect, shapePaint)
            }
        }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val ac = annotationCanvas ?: return false
        val (ix, iy) = viewToImage(event.x, event.y)
        when (currentTool) {
            EditorDrawTool.PEN, EditorDrawTool.ERASER -> handleStroke(event, ac, ix, iy)
            EditorDrawTool.SQUARE, EditorDrawTool.CIRCLE, EditorDrawTool.BLUR -> handleRectLike(event, ac, ix, iy)
            EditorDrawTool.ARROW -> handleArrow(event, ac, ix, iy)
        }
        return true
    }

    private fun handleStroke(
        event: MotionEvent,
        bc: Canvas,
        x: Float,
        y: Float,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPath.reset()
                strokeHadSegment = false
                currentPath.moveTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                strokeHadSegment = true
                currentPath.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (strokeHadSegment) currentPath.lineTo(x, y)
                if (!strokeHadSegment) {
                    currentPath.reset()
                    invalidate()
                    return
                }
                val committed = Path(currentPath)
                val p =
                    if (currentTool == EditorDrawTool.ERASER) {
                        Paint(eraserPaint)
                    } else {
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = strokeWidthImagePx
                            color = strokeColor
                            strokeJoin = Paint.Join.ROUND
                            strokeCap = Paint.Cap.ROUND
                        }
                    }
                actions.add(EditorBrushAction.Stroke(committed, p))
                bc.drawPath(committed, p)
                currentPath.reset()
                invalidate()
            }
        }
    }

    private fun handleRectLike(
        event: MotionEvent,
        bc: Canvas,
        x: Float,
        y: Float,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shapeStartX = x
                shapeStartY = y
                previewRect.set(x, y, x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                previewRect.set(
                    min(shapeStartX, x),
                    min(shapeStartY, y),
                    max(shapeStartX, x),
                    max(shapeStartY, y),
                )
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                previewRect.set(
                    min(shapeStartX, x),
                    min(shapeStartY, y),
                    max(shapeStartX, x),
                    max(shapeStartY, y),
                )
                if (previewRect.width() < 4f && previewRect.height() < 4f) {
                    invalidate()
                    return
                }
                when (currentTool) {
                    EditorDrawTool.SQUARE -> {
                        val paint =
                            Paint(shapePaint).apply {
                                color = strokeColor
                                style = Paint.Style.STROKE
                            }
                        actions.add(EditorBrushAction.RectOutline(RectF(previewRect), Paint(paint)))
                        bc.drawRect(previewRect, paint)
                    }
                    EditorDrawTool.CIRCLE -> {
                        val paint =
                            Paint(shapePaint).apply {
                                color = strokeColor
                                style = Paint.Style.STROKE
                            }
                        actions.add(EditorBrushAction.OvalOutline(RectF(previewRect), Paint(paint)))
                        bc.drawOval(previewRect, paint)
                    }
                    EditorDrawTool.BLUR -> {
                        val cell = (strokeWidthImagePx * 1.2f).toInt().coerceIn(6, 24)
                        actions.add(EditorBrushAction.BlurTiles(RectF(previewRect), cell))
                        drawBlurCheckerboard(bc, previewRect, cell)
                    }
                    else -> {}
                }
                previewRect.setEmpty()
                invalidate()
            }
        }
    }

    private fun handleArrow(
        event: MotionEvent,
        bc: Canvas,
        x: Float,
        y: Float,
    ) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                shapeStartX = x
                shapeStartY = y
                shapeEndX = x
                shapeEndY = y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                shapeEndX = x
                shapeEndY = y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                shapeEndX = x
                shapeEndY = y
                val paint =
                    Paint(shapePaint).apply {
                        color = strokeColor
                        style = Paint.Style.STROKE
                    }
                actions.add(
                    EditorBrushAction.ArrowLine(shapeStartX, shapeStartY, shapeEndX, shapeEndY, Paint(paint)),
                )
                drawArrowOnCanvas(bc, shapeStartX, shapeStartY, shapeEndX, shapeEndY, paint)
                shapeEndX = shapeStartX
                shapeEndY = shapeStartY
                invalidate()
            }
        }
    }

    /** Flatten base + ink into one ARGB bitmap (caller may recycle). */
    fun mergeToBitmap(): Bitmap? {
        val ib = imageBitmap ?: return null
        val ab = annotationBitmap ?: return null
        val out = ib.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        c.drawBitmap(ab, 0f, 0f, null)
        return out
    }
}
