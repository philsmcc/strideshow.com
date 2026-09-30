package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.strideshow.panelcast.R

/**
 * The floating tool strip plus its pop-over panels.
 *
 * Modelled on the SMART/Promethean pattern: one horizontal strip that can be
 * dragged anywhere (a teacher on the left of a 4K panel should not have to
 * walk to the right to change colour), tools that show a pop-over when
 * tapped again, and everything sized for a finger at arm's length.
 *
 * It is a plain View subclass with programmatic children rather than XML
 * because nearly every element is dynamic (colour swatches, page thumbnails)
 * and because layout dimensions derive from screen pixels, not dp - a 4K
 * 65" panel and a 1080p 32" panel report wildly different densities for
 * roughly the same physical button size we want.
 */
class Toolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onTool(tool: BoardView.Tool)
        fun onColor(color: Int)
        fun onPenWidth(px: Float)
        fun onShape(type: ShapeType, filled: Boolean)
        fun onAutoShape(on: Boolean)
        fun onBackground(bg: Background)
        fun onUndo(); fun onRedo(); fun onClear()
        fun onAddSource()
        fun onPages()
        fun onTile()
        fun onFit()
        fun onLockCanvas(locked: Boolean)
        fun onSave()
        fun onSettings()
    }

    var listener: Listener? = null

    private val strip: LinearLayout
    private val popHost: FrameLayout
    private lateinit var stripContainer: View
    private var currentPop: View? = null
    private val buttons = HashMap<BoardView.Tool, ImageButton>()
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var colorDot: View
    private lateinit var btnAdd: ImageButton
    private lateinit var pageBadge: TextView

    // State mirrored for pop-overs
    var tool = BoardView.Tool.PEN; private set
    var color = COLORS[0]; private set
    var penWidth = 6f; private set
    var shape = ShapeType.RECT; private set
    var shapeFilled = false; private set
    var autoShape = false; private set
    var paperBg = Background.GRAPH; private set
    var locked = false; private set

    /** Base button size in px, derived from the screen's short side. */
    private val unit: Int
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        val dm = resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        // ~5% of the short side: 54px on 1080p, 108px on 4K - about 16-18 mm
        // on a 65" panel, right for a fingertip at arm's length.
        unit = (shortSide * 0.05f).toInt().coerceIn(48, 132)

        popHost = FrameLayout(context)
        addView(popHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pill(0xE6151B26.toInt(), 0x33FFFFFF)
            elevation = dp(12f)
            setPadding(unit / 4, unit / 8, unit / 4, unit / 8)
            clipToPadding = false
        }
        buildStrip()

        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            // The strip itself is draggable by its handle; the scroller only
            // matters if the panel is narrower than the strip.
            overScrollMode = OVER_SCROLL_NEVER
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = unit / 2
        addView(scroller, lp)
        stripContainer = scroller
        makeDraggable(scroller)

        setTool(BoardView.Tool.PEN)
    }

    // ---- building -----------------------------------------------------------------------

    private fun pill(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = unit.toFloat()
        setColor(fill)
        setStroke(dp(1f).toInt(), stroke)
    }

    private fun iconButton(res: Int, desc: String, onClick: (View) -> Unit): ImageButton = ImageButton(context).apply {
        setImageResource(res)
        contentDescription = desc
        setColorFilter(0xFFE8EDF5.toInt())
        background = pill(Color.TRANSPARENT, Color.TRANSPARENT)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        val pad = unit / 4
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(unit, unit).apply { marginEnd = unit / 10 }
        setOnClickListener(onClick)
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(dp(1f).toInt().coerceAtLeast(1), (unit * 0.6f).toInt()).apply {
            marginStart = unit / 8; marginEnd = unit / 5
        }
    }

    private fun buildStrip() {
        // Drag handle
        strip.addView(TextView(context).apply {
            text = "\u2807" // ⠇ braille dots as grip
            setTextColor(0x88FFFFFF.toInt()); textSize = unit * 0.28f / resources.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams((unit * 0.5f).toInt(), unit)
        })

        // The "+" is the headline action, so it gets the accent treatment.
        btnAdd = iconButton(R.drawable.ic_add, "Add a source") { listener?.onAddSource() }
        btnAdd.background = pill(0xFF3D8BFD.toInt(), Color.TRANSPARENT)
        strip.addView(btnAdd)
        strip.addView(divider())

        fun toolBtn(t: BoardView.Tool, res: Int, desc: String) {
            val b = iconButton(res, desc) {
                if (tool == t && hasPopover(t)) togglePop(t) else { setTool(t); if (hasPopover(t)) showPop(t) }
            }
            buttons[t] = b
            strip.addView(b)
        }
        toolBtn(BoardView.Tool.PEN, R.drawable.ic_pen, "Pen")
        toolBtn(BoardView.Tool.HIGHLIGHTER, R.drawable.ic_highlighter, "Highlighter")
        toolBtn(BoardView.Tool.ERASER, R.drawable.ic_eraser, "Eraser")
        toolBtn(BoardView.Tool.LASER, R.drawable.ic_laser, "Laser pointer")
        toolBtn(BoardView.Tool.SHAPE, R.drawable.ic_shapes, "Shapes")
        toolBtn(BoardView.Tool.TEXT, R.drawable.ic_text, "Text")
        toolBtn(BoardView.Tool.SELECT, R.drawable.ic_select, "Select")
        toolBtn(BoardView.Tool.PAN, R.drawable.ic_pan, "Pan")

        // Colour swatch: shows current colour, opens the palette.
        colorDot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(dp(2f).toInt(), Color.WHITE) }
            layoutParams = LinearLayout.LayoutParams((unit * 0.6f).toInt(), (unit * 0.6f).toInt()).apply {
                marginStart = unit / 6; marginEnd = unit / 4
            }
            setOnClickListener { togglePop(POP_COLOR) }
            contentDescription = "Colour"
        }
        strip.addView(colorDot)
        strip.addView(divider())

        btnUndo = iconButton(R.drawable.ic_undo, "Undo") { listener?.onUndo() }
        btnRedo = iconButton(R.drawable.ic_redo, "Redo") { listener?.onRedo() }
        strip.addView(btnUndo); strip.addView(btnRedo)
        strip.addView(iconButton(R.drawable.ic_clear, "Clear page") { listener?.onClear() })
        strip.addView(divider())

        // Pages badge shows "2/5"; tapping opens the page strip.
        pageBadge = TextView(context).apply {
            text = "1/1"
            setTextColor(0xFFE8EDF5.toInt()); textSize = unit * 0.24f / resources.displayMetrics.scaledDensity
            gravity = Gravity.CENTER
            background = pill(0x22FFFFFF, Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, unit).apply { marginEnd = unit / 10 }
            setPadding(unit / 3, 0, unit / 3, 0)
            setOnClickListener { listener?.onPages() }
            contentDescription = "Pages"
        }
        strip.addView(pageBadge)
        strip.addView(iconButton(R.drawable.ic_grid, "Background") { togglePop(POP_BG) })
        strip.addView(iconButton(R.drawable.ic_tile, "Tile windows") { listener?.onTile() })
        strip.addView(iconButton(R.drawable.ic_fit, "Fit to content") { listener?.onFit() })
        btnLock = iconButton(R.drawable.ic_lock, "Lock canvas") { setLocked(!locked); listener?.onLockCanvas(locked) }
        strip.addView(btnLock)
        strip.addView(iconButton(R.drawable.ic_save, "Save snapshot") { listener?.onSave() })
        strip.addView(iconButton(R.drawable.ic_settings, "Settings") { listener?.onSettings() })
    }

    // ---- state -------------------------------------------------------------------------------

    fun setTool(t: BoardView.Tool) {
        tool = t
        for ((k, b) in buttons) {
            b.background = pill(if (k == t) 0xFF3D8BFD.toInt() else Color.TRANSPARENT, Color.TRANSPARENT)
        }
        listener?.onTool(t)
        if (t != BoardView.Tool.SHAPE && t != BoardView.Tool.PEN && t != BoardView.Tool.HIGHLIGHTER) dismissPop()
    }

    fun setHistory(canUndo: Boolean, canRedo: Boolean) {
        btnUndo.alpha = if (canUndo) 1f else 0.35f
        btnRedo.alpha = if (canRedo) 1f else 0.35f
    }

    fun setPageBadge(index: Int, count: Int) { pageBadge.text = "${index + 1}/$count" }

    fun setLocked(l: Boolean) {
        locked = l
        btnLock.background = pill(if (l) 0xFFE0A03A.toInt() else Color.TRANSPARENT, Color.TRANSPARENT)
    }

    fun setColor(c: Int) {
        color = c
        (colorDot.background as GradientDrawable).setColor(c)
        listener?.onColor(c)
    }

    /** Animate the + button when a source arrives, so the eye goes to the new window. */
    fun pulseAdd() {
        btnAdd.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).withEndAction {
            btnAdd.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }.start()
    }

    // ---- pop-overs -------------------------------------------------------------------------------

    private fun hasPopover(t: BoardView.Tool) = t == BoardView.Tool.PEN || t == BoardView.Tool.HIGHLIGHTER || t == BoardView.Tool.SHAPE

    private var popKey: Any? = null

    private fun togglePop(key: Any) { if (popKey == key) dismissPop() else showPop(key) }

    fun dismissPop() {
        currentPop?.let { popHost.removeView(it) }
        currentPop = null; popKey = null
    }

    private fun showPop(key: Any) {
        dismissPop()
        val content: View = when (key) {
            POP_COLOR -> buildColorPop()
            POP_BG -> buildBackgroundPop()
            BoardView.Tool.PEN, BoardView.Tool.HIGHLIGHTER -> buildPenPop()
            BoardView.Tool.SHAPE -> buildShapePop()
            else -> return
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(0xF2151B26.toInt()); setStroke(dp(1f).toInt(), 0x33FFFFFF) }
            elevation = dp(14f)
            setPadding(unit / 3, unit / 3, unit / 3, unit / 3)
            addView(content)
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        // Sit just above the strip, following it if the user moved it.
        lp.bottomMargin = (height - stripContainer.y).toInt() + unit / 4
        lp.leftMargin = 0
        popHost.addView(card, lp)
        card.post {
            // Horizontally centre over the strip.
            val cx = stripContainer.x + stripContainer.width / 2f
            card.x = (cx - card.width / 2f).coerceIn(unit / 4f, (width - card.width - unit / 4f).coerceAtLeast(unit / 4f))
        }
        currentPop = card; popKey = key
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text; setTextColor(0xFF93A1B5.toInt()); textSize = unit * 0.22f / resources.displayMetrics.scaledDensity
        setPadding(0, 0, 0, unit / 6)
    }

    private fun buildColorPop(): View {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(label("Colour"))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (c in COLORS) {
            row.addView(View(context).apply {
                val sel = c == color
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    setStroke(dp(if (sel) 3f else 1.5f).toInt(), if (sel) Color.WHITE else 0x66FFFFFF)
                }
                val s = (unit * 0.7f).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = unit / 5 }
                setOnClickListener { setColor(c); dismissPop() }
            })
        }
        grid.addView(row)
        return grid
    }

    private fun buildPenPop(): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(if (tool == BoardView.Tool.HIGHLIGHTER) "Highlighter width" else "Pen width"))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val widths = if (tool == BoardView.Tool.HIGHLIGHTER) listOf(24f, 36f, 56f, 80f) else listOf(3f, 6f, 10f, 16f, 26f)
        for (w in widths) {
            row.addView(FrameLayout(context).apply {
                val s = (unit * 0.7f).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = unit / 5 }
                background = pill(if (kotlin.math.abs(w - penWidth) < 0.5f) 0x443D8BFD else Color.TRANSPARENT, Color.TRANSPARENT)
                addView(View(context).apply {
                    val d = (w * resources.displayMetrics.density * 0.55f).toInt().coerceIn(dp(4f).toInt(), s - unit / 6)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
                    layoutParams = LayoutParams(d, d, Gravity.CENTER)
                })
                setOnClickListener { penWidth = w; listener?.onPenWidth(w); showPop(tool) }
            })
        }
        col.addView(row)
        col.addView(label("Colour").apply { setPadding(0, unit / 4, 0, unit / 6) })
        col.addView(buildColorPop().let { (it as LinearLayout).getChildAt(1).also { r -> it.removeView(r) } })
        if (tool == BoardView.Tool.PEN) {
            col.addView(toggleRow("Snap sketches to shapes", autoShape) { on -> autoShape = on; listener?.onAutoShape(on) })
        }
        return col
    }

    private fun buildShapePop(): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("Shape"))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(
            ShapeType.LINE to "\u2014", ShapeType.ARROW to "\u2192", ShapeType.RECT to "\u25AD",
            ShapeType.ELLIPSE to "\u25EF", ShapeType.TRIANGLE to "\u25B3",
        )
        for ((t, glyph) in items) {
            row.addView(TextView(context).apply {
                text = glyph; gravity = Gravity.CENTER
                setTextColor(Color.WHITE); textSize = unit * 0.42f / resources.displayMetrics.scaledDensity
                val s = (unit * 0.8f).toInt()
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = unit / 6 }
                background = pill(if (t == shape) 0xFF3D8BFD.toInt() else 0x22FFFFFF, Color.TRANSPARENT)
                setOnClickListener { shape = t; listener?.onShape(t, shapeFilled); showPop(BoardView.Tool.SHAPE) }
            })
        }
        col.addView(row)
        col.addView(toggleRow("Filled", shapeFilled) { on -> shapeFilled = on; listener?.onShape(shape, on) })
        return col
    }

    private fun buildBackgroundPop(): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("Paper"))
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val all = Background.values().toList()
        for (chunk in all.chunked(3)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (bg in chunk) {
                row.addView(TextView(context).apply {
                    text = bg.label; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE); textSize = unit * 0.22f / resources.displayMetrics.scaledDensity
                    layoutParams = LinearLayout.LayoutParams((unit * 2.2f).toInt(), (unit * 0.75f).toInt()).apply { marginEnd = unit / 6; bottomMargin = unit / 6 }
                    background = pill(if (bg == paperBg) 0xFF3D8BFD.toInt() else 0x22FFFFFF, Color.TRANSPARENT)
                    setOnClickListener { paperBg = bg; listener?.onBackground(bg); dismissPop() }
                })
            }
            grid.addView(row)
        }
        col.addView(grid)
        return col
    }

    private fun toggleRow(text: String, on: Boolean, cb: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, unit / 4, 0, 0)
        }
        row.addView(TextView(context).apply {
            this.text = text; setTextColor(0xFFE8EDF5.toInt()); textSize = unit * 0.22f / resources.displayMetrics.scaledDensity
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val sw = android.widget.Switch(context).apply { isChecked = on; setOnCheckedChangeListener { _, v -> cb(v) } }
        row.addView(sw)
        return row
    }

    // ---- dragging the strip -------------------------------------------------------------------

    private fun makeDraggable(v: View) {
        var dx = 0f; var dy = 0f; var moved = false; var downX = 0f; var downY = 0f
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = view.x - ev.rawX; dy = view.y - ev.rawY; downX = ev.rawX; downY = ev.rawY; moved = false
                    // Only the grip (first child area) starts a drag; buttons get their clicks.
                    val local = ev.x
                    if (local > unit * 0.6f + unit / 4) return@setOnTouchListener false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && kotlin.math.hypot(ev.rawX - downX, ev.rawY - downY) > dp(6f)) moved = true
                    if (moved) {
                        // Once the user drags the strip, it stops being gravity-anchored.
                        (view.layoutParams as LayoutParams).let { lp -> if (lp.gravity != Gravity.NO_GRAVITY) { lp.gravity = Gravity.NO_GRAVITY; lp.bottomMargin = 0; view.layoutParams = lp } }
                        view.x = (ev.rawX + dx).coerceIn(0f, (width - view.width).toFloat().coerceAtLeast(0f))
                        view.y = (ev.rawY + dy).coerceIn(0f, (height - view.height).toFloat().coerceAtLeast(0f))
                        dismissPop()
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** Pop-overs must not swallow the whole screen: only the card itself is hit-testable. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A tap outside a pop-over closes it and is otherwise ignored here,
        // so the board beneath still gets its touch.
        if (currentPop != null && event.actionMasked == MotionEvent.ACTION_DOWN) dismissPop()
        return false
    }

    companion object {
        private const val POP_COLOR = "color"
        private const val POP_BG = "bg"
        /** Palette chosen for contrast on a dark board. */
        val COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), // white
            0xFFFFD84D.toInt(), // yellow
            0xFF4DA3FF.toInt(), // blue
            0xFF3DDC84.toInt(), // green
            0xFFFF6B6B.toInt(), // red
            0xFFFF9F43.toInt(), // orange
            0xFFC77DFF.toInt(), // violet
            0xFF5FE3E3.toInt(), // cyan
            0xFFFF7AB6.toInt(), // pink
        )
    }
}
