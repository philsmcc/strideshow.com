package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.strideshow.panelcast.R

/**
 * Page navigator: a strip of thumbnails along the top of the board.
 *
 * Thumbnails are drawn live from the model (cheap: strokes only, no
 * background) so they are always current without keeping bitmaps around.
 */
class PageStrip(context: Context) : FrameLayout(context) {

    interface Listener {
        fun onSelectPage(index: Int)
        fun onAddPage()
        fun onDeletePage(index: Int)
    }

    var listener: Listener? = null
    private val row: LinearLayout
    private val dm = resources.displayMetrics
    private val shortSide = minOf(dm.widthPixels, dm.heightPixels)
    private val thumbW = (shortSide * 0.18f).toInt()
    private val thumbH = (thumbW * 9 / 16f).toInt()
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

    init {
        setBackgroundColor(0xE60B0F17.toInt())
        elevation = dp(16f)
        isClickable = true
        val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = (shortSide * 0.02f).toInt()
            setPadding(p, p, p, p)
        }
        scroller.addView(row)
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    fun render(board: Board) {
        row.removeAllViews()
        board.pages.forEachIndexed { i, page ->
            val selected = i == board.current
            val cell = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(10f); setColor(0xFF151B26.toInt())
                    setStroke(dp(if (selected) 3f else 1f).toInt(), if (selected) 0xFF3D8BFD.toInt() else 0x33FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams(thumbW, thumbH).apply { marginEnd = (shortSide * 0.015f).toInt() }
                setOnClickListener { listener?.onSelectPage(i) }
                setOnLongClickListener { listener?.onDeletePage(i); true }
            }
            cell.addView(Thumb(context, page), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cell.addView(TextView(context).apply {
                text = (i + 1).toString(); setTextColor(Color.WHITE); textSize = 12f
                background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(0x88000000.toInt()) }
                setPadding(dp(8f).toInt(), dp(2f).toInt(), dp(8f).toInt(), dp(2f).toInt())
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(6f).toInt(), 0, 0, dp(6f).toInt())
            })
            row.addView(cell)
        }
        row.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(Color.WHITE)
            contentDescription = "Add page"
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(0x22FFFFFF); setStroke(dp(1f).toInt(), 0x33FFFFFF) }
            layoutParams = LinearLayout.LayoutParams(thumbH, thumbH)
            setOnClickListener { listener?.onAddPage() }
        })
    }

    fun toggle(board: Board) {
        if (visibility == VISIBLE) hide() else { render(board); visibility = VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(150).start() }
    }

    fun hide() { if (visibility == VISIBLE) animate().alpha(0f).setDuration(120).withEndAction { visibility = GONE }.start() }

    val isShowing get() = visibility == VISIBLE

    /** Fit-to-content miniature of a page. */
    private class Thumb(context: Context, private val page: Page) : View(context) {
        private val ink = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true }
        private val fill = Paint().apply { style = Paint.Style.STROKE; isAntiAlias = true }
        private val path = Path()

        override fun onDraw(c: Canvas) {
            val b = page.contentBounds() ?: return
            val pad = 0.1f
            val sx = width * (1 - 2 * pad) / maxOf(b.width, 1f)
            val sy = height * (1 - 2 * pad) / maxOf(b.height, 1f)
            val s = minOf(sx, sy)
            c.save()
            c.translate(width * pad + (width * (1 - 2 * pad) - b.width * s) / 2f, height * pad + (height * (1 - 2 * pad) - b.height * s) / 2f)
            c.scale(s, s)
            c.translate(-b.left, -b.top)
            for (st in page.strokes) {
                ink.color = st.color; ink.alpha = if (st.kind == InkKind.HIGHLIGHTER) 90 else 255
                ink.strokeWidth = maxOf(st.width, 1.5f / s)
                val p = st.points
                if (p.size < 4) continue
                path.rewind(); path.moveTo(p[0], p[1])
                var i = 2; while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
                c.drawPath(path, ink)
            }
            for (sh in page.shapes) {
                fill.color = sh.color; fill.strokeWidth = maxOf(sh.width, 1.5f / s)
                val l = minOf(sh.x0, sh.x1); val t = minOf(sh.y0, sh.y1); val r = maxOf(sh.x0, sh.x1); val bb = maxOf(sh.y0, sh.y1)
                when (sh.type) {
                    ShapeType.RECT -> c.drawRect(l, t, r, bb, fill)
                    ShapeType.ELLIPSE -> c.drawOval(l, t, r, bb, fill)
                    ShapeType.LINE, ShapeType.ARROW -> c.drawLine(sh.x0, sh.y0, sh.x1, sh.y1, fill)
                    ShapeType.TRIANGLE -> { path.rewind(); path.moveTo((l + r) / 2, t); path.lineTo(r, bb); path.lineTo(l, bb); path.close(); c.drawPath(path, fill) }
                }
            }
            c.restore()
        }
    }
}
