package com.strideshow.panelcast.board

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure geometry for ink: smoothing, simplification and shape recognition.
 * No Android types, so it is unit-tested on the JVM.
 */
object InkGeometry {

    /**
     * Ramer-Douglas-Peucker simplification. Touch panels report 120-240
     * points per second; most of them are collinear noise. Dropping them
     * makes hit-testing, saving and redrawing several times cheaper with no
     * visible change. `epsilon` is in board units.
     */
    fun simplify(points: FloatArray, epsilon: Float): FloatArray {
        val n = points.size / 2
        if (n < 3) return points
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        val eps2 = epsilon * epsilon
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var maxD = 0f; var idx = -1
            val ax = points[a * 2]; val ay = points[a * 2 + 1]
            val bx = points[b * 2]; val by = points[b * 2 + 1]
            for (i in a + 1 until b) {
                val d = Page.distSqToSegment(points[i * 2], points[i * 2 + 1], ax, ay, bx, by)
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > eps2 && idx > 0) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b))
            }
        }
        var count = 0
        for (k in keep) if (k) count++
        val out = FloatArray(count * 2)
        var j = 0
        for (i in 0 until n) if (keep[i]) { out[j++] = points[i * 2]; out[j++] = points[i * 2 + 1] }
        return out
    }

    /** Total polyline length. */
    fun pathLength(p: FloatArray): Float {
        var len = 0f
        var i = 0
        while (i + 3 < p.size) { len += hypot(p[i + 2] - p[i], p[i + 3] - p[i + 1]); i += 2 }
        return len
    }

    /**
     * Ink-to-shape recognition.
     *
     * Approach: classify by a handful of robust global features rather than
     * template matching, because a whiteboard shape is drawn large, fast and
     * roughly - the features that survive that are (a) whether it closes,
     * (b) how straight it is, (c) how many corners it has, and (d) how its
     * area compares to its bounding box.
     *
     * Returns null when the stroke does not look like a shape, so freehand
     * writing is never mangled into rectangles.
     */
    fun recognise(raw: FloatArray, minSize: Float): Shape? {
        val n = raw.size / 2
        if (n < 8) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var i = 0
        while (i < raw.size) {
            l = min(l, raw[i]); r = max(r, raw[i]); t = min(t, raw[i + 1]); b = max(b, raw[i + 1]); i += 2
        }
        val w = r - l; val h = b - t
        val diag = hypot(w, h)
        if (diag < minSize) return null

        val len = pathLength(raw)
        val startEnd = hypot(raw[raw.size - 2] - raw[0], raw[raw.size - 1] - raw[1])

        // --- straight line: the path barely exceeds the chord -------------
        if (len > 0f && startEnd / len > 0.94f) {
            // Everything close to the chord?
            var maxDev = 0f
            var k = 0
            while (k < raw.size) {
                maxDev = max(maxDev, sqrt(Page.distSqToSegment(raw[k], raw[k + 1], raw[0], raw[1], raw[raw.size - 2], raw[raw.size - 1])))
                k += 2
            }
            if (maxDev < diag * 0.06f) {
                return Shape(0, ShapeType.LINE, 0, 0f, raw[0], raw[1], raw[raw.size - 2], raw[raw.size - 1])
            }
        }

        // --- closed figures -----------------------------------------------
        // A shape is "closed" if the pen ends near where it began, relative
        // to its size. Hands are imprecise: 25% of the diagonal is generous
        // but still rejects an open arc.
        val closed = startEnd < diag * 0.25f
        if (!closed) return null

        // Corner count via simplification at a coarse tolerance: a rectangle
        // reduces to ~4-5 vertices, a triangle to ~3-4, an ellipse to many.
        val simp = simplify(raw, diag * 0.08f)
        var corners = simp.size / 2 - 1 // the closing vertex duplicates the first
        // Merge start/end if they nearly coincide - they always do for a
        // closed figure, and counting them as two corners misclassifies.
        if (corners >= 2) {
            val ex = simp[simp.size - 2] - simp[0]; val ey = simp[simp.size - 1] - simp[1]
            if (hypot(ex, ey) < diag * 0.2f) corners -= 0 else corners += 1
        }

        // Area ratio: polygon area vs bounding box. Rect ~1, ellipse ~0.785,
        // triangle ~0.5.
        val area = abs(polygonArea(raw)) / max(w * h, 1e-3f)

        // Polygon corner angles: sharp turns suggest polygon, smooth = ellipse.
        val sharp = countSharpTurns(simp, 40f)

        return when {
            corners <= 4 && sharp >= 3 && area > 0.42f && area < 0.68f ->
                Shape(0, ShapeType.TRIANGLE, 0, 0f, l, t, r, b)
            sharp >= 4 && area >= 0.68f ->
                Shape(0, ShapeType.RECT, 0, 0f, l, t, r, b)
            sharp <= 2 && area > 0.6f && area < 0.9f ->
                Shape(0, ShapeType.ELLIPSE, 0, 0f, l, t, r, b)
            // Rounded-off rectangles and lumpy circles: decide by area.
            area >= 0.86f -> Shape(0, ShapeType.RECT, 0, 0f, l, t, r, b)
            area >= 0.62f && sharp <= 3 -> Shape(0, ShapeType.ELLIPSE, 0, 0f, l, t, r, b)
            else -> null
        }
    }

    /** Shoelace formula over the raw polyline (implicitly closed). */
    fun polygonArea(p: FloatArray): Float {
        val n = p.size / 2
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return a / 2f
    }

    /** Number of vertices in a simplified polyline whose turn exceeds `degrees`. */
    fun countSharpTurns(simp: FloatArray, degrees: Float): Int {
        val n = simp.size / 2
        if (n < 3) return 0
        var count = 0
        val thresh = Math.toRadians(degrees.toDouble())
        // Treat as closed loop so the start vertex is tested too.
        for (i in 0 until n - 1) {
            val prev = if (i == 0) n - 2 else i - 1
            val next = i + 1
            val a1 = atan2((simp[i * 2 + 1] - simp[prev * 2 + 1]).toDouble(), (simp[i * 2] - simp[prev * 2]).toDouble())
            val a2 = atan2((simp[next * 2 + 1] - simp[i * 2 + 1]).toDouble(), (simp[next * 2] - simp[i * 2]).toDouble())
            var d = abs(a2 - a1)
            if (d > Math.PI) d = 2 * Math.PI - d
            if (d > thresh) count++
        }
        return count
    }

    /**
     * Palm detection. Capacitive panels report a touch-major axis; a finger
     * or stylus is a few millimetres, a palm or fist is several centimetres.
     * Threshold is in pixels and supplied by the caller from display density.
     */
    fun isPalm(touchMajorPx: Float, palmThresholdPx: Float) = touchMajorPx >= palmThresholdPx
}
