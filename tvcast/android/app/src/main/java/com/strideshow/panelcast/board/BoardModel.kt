package com.strideshow.panelcast.board

import org.json.JSONArray
import org.json.JSONObject

/**
 * The whiteboard document model.
 *
 * Deliberately free of android.graphics types so it can be unit-tested on
 * the JVM and serialised without a device. Coordinates are in *board space*,
 * an infinite plane the user pans and zooms over; the view converts to
 * pixels. Pen widths are also in board units so a stroke drawn while zoomed
 * in stays proportionally thin when you zoom out, exactly like real ink on
 * a real page.
 */

/** What a stroke was made with. Drives rendering, not just colour. */
enum class InkKind {
    /** Solid, round-capped, pressure-independent. */
    PEN,
    /** Wide, translucent, square-capped, drawn under other ink. */
    HIGHLIGHTER,
    /** Thin, faintly lit; used for the "laser" that fades out. Never saved. */
    LASER,
}

/** One continuous touch from down to up. */
data class Stroke(
    val id: Long,
    val kind: InkKind,
    /** ARGB. Highlighter alpha is applied at render time, not stored here. */
    val color: Int,
    /** Width in board units. */
    val width: Float,
    /** Flat x0,y0,x1,y1,... - half the allocations of a list of points. */
    val points: FloatArray,
    /** Owner tag for multi-user sessions; 0 = local panel. */
    val author: Int = 0,
) {
    val pointCount: Int get() = points.size / 2

    /** Axis-aligned bounds, padded by half the width so hit tests include the ink edge. */
    fun bounds(): Rect {
        if (points.isEmpty()) return Rect(0f, 0f, 0f, 0f)
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < points.size) {
            val x = points[i]; val y = points[i + 1]
            if (x < l) l = x; if (x > r) r = x
            if (y < t) t = y; if (y > b) b = y
            i += 2
        }
        val pad = width / 2f
        return Rect(l - pad, t - pad, r + pad, b + pad)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("k", kind.name).put("c", color).put("w", width.toDouble())
        .put("a", author)
        .put("p", JSONArray().also { arr -> for (v in points) arr.put(v.toDouble()) })

    companion object {
        fun fromJson(o: JSONObject): Stroke {
            val arr = o.getJSONArray("p")
            val pts = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            return Stroke(
                id = o.getLong("id"),
                kind = InkKind.valueOf(o.optString("k", "PEN")),
                color = o.getInt("c"),
                width = o.getDouble("w").toFloat(),
                points = pts,
                author = o.optInt("a", 0),
            )
        }
    }
}

enum class ShapeType { LINE, ARROW, RECT, ELLIPSE, TRIANGLE }

/**
 * A geometric object. Stored by two corner points so resizing is trivial;
 * the renderer derives the geometry. `filled` shapes use a translucent fill
 * of the stroke colour, which reads well on a dark board.
 */
data class Shape(
    val id: Long,
    val type: ShapeType,
    val color: Int,
    val width: Float,
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val filled: Boolean = false,
    val author: Int = 0,
) {
    fun bounds(): Rect {
        val pad = width / 2f
        return Rect(minOf(x0, x1) - pad, minOf(y0, y1) - pad, maxOf(x0, x1) + pad, maxOf(y0, y1) + pad)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("t", type.name).put("c", color).put("w", width.toDouble())
        .put("x0", x0.toDouble()).put("y0", y0.toDouble())
        .put("x1", x1.toDouble()).put("y1", y1.toDouble())
        .put("f", filled).put("a", author)

    companion object {
        fun fromJson(o: JSONObject) = Shape(
            id = o.getLong("id"),
            type = ShapeType.valueOf(o.getString("t")),
            color = o.getInt("c"),
            width = o.getDouble("w").toFloat(),
            x0 = o.getDouble("x0").toFloat(), y0 = o.getDouble("y0").toFloat(),
            x1 = o.getDouble("x1").toFloat(), y1 = o.getDouble("y1").toFloat(),
            filled = o.optBoolean("f", false),
            author = o.optInt("a", 0),
        )
    }
}

/** Typed text placed on the board. Size in board units. */
data class TextItem(
    val id: Long,
    val text: String,
    val color: Int,
    val size: Float,
    val x: Float, val y: Float,
    val author: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("s", text).put("c", color).put("z", size.toDouble())
        .put("x", x.toDouble()).put("y", y.toDouble()).put("a", author)

    companion object {
        fun fromJson(o: JSONObject) = TextItem(
            id = o.getLong("id"), text = o.getString("s"), color = o.getInt("c"),
            size = o.getDouble("z").toFloat(),
            x = o.getDouble("x").toFloat(), y = o.getDouble("y").toFloat(),
            author = o.optInt("a", 0),
        )
    }
}

/** Minimal rect so the model has no android.graphics dependency. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun intersects(o: Rect) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun union(o: Rect) = Rect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))
}

/**
 * Background pattern. Each is a subtle, dark-theme-friendly guide. Spacing
 * is in board units so the paper scales with the zoom like a real sheet.
 */
enum class Background(val label: String) {
    GRAPH("Graph paper"),
    DOTS("Dot grid"),
    LINES("Ruled lines"),
    CARTESIAN("Cartesian axes"),
    MUSIC("Music staff"),
    PLAIN("Plain"),
}

/**
 * One page of the board. Pages are how a lesson is organised (SMART, Newline
 * and Promethean all have them); each page is its own infinite canvas with
 * its own background and view position.
 */
class Page(
    val id: Long,
    var background: Background = Background.GRAPH,
) {
    val strokes = ArrayList<Stroke>()
    val shapes = ArrayList<Shape>()
    val texts = ArrayList<TextItem>()

    /** Where the user left the camera on this page, in board units. */
    var viewX = 0f
    var viewY = 0f
    var viewScale = 1f

    /** Undo history: each entry reverses one user action. */
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()
    val isEmpty get() = strokes.isEmpty() && shapes.isEmpty() && texts.isEmpty()

    /** Union of all content, for "fit to content" and export. Null if empty. */
    fun contentBounds(): Rect? {
        var r: Rect? = null
        for (s in strokes) r = r?.union(s.bounds()) ?: s.bounds()
        for (s in shapes) r = r?.union(s.bounds()) ?: s.bounds()
        for (t in texts) {
            val b = Rect(t.x, t.y - t.size, t.x + t.size * 0.6f * t.text.length, t.y + t.size * 0.3f)
            r = r?.union(b) ?: b
        }
        return r
    }

    // ---- edits ----------------------------------------------------------

    /** An undoable change. `apply` does it, `revert` undoes it. */
    sealed class Edit {
        abstract fun apply(p: Page)
        abstract fun revert(p: Page)

        class AddStroke(val s: Stroke) : Edit() {
            override fun apply(p: Page) { p.strokes.add(s) }
            override fun revert(p: Page) { p.strokes.removeAll { it.id == s.id } }
        }
        class AddShape(val s: Shape) : Edit() {
            override fun apply(p: Page) { p.shapes.add(s) }
            override fun revert(p: Page) { p.shapes.removeAll { it.id == s.id } }
        }
        class AddText(val t: TextItem) : Edit() {
            override fun apply(p: Page) { p.texts.add(t) }
            override fun revert(p: Page) { p.texts.removeAll { it.id == t.id } }
        }
        /** Erase pass: removes any number of objects in one undo step. */
        class Remove(val strokes: List<Stroke>, val shapes: List<Shape>, val texts: List<TextItem>) : Edit() {
            override fun apply(p: Page) {
                val sid = strokes.mapTo(HashSet()) { it.id }
                val hid = shapes.mapTo(HashSet()) { it.id }
                val tid = texts.mapTo(HashSet()) { it.id }
                p.strokes.removeAll { it.id in sid }
                p.shapes.removeAll { it.id in hid }
                p.texts.removeAll { it.id in tid }
            }
            override fun revert(p: Page) {
                p.strokes.addAll(strokes); p.shapes.addAll(shapes); p.texts.addAll(texts)
            }
            val isEmpty get() = strokes.isEmpty() && shapes.isEmpty() && texts.isEmpty()
        }
        /** Clear page. Kept as one step so "oops" is a single undo. */
        class Clear(val strokes: List<Stroke>, val shapes: List<Shape>, val texts: List<TextItem>) : Edit() {
            override fun apply(p: Page) { p.strokes.clear(); p.shapes.clear(); p.texts.clear() }
            override fun revert(p: Page) {
                p.strokes.addAll(strokes); p.shapes.addAll(shapes); p.texts.addAll(texts)
            }
        }
        /** Move a set of objects by a delta (selection drag). */
        class Translate(val strokeIds: Set<Long>, val shapeIds: Set<Long>, val textIds: Set<Long>, val dx: Float, val dy: Float) : Edit() {
            override fun apply(p: Page) = shift(p, dx, dy)
            override fun revert(p: Page) = shift(p, -dx, -dy)
            private fun shift(p: Page, dx: Float, dy: Float) {
                for (i in p.strokes.indices) {
                    val s = p.strokes[i]
                    if (s.id in strokeIds) {
                        val pts = s.points.copyOf()
                        var k = 0
                        while (k < pts.size) { pts[k] += dx; pts[k + 1] += dy; k += 2 }
                        p.strokes[i] = s.copy(points = pts)
                    }
                }
                for (i in p.shapes.indices) {
                    val s = p.shapes[i]
                    if (s.id in shapeIds) p.shapes[i] = s.copy(x0 = s.x0 + dx, y0 = s.y0 + dy, x1 = s.x1 + dx, y1 = s.y1 + dy)
                }
                for (i in p.texts.indices) {
                    val t = p.texts[i]
                    if (t.id in textIds) p.texts[i] = t.copy(x = t.x + dx, y = t.y + dy)
                }
            }
        }
    }

    fun commit(e: Edit) {
        e.apply(this)
        undo.addLast(e)
        if (undo.size > MAX_UNDO) undo.removeFirst()
        redo.clear()
    }

    fun undo(): Boolean {
        val e = undo.removeLastOrNull() ?: return false
        e.revert(this)
        redo.addLast(e)
        return true
    }

    fun redo(): Boolean {
        val e = redo.removeLastOrNull() ?: return false
        e.apply(this)
        undo.addLast(e)
        return true
    }

    // ---- hit testing ----------------------------------------------------

    /**
     * Everything touched by a circular eraser at (x,y) radius r. Strokes are
     * tested segment-by-segment so erasing between two lines does not take
     * out both just because their bounding boxes overlap.
     */
    fun hitTest(x: Float, y: Float, r: Float): Edit.Remove {
        val probe = Rect(x - r, y - r, x + r, y + r)
        val hs = strokes.filter { s -> s.bounds().intersects(probe) && strokeHits(s, x, y, r) }
        val hh = shapes.filter { s -> s.bounds().intersects(probe) && shapeHits(s, x, y, r) }
        val ht = texts.filter { t ->
            Rect(t.x, t.y - t.size, t.x + t.size * 0.6f * t.text.length, t.y + t.size * 0.3f).intersects(probe)
        }
        return Edit.Remove(hs, hh, ht)
    }

    /** Objects fully or partly inside a marquee rectangle. */
    fun select(area: Rect): Selection {
        val ss = strokes.filter { it.bounds().intersects(area) }.map { it.id }.toSet()
        val hs = shapes.filter { it.bounds().intersects(area) }.map { it.id }.toSet()
        val ts = texts.filter { t ->
            Rect(t.x, t.y - t.size, t.x + t.size * 0.6f * t.text.length, t.y + t.size * 0.3f).intersects(area)
        }.map { it.id }.toSet()
        return Selection(ss, hs, ts)
    }

    fun boundsOf(sel: Selection): Rect? {
        var r: Rect? = null
        for (s in strokes) if (s.id in sel.strokes) r = r?.union(s.bounds()) ?: s.bounds()
        for (s in shapes) if (s.id in sel.shapes) r = r?.union(s.bounds()) ?: s.bounds()
        for (t in texts) if (t.id in sel.texts) {
            val b = Rect(t.x, t.y - t.size, t.x + t.size * 0.6f * t.text.length, t.y + t.size * 0.3f)
            r = r?.union(b) ?: b
        }
        return r
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("bg", background.name)
        .put("vx", viewX.toDouble()).put("vy", viewY.toDouble()).put("vs", viewScale.toDouble())
        .put("strokes", JSONArray().also { a -> strokes.filter { it.kind != InkKind.LASER }.forEach { a.put(it.toJson()) } })
        .put("shapes", JSONArray().also { a -> shapes.forEach { a.put(it.toJson()) } })
        .put("texts", JSONArray().also { a -> texts.forEach { a.put(it.toJson()) } })

    companion object {
        const val MAX_UNDO = 200

        fun fromJson(o: JSONObject): Page {
            val p = Page(o.getLong("id"), Background.valueOf(o.optString("bg", "GRAPH")))
            p.viewX = o.optDouble("vx", 0.0).toFloat()
            p.viewY = o.optDouble("vy", 0.0).toFloat()
            p.viewScale = o.optDouble("vs", 1.0).toFloat().coerceIn(0.05f, 20f)
            o.optJSONArray("strokes")?.let { a -> for (i in 0 until a.length()) p.strokes.add(Stroke.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("shapes")?.let { a -> for (i in 0 until a.length()) p.shapes.add(Shape.fromJson(a.getJSONObject(i))) }
            o.optJSONArray("texts")?.let { a -> for (i in 0 until a.length()) p.texts.add(TextItem.fromJson(a.getJSONObject(i))) }
            return p
        }

        /** Distance from point to segment, squared - avoids a sqrt per segment. */
        fun distSqToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
            val cx = ax + t * dx; val cy = ay + t * dy
            val ex = px - cx; val ey = py - cy
            return ex * ex + ey * ey
        }

        fun strokeHits(s: Stroke, x: Float, y: Float, r: Float): Boolean {
            val reach = r + s.width / 2f
            val reach2 = reach * reach
            val p = s.points
            if (p.size == 2) {
                val dx = p[0] - x; val dy = p[1] - y
                return dx * dx + dy * dy <= reach2
            }
            var i = 0
            while (i + 3 < p.size) {
                if (distSqToSegment(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= reach2) return true
                i += 2
            }
            return false
        }

        /** Shapes hit on their outline (or anywhere inside if filled). */
        fun shapeHits(s: Shape, x: Float, y: Float, r: Float): Boolean {
            val b = s.bounds()
            if (s.filled && b.contains(x, y)) return true
            val reach = r + s.width / 2f
            val reach2 = reach * reach
            return when (s.type) {
                ShapeType.LINE, ShapeType.ARROW ->
                    distSqToSegment(x, y, s.x0, s.y0, s.x1, s.y1) <= reach2
                ShapeType.RECT -> {
                    val l = minOf(s.x0, s.x1); val t = minOf(s.y0, s.y1)
                    val rr = maxOf(s.x0, s.x1); val bb = maxOf(s.y0, s.y1)
                    distSqToSegment(x, y, l, t, rr, t) <= reach2 || distSqToSegment(x, y, rr, t, rr, bb) <= reach2 ||
                        distSqToSegment(x, y, rr, bb, l, bb) <= reach2 || distSqToSegment(x, y, l, bb, l, t) <= reach2
                }
                ShapeType.TRIANGLE -> {
                    val l = minOf(s.x0, s.x1); val t = minOf(s.y0, s.y1)
                    val rr = maxOf(s.x0, s.x1); val bb = maxOf(s.y0, s.y1)
                    val mx = (l + rr) / 2f
                    distSqToSegment(x, y, mx, t, rr, bb) <= reach2 || distSqToSegment(x, y, rr, bb, l, bb) <= reach2 ||
                        distSqToSegment(x, y, l, bb, mx, t) <= reach2
                }
                ShapeType.ELLIPSE -> {
                    // Distance to an ellipse outline has no closed form; a
                    // normalised-radius band is accurate enough for a finger.
                    val cx = (s.x0 + s.x1) / 2f; val cy = (s.y0 + s.y1) / 2f
                    val rx = maxOf(kotlin.math.abs(s.x1 - s.x0) / 2f, 1e-3f)
                    val ry = maxOf(kotlin.math.abs(s.y1 - s.y0) / 2f, 1e-3f)
                    val nx = (x - cx) / rx; val ny = (y - cy) / ry
                    val d = kotlin.math.sqrt(nx * nx + ny * ny)
                    val tol = reach / minOf(rx, ry)
                    kotlin.math.abs(d - 1f) <= tol
                }
            }
        }
    }
}

/** Ids of selected objects on the current page. */
data class Selection(val strokes: Set<Long>, val shapes: Set<Long>, val texts: Set<Long>) {
    val isEmpty get() = strokes.isEmpty() && shapes.isEmpty() && texts.isEmpty()
    companion object { val EMPTY = Selection(emptySet(), emptySet(), emptySet()) }
}

/** The whole document: an ordered list of pages plus the current index. */
class Board {
    val pages = ArrayList<Page>().apply { add(Page(nextId())) }
    var current = 0
    val page: Page get() = pages[current]

    fun addPage(after: Int = current): Page {
        val p = Page(nextId(), pages.getOrNull(after)?.background ?: Background.GRAPH)
        pages.add(after + 1, p)
        current = after + 1
        return p
    }

    fun removePage(index: Int) {
        if (pages.size <= 1) { pages[0] = Page(nextId(), pages[0].background); current = 0; return }
        pages.removeAt(index)
        current = current.coerceIn(0, pages.size - 1)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("current", current)
        .put("pages", JSONArray().also { a -> pages.forEach { a.put(it.toJson()) } })

    companion object {
        private var idCounter = System.currentTimeMillis()
        /** Monotonic ids: unique across pages and sessions on one device. */
        @Synchronized fun nextId(): Long = ++idCounter

        fun fromJson(o: JSONObject): Board {
            val b = Board()
            b.pages.clear()
            val arr = o.optJSONArray("pages")
            if (arr != null) for (i in 0 until arr.length()) b.pages.add(Page.fromJson(arr.getJSONObject(i)))
            if (b.pages.isEmpty()) b.pages.add(Page(nextId()))
            b.current = o.optInt("current", 0).coerceIn(0, b.pages.size - 1)
            // Never hand out an id that already exists in the loaded document.
            val maxId = b.pages.maxOf { p ->
                maxOf(p.id, p.strokes.maxOfOrNull { it.id } ?: 0L, p.shapes.maxOfOrNull { it.id } ?: 0L, p.texts.maxOfOrNull { it.id } ?: 0L)
            }
            synchronized(Board) { if (maxId >= idCounter) idCounter = maxId + 1 }
            return b
        }
    }
}
