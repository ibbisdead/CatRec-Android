package com.ibbie.catrec_screenrecorcer.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.setPadding
import com.ibbie.catrec_screenrecorcer.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import androidx.core.graphics.createBitmap

private sealed class BrushCommittedAction {
    data class Stroke(
        val path: Path,
        val paint: Paint,
    ) : BrushCommittedAction()

    data class RectOutline(
        val rect: RectF,
        val paint: Paint,
    ) : BrushCommittedAction()

    data class OvalOutline(
        val rect: RectF,
        val paint: Paint,
    ) : BrushCommittedAction()

    data class ArrowLine(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val paint: Paint,
    ) : BrushCommittedAction()

    data class BlurTiles(
        val rect: RectF,
        val cell: Int,
    ) : BrushCommittedAction()
}

internal enum class DrawTool { PEN, ERASER, SQUARE, CIRCLE, ARROW, BLUR }

@SuppressLint("ViewConstructor")
internal class BrushSurfaceView(
    context: Context,
) : View(context) {
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val actions = mutableListOf<BrushCommittedAction>()

    private var currentTool = DrawTool.PEN
    private var strokeColor = Color.RED
    private var strokeWidthPx = 12f

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

    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private val previewRect = RectF()
    private var shapeEndX = 0f
    private var shapeEndY = 0f
    private val shapePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

    fun setTool(tool: DrawTool) {
        currentTool = tool
    }

    fun setStrokeColor(color: Int) {
        strokeColor = color
        shapePaint.color = color
        invalidate()
    }

    fun setStrokeWidthPx(w: Float) {
        strokeWidthPx = w.coerceIn(2f, 80f)
        penPaint.strokeWidth = strokeWidthPx
        eraserPaint.strokeWidth = strokeWidthPx * 1.4f
        shapePaint.strokeWidth = strokeWidthPx
        invalidate()
    }

    fun getStrokeWidthPx(): Float = strokeWidthPx

    fun getStrokeColor(): Int = strokeColor

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
        redrawAll()
        invalidate()
    }

    private fun redrawAll() {
        val b = bitmap ?: return
        b.eraseColor(Color.TRANSPARENT)
        val c = bitmapCanvas ?: return
        for (a in actions) {
            when (a) {
                is BrushCommittedAction.Stroke -> c.drawPath(a.path, a.paint)
                is BrushCommittedAction.RectOutline -> c.drawRect(a.rect, a.paint)
                is BrushCommittedAction.OvalOutline -> c.drawOval(a.rect, a.paint)
                is BrushCommittedAction.ArrowLine -> drawArrowOnCanvas(c, a.x1, a.y1, a.x2, a.y2, a.paint)
                is BrushCommittedAction.BlurTiles -> drawBlurCheckerboard(c, a.rect, a.cell)
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
                val paint =
                    Paint().apply {
                        style = Paint.Style.FILL
                        isAntiAlias = false
                    }
                paint.color = if ((row + col) % 2 == 0) Color.BLACK else Color.WHITE
                c.drawRect(x, y, x2, y2, paint)
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
        val head = (strokeWidthPx * 3f).coerceIn(18f, 56f)
        val spread = 0.45
        val a1 = ang + Math.PI - spread
        val a2 = ang + Math.PI + spread
        c.drawLine(x2, y2, (x2 + head * cos(a1)).toFloat(), (y2 + head * sin(a1)).toFloat(), p)
        c.drawLine(x2, y2, (x2 + head * cos(a2)).toFloat(), (y2 + head * sin(a2)).toFloat(), p)
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        bitmap?.recycle()
        bitmap = createBitmap(w, h)
        bitmapCanvas = Canvas(bitmap!!)
        penPaint.strokeWidth = strokeWidthPx
        eraserPaint.strokeWidth = strokeWidthPx * 1.4f
        shapePaint.strokeWidth = strokeWidthPx
        redrawAll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        shapePaint.color = strokeColor
        when (currentTool) {
            DrawTool.PEN -> {
                penPaint.color = strokeColor
                canvas.drawPath(currentPath, penPaint)
            }
            DrawTool.ERASER -> canvas.drawPath(currentPath, eraserPaint)
            DrawTool.SQUARE -> canvas.drawRect(previewRect, shapePaint)
            DrawTool.CIRCLE -> canvas.drawOval(previewRect, shapePaint)
            DrawTool.ARROW -> drawArrowOnCanvas(canvas, shapeStartX, shapeStartY, shapeEndX, shapeEndY, shapePaint)
            DrawTool.BLUR -> {
                shapePaint.style = Paint.Style.STROKE
                canvas.drawRect(previewRect, shapePaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bc = bitmapCanvas ?: return false
        val x = event.x
        val y = event.y
        when (currentTool) {
            DrawTool.PEN, DrawTool.ERASER -> handleStroke(event, bc, x, y)
            DrawTool.SQUARE, DrawTool.CIRCLE, DrawTool.BLUR -> handleRectLike(event, bc, x, y)
            DrawTool.ARROW -> handleArrow(event, bc, x, y)
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
                    if (currentTool == DrawTool.ERASER) {
                        Paint(eraserPaint)
                    } else {
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = strokeWidthPx
                            color = strokeColor
                            strokeJoin = Paint.Join.ROUND
                            strokeCap = Paint.Cap.ROUND
                        }
                    }
                actions.add(BrushCommittedAction.Stroke(committed, p))
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
                    DrawTool.SQUARE -> {
                        val paint =
                            Paint(shapePaint).apply {
                                color = strokeColor
                                style = Paint.Style.STROKE
                            }
                        actions.add(BrushCommittedAction.RectOutline(RectF(previewRect), Paint(paint)))
                        bc.drawRect(previewRect, paint)
                    }
                    DrawTool.CIRCLE -> {
                        val paint =
                            Paint(shapePaint).apply {
                                color = strokeColor
                                style = Paint.Style.STROKE
                            }
                        actions.add(BrushCommittedAction.OvalOutline(RectF(previewRect), Paint(paint)))
                        bc.drawOval(previewRect, paint)
                    }
                    DrawTool.BLUR -> {
                        val cell = 10
                        actions.add(BrushCommittedAction.BlurTiles(RectF(previewRect), cell))
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
                    BrushCommittedAction.ArrowLine(shapeStartX, shapeStartY, shapeEndX, shapeEndY, Paint(paint)),
                )
                drawArrowOnCanvas(bc, shapeStartX, shapeStartY, shapeEndX, shapeEndY, paint)
                shapeEndX = shapeStartX
                shapeEndY = shapeStartY
                invalidate()
            }
        }
    }
}

/**
 * Full-screen brush UI: drawing surface + modern bottom toolbar.
 */
@SuppressLint("ClickableViewAccessibility")
internal class BrushOverlayLayout(
    context: Context,
    private val onClose: () -> Unit,
    private val onScreenshot: () -> Unit,
) : FrameLayout(context) {
    private val bottomChrome =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(context, R.drawable.bg_brush_toolbar)
            elevation = 12f * context.resources.displayMetrics.density
        }

    private val surface =
        BrushSurfaceView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

    /** Hides the bottom toolbar so screenshots are not obstructed; drawing surface stays visible. */
    fun setToolbarVisible(visible: Boolean) {
        bottomChrome.visibility = if (visible) VISIBLE else GONE
    }

    private val mainTools =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private val colorPanel =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = GONE
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }

    private var pendingColor = Color.RED
    private var pendingWidthPx = 12f
    private var colorPanelSnapshotColor = Color.RED
    private var colorPanelSnapshotWidth = 12f

    private val palette =
        intArrayOf(
            Color.RED,
            Color.WHITE,
            Color.BLACK,
            Color.YELLOW,
            Color.BLUE,
            0xFFFFC0CB.toInt(),
            0xFFAA00FF.toInt(),
            Color.GREEN,
        )

    private val colorChipViews = mutableListOf<View>()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        addView(surface)

        val scroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(mainTools, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            }
        val barParams =
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }

        val widthSeek =
            SeekBar(context).apply {
                max = 78
                progress = (pendingWidthPx - 2f).toInt().coerceIn(0, 78)
                layoutParams =
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(6), 0, dp(4))
                    }
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            pendingWidthPx = 2f + progress
                        }

                        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                    },
                )
            }

        fun selectTool(tool: DrawTool) {
            surface.setTool(tool)
            highlightTool(tool)
        }

        fun addToolIcon(
            drawableRes: Int,
            tool: DrawTool,
            contentDesc: String,
        ) {
            val iv =
                ImageView(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                            setMargins(dp(4), dp(10), dp(4), dp(10))
                        }
                    setImageResource(drawableRes)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(11))
                    background = AppCompatResources.getDrawable(context, R.drawable.bg_btn_overlay_normal)
                    tag = tool
                    this.contentDescription = contentDesc
                    setOnClickListener { selectTool(tool) }
                }
            mainTools.addView(iv)
        }

        addToolIcon(R.drawable.ic_brush_tool_pen, DrawTool.PEN, context.getString(R.string.brush_tool_pen))
        addToolIcon(R.drawable.ic_brush_tool_eraser, DrawTool.ERASER, context.getString(R.string.brush_tool_eraser))

        val undo =
            ImageView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                        setMargins(dp(4), dp(10), dp(4), dp(10))
                    }
                setImageResource(R.drawable.ic_brush_undo)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(11))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_btn_overlay_normal)
                contentDescription = context.getString(R.string.brush_tool_undo)
                setOnClickListener { surface.rewind() }
            }
        mainTools.addView(undo)

        addToolIcon(R.drawable.ic_brush_shape_square, DrawTool.SQUARE, context.getString(R.string.brush_tool_square))
        addToolIcon(R.drawable.ic_brush_shape_circle, DrawTool.CIRCLE, context.getString(R.string.brush_tool_circle))
        addToolIcon(R.drawable.ic_brush_shape_arrow, DrawTool.ARROW, context.getString(R.string.brush_tool_arrow))
        addToolIcon(R.drawable.ic_brush_pixelate, DrawTool.BLUR, context.getString(R.string.brush_tool_mosaic))

        val colorBtn =
            ImageView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                        setMargins(dp(4), dp(10), dp(4), dp(10))
                    }
                setImageResource(R.drawable.ic_brush_palette)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(11))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_btn_overlay_normal)
                contentDescription = context.getString(R.string.brush_tool_colors)
                setOnClickListener {
                    pendingColor = surface.getStrokeColor()
                    pendingWidthPx = surface.getStrokeWidthPx()
                    widthSeek.progress = (pendingWidthPx - 2f).toInt().coerceIn(0, 78)
                    colorPanelSnapshotColor = pendingColor
                    colorPanelSnapshotWidth = pendingWidthPx
                    refreshColorChipSelection()
                    colorPanel.visibility = VISIBLE
                    scroll.visibility = GONE
                }
            }
        mainTools.addView(colorBtn)

        val shot =
            ImageView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                        setMargins(dp(4), dp(10), dp(4), dp(10))
                    }
                setImageResource(R.drawable.ic_screenshot)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_btn_overlay_normal)
                contentDescription = context.getString(R.string.notif_action_screenshot)
                setOnClickListener { onScreenshot() }
            }
        mainTools.addView(shot)

        val closeBtn =
            ImageView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                        setMargins(dp(6), dp(10), dp(10), dp(10))
                    }
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(12))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_btn_overlay_stop)
                contentDescription = context.getString(R.string.action_close)
                setOnClickListener { onClose() }
            }
        mainTools.addView(closeBtn)

        val panelHeader =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
        val panelTitle =
            TextView(context).apply {
                text = context.getString(R.string.brush_panel_title)
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            }
        val backTv =
            TextView(context).apply {
                text = context.getString(R.string.brush_color_back)
                setTextColor(0xFFB0B0B0.toInt())
                textSize = 14f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    pendingColor = colorPanelSnapshotColor
                    pendingWidthPx = colorPanelSnapshotWidth
                    widthSeek.progress = (pendingWidthPx - 2f).toInt().coerceIn(0, 78)
                    refreshColorChipSelection()
                    colorPanel.visibility = GONE
                    scroll.visibility = VISIBLE
                }
            }
        panelHeader.addView(panelTitle)
        panelHeader.addView(backTv)
        colorPanel.addView(panelHeader)

        val widthLabel =
            TextView(context).apply {
                text = context.getString(R.string.brush_line_width)
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 12f
                layoutParams =
                    LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(4), 0, 0)
                    }
            }
        colorPanel.addView(widthLabel)
        colorPanel.addView(widthSeek)

        val chipScroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams =
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(10), 0, 0)
                    }
            }
        val chipRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        palette.forEach { c ->
            val chip =
                View(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                            setMargins(dp(6), dp(4), dp(6), dp(4))
                        }
                    setOnClickListener {
                        pendingColor = c
                        refreshColorChipSelection()
                    }
                }
            colorChipViews.add(chip)
            chipRow.addView(chip)
        }
        chipScroll.addView(chipRow)
        colorPanel.addView(chipScroll)

        val hint =
            TextView(context).apply {
                text = context.getString(R.string.brush_color_picker_hint)
                setTextColor(0xFF888888.toInt())
                textSize = 12f
                layoutParams =
                    LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(8), 0, dp(10))
                    }
            }
        colorPanel.addView(hint)

        val applyBtn =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
                background = AppCompatResources.getDrawable(context, R.drawable.bg_brush_apply)
                setPadding(dp(16), 0, dp(16), 0)
                setOnClickListener {
                    surface.setStrokeColor(pendingColor)
                    surface.setStrokeWidthPx(pendingWidthPx)
                    colorPanel.visibility = GONE
                    scroll.visibility = VISIBLE
                }
            }
        applyBtn.addView(
            ImageView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                        setMargins(0, 0, dp(8), 0)
                    }
                setImageResource(R.drawable.ic_brush_check_small)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
        )
        applyBtn.addView(
            TextView(context).apply {
                text = context.getString(R.string.brush_apply)
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        colorPanel.addView(applyBtn)

        bottomChrome.addView(scroll)
        bottomChrome.addView(colorPanel)
        addView(bottomChrome, barParams)

        surface.setStrokeColor(Color.RED)
        surface.setStrokeWidthPx(12f)
        highlightTool(DrawTool.PEN)
        refreshColorChipSelection()
    }

    private fun refreshColorChipSelection() {
        colorChipViews.forEachIndexed { index, v ->
            val c = palette.getOrNull(index) ?: return@forEachIndexed
            val selected = pendingColor == c
            v.background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(
                        dp(if (selected) 3 else 1),
                        if (selected) Color.WHITE else 0x66FFFFFF,
                    )
                }
        }
    }

    private fun highlightTool(active: DrawTool) {
        for (i in 0 until mainTools.childCount) {
            val v = mainTools.getChildAt(i)
            val t = v.tag as? DrawTool ?: continue
            v.background =
                AppCompatResources.getDrawable(
                    context,
                    if (t == active) R.drawable.bg_brush_tool_selected else R.drawable.bg_btn_overlay_normal,
                )
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
