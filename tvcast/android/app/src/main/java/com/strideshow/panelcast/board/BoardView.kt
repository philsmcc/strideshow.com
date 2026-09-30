package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The ink layer and the camera over the infinite board.
 *
 * Draws: background paper, committed ink, in-progress strokes, selection
 * marquee, and the fading laser trail. Sits ABOVE the source windows in the
 * view tree so ink can annotate a live camera or a shared screen.
 *
 * Touch policy (the part that makes a whiteboard feel right):
 *  - Every pointer gets its own in-progress stroke, so two people can write
 *    at the same time (this is table stakes on SMART/Newline/Promethean).
 *  - Two pointers that land close together and quickly are a pinch: pan and
 *    zoom the board, cancelling any strokes they had begun.
 *  - A large-contact touch (palm, fist) erases regardless of tool - erasing
 *    with the side of your hand is the single most-loved gesture on these
 *    panels.
 *  - Touches that a `SourceWindowLayer` claims never reach here; the layer
 *    below decides whether a touch is window chrome (drag/resize) or ink.
 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Tool { PEN, HIGHLIGHTER, ERASER, LASER, SHAPE, SELECT, TEXT, PAN }

    interface Listener {
        fun onHistoryChanged()
        /** Text tool tapped at board coords; host shows an input. */
        fun onTextRequested(bx: Float, by: Float)
        fun onViewChanged()
    }

    var board = Board()
        set(v) { field = v; invalidate() }
    var listener: Listener? = null

    /**
     * The paper must be *behind* the source windows and the ink *in front*
     * of them, so the activity uses two instances sharing one Board: a
     * paper-only layer at the bottom (not touchable) and an ink layer on
     * top. Both flags on gives the single-layer behaviour used for export.
     */
    var paintPaper = true
    var paintInk = true
    /** Ink layer calls this whenever the camera moves so the paper layer redraws. */
    var onInvalidatePaper: (() -> Unit)? = null

    /**
     * The floating-window layer beneath this view. Consulted on every new
     * pointer so window chrome and window pinches win over inking.
     */
    var windowLayer: SourceWindowLayer? = null
        set(v) {
            field = v
            v?.let { layer ->
                layer.onLongPressPickup = { pointerId, w ->
                    // A finger resting on a window becomes a drag: discard the
                    // dot it may have started and hand the pointer over.
                    val l = live.remove(pointerId)
                    windowPointers.add(pointerId)
                    layer.pickUp(pointerId, w, l?.lastX ?: 0f, l?.lastY ?: 0f)
                    invalidate()
                }
            }
        }
    /** Pointers currently owned by the window layer. */
    private val windowPointers = HashSet<Int>()
    private var recentDownId = -1
    private var recentDownAt = 0L

    var tool = Tool.PEN
        set(v) { field = v; selection = Selection.EMPTY; invalidate() }
    var inkColor = Color.WHITE
    /** Width in *screen* pixels at 1x; converted to board units per stroke. */
    var penWidthPx = 6f
    var highlighterWidthPx = 36f
    var eraserRadiusPx = 40f
    var shapeType = ShapeType.RECT
    var shapeFilled = false
    /** Ink-to-shape: after a stroke with the pen, snap it if it looks like a shape. */
    var autoShape = false
    /** Palm eraser threshold; set from density by the activity. */
    var palmThresholdPx = 60f
    /** When true, pan/zoom are disabled (SMART's "lock canvas"). */
    var lockCanvas = false

    var selection = Selection.EMPTY
        private set

    // --- camera: board -> screen is  sx = (bx - viewX) * scale --------------
    val page: Page get() = board.page
    private val fwd = Matrix()
    private val inv = Matrix()

    fun toBoardX(sx: Float) = sx / page.viewScale + page.viewX
    fun toBoardY(sy: Float) = sy / page.viewScale + page.viewY
    fun toScreenX(bx: Float) = (bx - page.viewX) * page.viewScale
    fun toScreenY(by: Float) = (by - page.viewY) * page.viewScale

    private fun syncMatrix() {
        fwd.reset()
        fwd.postTranslate(-page.viewX, -page.viewY)
        fwd.postScale(page.viewScale, page.viewScale)
        fwd.invert(inv)
    }

    // --- paints --------------------------------------------------------------
    private val paperPaint = Paint().apply { color = 0xFF0B0F17.toInt() }
    private val gridMinor = Paint().apply { color = 0x1AFFFFFF; strokeWidth = 1f; style = Paint.Style.STROKE; isAntiAlias = false }
    private val gridMajor = Paint().apply { color = 0x30FFFFFF; strokeWidth = 1.5f; style = Paint.Style.STROKE; isAntiAlias = false }
    private val axisPaint = Paint().apply { color = 0x88FFFFFF.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val inkPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val hlPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
        alpha = 110
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint = Paint().apply { isAntiAlias = true; typeface = android.graphics.Typeface.SANS_SERIF }
    private val eraserPreview = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xAAFFFFFF.toInt(); isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val marqueePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF3D8BFD.toInt(); isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val marqueeFill = Paint().apply { style = Paint.Style.FILL; color = 0x223D8BFD }
    private val laserGlow = Paint().apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val path = Path()
    private val tmpRect = RectF()

    // --- in-progress strokes, one per pointer ---------------------------------
    private class Live(
        val pointerId: Int,
        val kind: InkKind,
        val color: Int,
        val width: Float,
        val startedAt: Long,
        var downX: Float, var downY: Float,
    ) {
        val pts = ArrayList<Float>(256)
        var lastX = 0f; var lastY = 0f
        var isPalm = false
        /** For the shape tool: current drag endpoint. */
        var curX = 0f; var curY = 0f
        /** Objects erased so far in this pass, committed as one undo step. */
        val erasedStrokes = LinkedHashMap<Long, Stroke>()
        val erasedShapes = LinkedHashMap<Long, Shape>()
        val erasedTexts = LinkedHashMap<Long, TextItem>()
    }
    private val live = HashMap<Int, Live>()

    /** Laser: recent points with timestamps; drawn with a fade, never saved. */
    private class LaserPt(val x: Float, val y: Float, val t: Long)
    private val laser = ArrayList<LaserPt>()
    private val laserFadeMs = 900L

    // --- pinch state ---------------------------------------------------------
    private var pinching = false
    private var pinchId0 = -1; private var pinchId1 = -1
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var pinchStartMidBX = 0f; private var pinchStartMidBY = 0f
    private var lastMidX = 0f; private var lastMidY = 0f
    /** Two fingers count as a pinch only if they land within this window (ms) and distance (px). */
    private val pinchWindowMs = 250L
    private val pinchMaxDistPx get() = min(width, height) * 0.35f

    // --- selection drag ---------------------------------------------------------
    private var selDragging = false
    private var selDx = 0f; private var selDy = 0f
    private var selLastX = 0f; private var selLastY = 0f
    private var marquee: RectF? = null
    private var marqueePointer = -1

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    // =====================================================================
    // Drawing
    // =====================================================================

    override fun onDraw(canvas: Canvas) {
        syncMatrix()
        if (paintPaper) drawPaper(canvas)
        if (!paintInk) return

        canvas.save()
        canvas.concat(fwd)
        val scale = page.viewScale

        // Highlighter under everything else so it never obscures ink.
        for (s in page.strokes) if (s.kind == InkKind.HIGHLIGHTER) drawStroke(canvas, s.points, s.color, s.width, hlPaint)
        for (l in live.values) if (l.kind == InkKind.HIGHLIGHTER && !l.isPalm) drawLive(canvas, l, hlPaint)

        for (s in page.shapes) drawShape(canvas, s)
        for (s in page.strokes) if (s.kind == InkKind.PEN) drawStroke(canvas, s.points, s.color, s.width, inkPaint)
        for (t in page.texts) drawText(canvas, t)
        for (l in live.values) {
            if (l.isPalm) continue
            when (l.kind) {
                InkKind.PEN -> drawLive(canvas, l, inkPaint)
                else -> {}
            }
        }

        // Shape being dragged out. pts[0..1] hold the board-space start point.
        if (tool == Tool.SHAPE) for (l in live.values) if (!l.isPalm && l.pts.size >= 2) {
            drawShape(canvas, Shape(0, shapeType, inkColor, penWidthPx / scale, l.pts[0], l.pts[1], l.curX, l.curY, shapeFilled))
        }

        // Selection outline.
        if (!selection.isEmpty) page.boundsOf(selection)?.let { b ->
            tmpRect.set(b.left, b.top, b.right, b.bottom)
            marqueePaint.strokeWidth = 2f / scale
            canvas.drawRect(tmpRect, marqueePaint)
        }
        canvas.restore()

        // Screen-space overlays.
        marquee?.let { m -> canvas.drawRect(m, marqueeFill); canvas.drawRect(m, marqueePaint) }
        for (l in live.values) {
            if (l.isPalm) canvas.drawCircle(l.lastX, l.lastY, eraserRadiusPx * 1.6f, eraserPreview)
            else if (tool == Tool.ERASER) canvas.drawCircle(l.lastX, l.lastY, eraserRadiusPx, eraserPreview)
        }
        drawLaser(canvas)
    }

    /**
     * Background paper. Spacing is in board units, so the grid zooms with
     * the content; but when zoomed far out the minor grid would become a
     * solid wash, so minor lines fade and majors take over (like a CAD grid).
     */
    private fun drawPaper(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
        val bg = page.background
        if (bg == Background.PLAIN) return
        val scale = page.viewScale
        val base = 40f // board units between minor lines at 1x
        var step = base * scale
        // Keep visible spacing in a sane pixel range by doubling/halving.
        while (step < 18f) step *= 2f
        while (step > 90f) step /= 2f
        val majorEvery = 5
        val startBX = page.viewX; val startBY = page.viewY
        val stepB = step / scale
        val x0 = -((startBX % stepB + stepB) % stepB) * scale
        val y0 = -((startBY % stepB + stepB) % stepB) * scale
        val idx0X = Math.floorDiv((startBX / stepB).toLong(), 1L)
        val idx0Y = Math.floorDiv((startBY / stepB).toLong(), 1L)
        val fade = ((step - 18f) / 30f).coerceIn(0.15f, 1f)
        gridMinor.alpha = (0x1A * fade).toInt()

        when (bg) {
            Background.GRAPH, Background.CARTESIAN -> {
                var x = x0; var i = idx0X
                while (x <= width) {
                    c.drawLine(x, 0f, x, height.toFloat(), if (i % majorEvery == 0L) gridMajor else gridMinor); x += step; i++
                }
                var y = y0; i = idx0Y
                while (y <= height) {
                    c.drawLine(0f, y, width.toFloat(), y, if (i % majorEvery == 0L) gridMajor else gridMinor); y += step; i++
                }
                if (bg == Background.CARTESIAN) {
                    val ax = toScreenX(0f); val ay = toScreenY(0f)
                    if (ax in 0f..width.toFloat()) c.drawLine(ax, 0f, ax, height.toFloat(), axisPaint)
                    if (ay in 0f..height.toFloat()) c.drawLine(0f, ay, width.toFloat(), ay, axisPaint)
                }
            }
            Background.DOTS -> {
                val r = max(1.2f, step * 0.045f)
                fillPaint.color = 0x44FFFFFF
                var y = y0
                while (y <= height) { var x = x0; while (x <= width) { c.drawCircle(x, y, r, fillPaint); x += step }; y += step }
            }
            Background.LINES -> {
                var y = y0; var i = idx0Y
                while (y <= height) { c.drawLine(0f, y, width.toFloat(), y, if (i % majorEvery == 0L) gridMajor else gridMinor); y += step; i++ }
            }
            Background.MUSIC -> {
                // Groups of five lines with a gap; spacing from the grid step.
                val lineGap = step
                val groupH = lineGap * 8
                val gb = groupH / scale
                var gy = -((startBY % gb + gb) % gb) * scale
                while (gy <= height) {
                    for (k in 0 until 5) c.drawLine(0f, gy + k * lineGap, width.toFloat(), gy + k * lineGap, gridMajor)
                    gy += groupH
                }
            }
            Background.PLAIN -> {}
        }
    }

    private fun drawStroke(c: Canvas, pts: FloatArray, color: Int, width: Float, p: Paint) {
        if (pts.size < 2) return
        p.color = color
        if (p === hlPaint) p.alpha = 110
        p.strokeWidth = width
        if (pts.size == 2) { c.drawPoint(pts[0], pts[1], p); return }
        path.rewind()
        path.moveTo(pts[0], pts[1])
        // Quadratic midpoints give a smooth line without a real spline fit.
        var i = 2
        while (i + 3 < pts.size) {
            val mx = (pts[i] + pts[i + 2]) / 2f; val my = (pts[i + 1] + pts[i + 3]) / 2f
            path.quadTo(pts[i], pts[i + 1], mx, my)
            i += 2
        }
        path.lineTo(pts[pts.size - 2], pts[pts.size - 1])
        c.drawPath(path, p)
    }

    private fun drawLive(c: Canvas, l: Live, p: Paint) {
        if (l.pts.size < 2) return
        val arr = FloatArray(l.pts.size); for (i in arr.indices) arr[i] = l.pts[i]
        drawStroke(c, arr, l.color, l.width, p)
    }

    private fun drawShape(c: Canvas, s: Shape) {
        inkPaint.color = s.color; inkPaint.strokeWidth = s.width
        val l = min(s.x0, s.x1); val t = min(s.y0, s.y1); val r = max(s.x0, s.x1); val b = max(s.y0, s.y1)
        tmpRect.set(l, t, r, b)
        if (s.filled) { fillPaint.color = (s.color and 0x00FFFFFF) or 0x33000000 }
        when (s.type) {
            ShapeType.RECT -> { if (s.filled) c.drawRect(tmpRect, fillPaint); c.drawRect(tmpRect, inkPaint) }
            ShapeType.ELLIPSE -> { if (s.filled) c.drawOval(tmpRect, fillPaint); c.drawOval(tmpRect, inkPaint) }
            ShapeType.TRIANGLE -> {
                path.rewind(); path.moveTo((l + r) / 2f, t); path.lineTo(r, b); path.lineTo(l, b); path.close()
                if (s.filled) c.drawPath(path, fillPaint); c.drawPath(path, inkPaint)
            }
            ShapeType.LINE -> c.drawLine(s.x0, s.y0, s.x1, s.y1, inkPaint)
            ShapeType.ARROW -> {
                c.drawLine(s.x0, s.y0, s.x1, s.y1, inkPaint)
                val ang = atan2(s.y1 - s.y0, s.x1 - s.x0)
                val head = max(s.width * 4f, 18f / page.viewScale)
                val a1 = ang + Math.toRadians(150.0).toFloat(); val a2 = ang - Math.toRadians(150.0).toFloat()
                path.rewind()
                path.moveTo(s.x1, s.y1)
                path.lineTo(s.x1 + head * kotlin.math.cos(a1), s.y1 + head * kotlin.math.sin(a1))
                path.moveTo(s.x1, s.y1)
                path.lineTo(s.x1 + head * kotlin.math.cos(a2), s.y1 + head * kotlin.math.sin(a2))
                c.drawPath(path, inkPaint)
            }
        }
    }

    private fun drawText(c: Canvas, t: TextItem) {
        textPaint.color = t.color; textPaint.textSize = t.size
        var y = t.y
        for (line in t.text.split('\n')) { c.drawText(line, t.x, y, textPaint); y += t.size * 1.2f }
    }

    private fun drawLaser(c: Canvas) {
        if (laser.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        laser.removeAll { now - it.t > laserFadeMs }
        if (laser.size < 2) { if (laser.isNotEmpty()) postInvalidateOnAnimation(); return }
        // Draw as segments with per-segment alpha for a comet tail.
        for (i in 1 until laser.size) {
            val a = laser[i - 1]; val b = laser[i]
            val age = (now - b.t).toFloat() / laserFadeMs
            val alpha = ((1f - age) * 255).toInt().coerceIn(0, 255)
            laserGlow.color = Color.argb(alpha / 3, 255, 60, 60); laserGlow.strokeWidth = 22f
            c.drawLine(a.x, a.y, b.x, b.y, laserGlow)
            laserGlow.color = Color.argb(alpha, 255, 90, 90); laserGlow.strokeWidth = 6f
            c.drawLine(a.x, a.y, b.x, b.y, laserGlow)
        }
        val head = laser.last()
        fillPaint.color = Color.argb(255, 255, 120, 120)
        c.drawCircle(head.x, head.y, 8f, fillPaint)
        postInvalidateOnAnimation()
    }

    // =====================================================================
    // Touch
    // =====================================================================

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled) return false
        val layer = windowLayer
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                val id = ev.getPointerId(idx)
                if (layer != null) {
                    // A first finger on a window button: decline the whole
                    // gesture so the framework dispatches it to the button.
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN && layer.isOnButton(ev.getX(idx), ev.getY(idx))) return false
                    if (layer.claim(ev, idx, id, recentDownId, recentDownAt)) {
                        windowPointers.add(id)
                        // The layer may have taken over a pointer that was inking.
                        val stolen = layer.pinchStoleFirst
                        if (stolen >= 0) { live.remove(stolen); windowPointers.add(stolen); layer.pinchStoleFirst = -1 }
                        invalidate()
                        return true
                    }
                }
                recentDownId = id; recentDownAt = ev.eventTime
                onPointerDown(ev, idx, id)
            }
            MotionEvent.ACTION_MOVE -> {
                if (windowPointers.isNotEmpty() || layer?.hasPressCandidate() == true) layer?.onPointerMove(ev)
                if (pinching) { onPinchMove(ev) }
                for (i in 0 until ev.pointerCount) {
                    val id = ev.getPointerId(i)
                    val l = live[id] ?: continue
                    // Include historical samples: the panel reports at up to
                    // 240Hz and batches them, and using only the latest point
                    // turns fast handwriting into straight segments.
                    for (h in 0 until ev.historySize) onPointerMove(l, ev.getHistoricalX(i, h), ev.getHistoricalY(i, h), ev.getHistoricalTouchMajor(i, h))
                    onPointerMove(l, ev.getX(i), ev.getY(i), ev.getTouchMajor(i))
                }
                if (selDragging) onSelDrag(ev)
                if (marqueePointer >= 0) {
                    val i = ev.findPointerIndex(marqueePointer)
                    if (i >= 0) marquee?.let { m -> m.right = ev.getX(i); m.bottom = ev.getY(i) }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                val id = ev.getPointerId(idx)
                layer?.onPointerUp(ev, id)
                if (windowPointers.remove(id)) { invalidate(); return true }
                onPointerUp(ev, idx, id, cancelled = false)
            }
            MotionEvent.ACTION_CANCEL -> {
                live.clear(); pinching = false; selDragging = false; marquee = null; marqueePointer = -1
                windowPointers.clear(); layer?.cancelGestures()
                invalidate()
            }
        }
        return true
    }

    private fun onPointerDown(ev: MotionEvent, idx: Int, id: Int) {
        val x = ev.getX(idx); val y = ev.getY(idx)
        val now = ev.eventTime

        // Second finger shortly after the first, nearby: it is a pinch.
        if (!pinching && live.size == 1 && !lockCanvas) {
            val other = live.values.first()
            val dist = hypot(x - other.downX, y - other.downY)
            if (now - other.startedAt < pinchWindowMs && dist < pinchMaxDistPx && !other.isPalm) {
                beginPinch(ev, other.pointerId, id)
                return
            }
        }
        if (pinching) return // extra fingers during pinch are ignored

        // Pan tool: any single finger pans.
        if (tool == Tool.PAN && live.isEmpty() && !lockCanvas) {
            beginPinch(ev, id, -1)
            return
        }

        val palm = InkGeometry.isPalm(ev.getTouchMajor(idx), palmThresholdPx)
        val scale = page.viewScale
        val kind = when {
            palm -> InkKind.LASER // placeholder; palm strokes are erasers
            tool == Tool.HIGHLIGHTER -> InkKind.HIGHLIGHTER
            tool == Tool.LASER -> InkKind.LASER
            else -> InkKind.PEN
        }
        val width = when (kind) { InkKind.HIGHLIGHTER -> highlighterWidthPx; else -> penWidthPx } / scale
        val l = Live(id, kind, inkColor, width, now, x, y).also { it.lastX = x; it.lastY = y; it.isPalm = palm; it.curX = x; it.curY = y }
        live[id] = l

        when {
            palm || tool == Tool.ERASER -> eraseAt(l, x, y)
            tool == Tool.SELECT -> {
                val bx = toBoardX(x); val by = toBoardY(y)
                val b = if (selection.isEmpty) null else page.boundsOf(selection)
                if (b != null && b.contains(bx, by)) {
                    selDragging = true; selDx = 0f; selDy = 0f; selLastX = x; selLastY = y
                } else {
                    selection = Selection.EMPTY
                    marquee = RectF(x, y, x, y); marqueePointer = id
                }
            }
            tool == Tool.TEXT -> { /* handled on up as a tap */ }
            tool == Tool.LASER -> laser.add(LaserPt(x, y, SystemClock.uptimeMillis()))
            tool == Tool.SHAPE -> { l.pts.add(toBoardX(x)); l.pts.add(toBoardY(y)); l.curX = l.pts[0]; l.curY = l.pts[1] }
            else -> { l.pts.add(toBoardX(x)); l.pts.add(toBoardY(y)) }
        }
        invalidate()
    }

    private fun onPointerMove(l: Live, x: Float, y: Float, major: Float) {
        if (pinching) return
        l.lastX = x; l.lastY = y
        // A finger that widens into a palm mid-stroke becomes an eraser: undo
        // the accidental ink it just laid down.
        if (!l.isPalm && InkGeometry.isPalm(major, palmThresholdPx) && tool != Tool.ERASER) {
            l.isPalm = true; l.pts.clear()
        }
        when {
            l.isPalm || tool == Tool.ERASER -> eraseAt(l, x, y)
            tool == Tool.SHAPE -> { l.curX = toBoardX(x); l.curY = toBoardY(y) }
            tool == Tool.LASER -> laser.add(LaserPt(x, y, SystemClock.uptimeMillis()))
            tool == Tool.SELECT || tool == Tool.TEXT -> {}
            else -> {
                val bx = toBoardX(x); val by = toBoardY(y)
                // Skip sub-pixel jitter; it only adds points.
                val n = l.pts.size
                if (n >= 2) {
                    val dx = (bx - l.pts[n - 2]) * page.viewScale; val dy = (by - l.pts[n - 1]) * page.viewScale
                    if (dx * dx + dy * dy < 1.5f) return
                }
                l.pts.add(bx); l.pts.add(by)
            }
        }
    }

    private fun onPointerUp(ev: MotionEvent, idx: Int, id: Int, cancelled: Boolean) {
        if (pinching && (id == pinchId0 || id == pinchId1)) {
            pinching = false
            live.clear()
            // If one finger remains, it continues as a pan rather than
            // suddenly drawing a line from wherever it is.
            if (ev.pointerCount > 1 && tool != Tool.PAN) {
                val remain = (0 until ev.pointerCount).map { ev.getPointerId(it) }.first { it != id }
                beginPinch(ev, remain, -1)
            }
            listener?.onViewChanged()
            invalidate()
            return
        }
        val l = live.remove(id) ?: return
        if (marqueePointer == id) {
            marquee?.let { m ->
                val r = Rect(min(toBoardX(m.left), toBoardX(m.right)), min(toBoardY(m.top), toBoardY(m.bottom)),
                    max(toBoardX(m.left), toBoardX(m.right)), max(toBoardY(m.top), toBoardY(m.bottom)))
                selection = if (r.width > 4 && r.height > 4) page.select(r) else Selection.EMPTY
            }
            marquee = null; marqueePointer = -1
        }
        if (selDragging && live.isEmpty()) {
            selDragging = false
            if (abs(selDx) > 0.01f || abs(selDy) > 0.01f) {
                // The drag already moved objects live; record it as one edit
                // by reverting and re-applying through the history.
                val e = Page.Edit.Translate(selection.strokes, selection.shapes, selection.texts, selDx, selDy)
                e.revert(page); page.commit(e)
                listener?.onHistoryChanged()
            }
        }
        if (l.isPalm || tool == Tool.ERASER) {
            commitErase(l)
        } else when (tool) {
            Tool.TEXT -> if (!cancelled) listener?.onTextRequested(toBoardX(l.lastX), toBoardY(l.lastY))
            Tool.SHAPE -> {
                val w = penWidthPx / page.viewScale
                if (l.pts.size >= 2) {
                    val sx0 = l.pts[0]; val sy0 = l.pts[1]
                    // Ignore a tap that did not drag out a real size.
                    if (hypot(l.curX - sx0, l.curY - sy0) * page.viewScale > 6f) {
                        val s = Shape(Board.nextId(), shapeType, inkColor, w, sx0, sy0, l.curX, l.curY, shapeFilled)
                        page.commit(Page.Edit.AddShape(s)); listener?.onHistoryChanged()
                    }
                }
            }
            Tool.PEN, Tool.HIGHLIGHTER -> commitStroke(l)
            else -> {}
        }
        invalidate()
    }

    private fun commitStroke(l: Live) {
        if (l.pts.size < 2) return
        var arr = FloatArray(l.pts.size); for (i in arr.indices) arr[i] = l.pts[i]
        // A tap leaves a dot: duplicate the point so the renderer has a segment.
        if (arr.size == 2) arr = floatArrayOf(arr[0], arr[1], arr[0] + 0.01f, arr[1])
        arr = InkGeometry.simplify(arr, 0.6f / page.viewScale)

        if (autoShape && l.kind == InkKind.PEN) {
            val rec = InkGeometry.recognise(arr, 40f / page.viewScale)
            if (rec != null) {
                page.commit(Page.Edit.AddShape(rec.copy(id = Board.nextId(), color = l.color, width = l.width)))
                listener?.onHistoryChanged(); return
            }
        }
        page.commit(Page.Edit.AddStroke(Stroke(Board.nextId(), l.kind, l.color, l.width, arr)))
        listener?.onHistoryChanged()
    }

    private fun eraseAt(l: Live, sx: Float, sy: Float) {
        val r = eraserRadiusPx * (if (l.isPalm) 1.6f else 1f) / page.viewScale
        val hit = page.hitTest(toBoardX(sx), toBoardY(sy), r)
        if (hit.isEmpty) return
        // Remove immediately for feedback; the undo entry is built on lift.
        for (s in hit.strokes) l.erasedStrokes[s.id] = s
        for (s in hit.shapes) l.erasedShapes[s.id] = s
        for (t in hit.texts) l.erasedTexts[t.id] = t
        hit.apply(page)
    }

    private fun commitErase(l: Live) {
        if (l.erasedStrokes.isEmpty() && l.erasedShapes.isEmpty() && l.erasedTexts.isEmpty()) return
        val e = Page.Edit.Remove(l.erasedStrokes.values.toList(), l.erasedShapes.values.toList(), l.erasedTexts.values.toList())
        // Already applied live; put objects back then commit so history is consistent.
        e.revert(page); page.commit(e)
        listener?.onHistoryChanged()
    }

    // --- pinch / pan -------------------------------------------------------------

    private fun beginPinch(ev: MotionEvent, idA: Int, idB: Int) {
        // Cancel any strokes these fingers started.
        live.remove(idA); live.remove(idB)
        pinching = true; pinchId0 = idA; pinchId1 = idB
        val ia = ev.findPointerIndex(idA)
        val ib = if (idB >= 0) ev.findPointerIndex(idB) else -1
        if (ia < 0) { pinching = false; return }
        val ax = ev.getX(ia); val ay = ev.getY(ia)
        if (ib >= 0) {
            val bx = ev.getX(ib); val by = ev.getY(ib)
            pinchStartDist = max(hypot(bx - ax, by - ay), 1f)
            lastMidX = (ax + bx) / 2f; lastMidY = (ay + by) / 2f
        } else {
            pinchStartDist = 0f; lastMidX = ax; lastMidY = ay
        }
        pinchStartScale = page.viewScale
        pinchStartMidBX = toBoardX(lastMidX); pinchStartMidBY = toBoardY(lastMidY)
    }

    private fun onPinchMove(ev: MotionEvent) {
        val ia = ev.findPointerIndex(pinchId0)
        val ib = if (pinchId1 >= 0) ev.findPointerIndex(pinchId1) else -1
        if (ia < 0) return
        val ax = ev.getX(ia); val ay = ev.getY(ia)
        var midX = ax; var midY = ay
        if (ib >= 0) {
            val bx = ev.getX(ib); val by = ev.getY(ib)
            midX = (ax + bx) / 2f; midY = (ay + by) / 2f
            val dist = max(hypot(bx - ax, by - ay), 1f)
            val newScale = (pinchStartScale * dist / pinchStartDist).coerceIn(MIN_SCALE, MAX_SCALE)
            page.viewScale = newScale
        }
        // Keep the board point under the fingers' midpoint fixed under them.
        page.viewX = pinchStartMidBX - midX / page.viewScale
        page.viewY = pinchStartMidBY - midY / page.viewScale
        lastMidX = midX; lastMidY = midY
        onInvalidatePaper?.invoke()
        listener?.onViewChanged()
    }

    private fun onSelDrag(ev: MotionEvent) {
        val i = (0 until ev.pointerCount).firstOrNull { live.containsKey(ev.getPointerId(it)) } ?: return
        val x = ev.getX(i); val y = ev.getY(i)
        val dx = (x - selLastX) / page.viewScale; val dy = (y - selLastY) / page.viewScale
        selLastX = x; selLastY = y
        selDx += dx; selDy += dy
        Page.Edit.Translate(selection.strokes, selection.shapes, selection.texts, dx, dy).apply(page)
    }

    // =====================================================================
    // Public actions
    // =====================================================================

    fun undo() { if (page.undo()) { selection = Selection.EMPTY; listener?.onHistoryChanged(); invalidate() } }
    fun redo() { if (page.redo()) { selection = Selection.EMPTY; listener?.onHistoryChanged(); invalidate() } }

    fun clearPage() {
        if (page.isEmpty) return
        page.commit(Page.Edit.Clear(page.strokes.toList(), page.shapes.toList(), page.texts.toList()))
        selection = Selection.EMPTY
        listener?.onHistoryChanged(); invalidate()
    }

    fun deleteSelection() {
        if (selection.isEmpty) return
        val e = Page.Edit.Remove(
            page.strokes.filter { it.id in selection.strokes },
            page.shapes.filter { it.id in selection.shapes },
            page.texts.filter { it.id in selection.texts },
        )
        page.commit(e); selection = Selection.EMPTY
        listener?.onHistoryChanged(); invalidate()
    }

    fun addText(bx: Float, by: Float, text: String) {
        if (text.isBlank()) return
        val size = 28f / page.viewScale * 1.6f
        page.commit(Page.Edit.AddText(TextItem(Board.nextId(), text, inkColor, size, bx, by)))
        listener?.onHistoryChanged(); invalidate()
    }

    private fun viewChanged() { onInvalidatePaper?.invoke(); listener?.onViewChanged(); invalidate() }

    fun resetView() { page.viewX = 0f; page.viewY = 0f; page.viewScale = 1f; viewChanged() }

    /** Zoom about the screen centre by a factor (toolbar +/- buttons). */
    fun zoomBy(factor: Float) {
        val cx = width / 2f; val cy = height / 2f
        val bx = toBoardX(cx); val by = toBoardY(cy)
        page.viewScale = (page.viewScale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        page.viewX = bx - cx / page.viewScale; page.viewY = by - cy / page.viewScale
        viewChanged()
    }

    /** Fit all content on screen with a margin. */
    fun fitToContent() {
        val b = page.contentBounds() ?: return resetView()
        val margin = 0.08f
        val sw = width * (1 - 2 * margin); val sh = height * (1 - 2 * margin)
        val s = min(sw / max(b.width, 1f), sh / max(b.height, 1f)).coerceIn(MIN_SCALE, MAX_SCALE)
        page.viewScale = s
        page.viewX = b.left - (width / s - b.width) / 2f
        page.viewY = b.top - (height / s - b.height) / 2f
        viewChanged()
    }

    fun setBackground(bg: Background) { page.background = bg; onInvalidatePaper?.invoke(); invalidate() }

    /**
     * Render the current page to a bitmap at screen size (for export). The
     * paper is drawn from `paperLayer` so the export matches what is on
     * screen even though this instance may be ink-only. Live video windows
     * are not included: they are transient and the user owns that content.
     */
    fun snapshotWithPaper(paperLayer: BoardView?): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(max(width, 1), max(height, 1), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val savedLive = HashMap(live); live.clear()
        val pp = paintPaper; val pi = paintInk
        paintPaper = true; paintInk = true
        onDraw(c)
        paintPaper = pp; paintInk = pi
        live.putAll(savedLive)
        return bmp
    }

    companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 12f
    }
}
