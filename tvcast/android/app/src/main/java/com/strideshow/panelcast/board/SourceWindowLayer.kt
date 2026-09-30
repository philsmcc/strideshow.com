package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Holds every SourceWindow and owns the gesture arbitration between windows
 * and the ink layer above them.
 *
 * Z-order in the activity:
 *   [paper + ink]  BoardView         (top; drawn last; receives most touches)
 *   [windows]      SourceWindowLayer (this)
 *
 * But touch order is the *reverse* of what the user expects: they tap a
 * window's title bar to drag it, and pinch a window to resize it, while
 * writing over the window's video should still ink. So BoardView asks this
 * layer first via `claim()` whether a new pointer belongs to a window:
 *
 *  - Pointer on a title strip           -> window drag (1 finger)
 *  - Pointer on a strip button          -> click, handled by the button
 *  - Two pointers both inside a window,
 *    landing within a short interval    -> window pinch: move/scale/rotate
 *  - Anything else                      -> ink (BoardView keeps it)
 *
 * Long-press anywhere on a window also picks it up for a drag, which is the
 * gesture most people try first on a touch panel.
 */
class SourceWindowLayer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onCloseRequested(w: SourceWindow)
        fun onFullscreenToggled(w: SourceWindow, full: Boolean)
        fun onMuteToggled(w: SourceWindow, muted: Boolean)
        fun onWindowFocused(w: SourceWindow)
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())

    val windows: List<SourceWindow> get() = (0 until childCount).mapNotNull { getChildAt(it) as? SourceWindow }

    fun find(peerId: String): SourceWindow? = windows.firstOrNull { it.peerId == peerId }

    // --- gesture state ---------------------------------------------------------

    private var target: SourceWindow? = null
    private var idA = -1; private var idB = -1
    private var dragging = false
    private var pinching = false
    private var lastAX = 0f; private var lastAY = 0f
    private var startDist = 0f; private var startAngle = 0f
    private var startScaleX = 1f
    private var startRot = 0f
    private var startCX = 0f; private var startCY = 0f
    private var startMidX = 0f; private var startMidY = 0f
    private var rotateEnabled = true
    private val snapDeg = 6f // snap back to axis when this close

    private var pressCandidate: SourceWindow? = null
    private var pressX = 0f; private var pressY = 0f
    private var pressId = -1
    private val longPress = Runnable {
        val w = pressCandidate ?: return@Runnable
        pressCandidate = null
        // Promote to a drag; BoardView is told to release the pointer.
        onLongPressPickup?.invoke(pressId, w)
    }
    /** Set by BoardView glue: cancels the pointer's stroke and hands it to us. */
    var onLongPressPickup: ((pointerId: Int, w: SourceWindow) -> Unit)? = null

    private val chromeHideDelay = 3500L
    private val hideChrome = Runnable { windows.forEach { it.setChromeVisible(false) } }

    // --- add / remove ----------------------------------------------------------

    fun add(w: SourceWindow) {
        // Default size: a third of the screen width, 16:9, placed with a
        // cascading offset so several windows do not stack exactly.
        val n = windows.size
        val defW = (width * 0.34f).toInt().coerceAtLeast(320)
        val defH = (defW / w.aspect).toInt()
        val lp = LayoutParams(defW, defH)
        addView(w, lp)
        val offset = (n % 5) * dpPx(36f)
        w.x = width * 0.08f + offset
        w.y = height * 0.12f + offset
        wireButtons(w)
        showChromeBriefly()
        listener?.onWindowFocused(w)
    }

    fun remove(w: SourceWindow) {
        if (target === w) resetGesture()
        removeView(w)
        w.release()
    }

    private fun wireButtons(w: SourceWindow) {
        w.btnClose.setOnClickListener { listener?.onCloseRequested(w) }
        w.btnFull.setOnClickListener { toggleFullscreen(w) }
        w.btnMute.setOnClickListener {
            w.muted = !w.muted
            // Un-muting one source mutes the others: two audio tracks at
            // once is never what a teacher wants.
            if (!w.muted) windows.filter { it !== w && !it.muted }.forEach { o -> o.muted = true; listener?.onMuteToggled(o, true) }
            listener?.onMuteToggled(w, w.muted)
        }
    }

    // --- fullscreen ---------------------------------------------------------------

    fun toggleFullscreen(w: SourceWindow) {
        if (w.isFullscreen) exitFullscreen(w) else enterFullscreen(w)
    }

    private fun enterFullscreen(w: SourceWindow) {
        windows.filter { it.isFullscreen && it !== w }.forEach { exitFullscreen(it) }
        w.savedX = w.x; w.savedY = w.y; w.savedW = w.width; w.savedH = w.height; w.savedRot = w.rotation
        w.isFullscreen = true
        bringToFront(w)
        w.animate().x(0f).y(0f).rotation(0f).setDuration(200).start()
        // Fit the video's aspect inside the screen with black bars rather
        // than stretching; the renderer letterboxes itself.
        val lp = w.layoutParams as LayoutParams
        lp.width = width; lp.height = height
        w.layoutParams = lp
        listener?.onFullscreenToggled(w, true)
    }

    private fun exitFullscreen(w: SourceWindow) {
        w.isFullscreen = false
        val lp = w.layoutParams as LayoutParams
        lp.width = if (w.savedW > 0) w.savedW else (width * 0.34f).toInt()
        lp.height = if (w.savedH > 0) w.savedH else (lp.width / w.aspect).toInt()
        w.layoutParams = lp
        w.animate().x(w.savedX).y(w.savedY).rotation(w.savedRot).setDuration(200).start()
        listener?.onFullscreenToggled(w, false)
    }

    // --- layout helpers ----------------------------------------------------------------

    fun onWindowAspectChanged(w: SourceWindow) {
        if (w.isFullscreen) return
        val lp = w.layoutParams as? LayoutParams ?: return
        lp.height = (lp.width / w.aspect).toInt().coerceAtLeast(dpPx(120f).toInt())
        w.layoutParams = lp
        clampInside(w)
    }

    /** Keep at least a grab-able margin of each window on screen. */
    private fun clampInside(w: SourceWindow) {
        if (width == 0 || height == 0) return
        val minVisible = dpPx(80f)
        val vw = w.width * w.scaleX; val vh = w.height * w.scaleY
        w.x = w.x.coerceIn(-vw + minVisible, width - minVisible)
        w.y = w.y.coerceIn(0f, height - minVisible)
    }

    fun bringToFront(w: SourceWindow) {
        if (indexOfChild(w) != childCount - 1) { removeView(w); addView(w) }
        listener?.onWindowFocused(w)
    }

    /** Arrange all windows in a grid - the "tile" button. */
    fun tileAll() {
        val ws = windows.filter { !it.isFullscreen }
        if (ws.isEmpty()) return
        val n = ws.size
        val cols = kotlin.math.ceil(kotlin.math.sqrt(n.toDouble())).toInt().coerceAtLeast(1)
        val rows = kotlin.math.ceil(n / cols.toDouble()).toInt()
        val pad = dpPx(16f)
        val topInset = dpPx(72f)  // leave the toolbar visible
        val cellW = (width - pad * (cols + 1)) / cols
        val cellH = (height - topInset - pad * (rows + 1)) / rows
        ws.forEachIndexed { i, w ->
            val c = i % cols; val r = i / cols
            var tw = cellW; var th = tw / w.aspect
            if (th > cellH) { th = cellH; tw = th * w.aspect }
            val lp = w.layoutParams as LayoutParams
            lp.width = tw.toInt(); lp.height = th.toInt(); w.layoutParams = lp
            w.scaleX = 1f; w.scaleY = 1f
            val tx = pad + c * (cellW + pad) + (cellW - tw) / 2f
            val ty = topInset + pad + r * (cellH + pad) + (cellH - th) / 2f
            w.animate().x(tx).y(ty).rotation(0f).setDuration(220).start()
        }
        showChromeBriefly()
    }

    // --- touch arbitration -----------------------------------------------------------

    /** Which window (topmost first) contains this screen point, and where. */
    private fun windowAt(x: Float, y: Float, local: PointF): SourceWindow? {
        for (i in childCount - 1 downTo 0) {
            val w = getChildAt(i) as? SourceWindow ?: continue
            if (toLocal(w, x, y, local)) return w
        }
        return null
    }

    private val tmpM = Matrix()
    private val tmpPt = FloatArray(2)

    /** Map a parent point into a (possibly rotated/scaled) child's coordinates. */
    private fun toLocal(w: View, x: Float, y: Float, out: PointF): Boolean {
        w.matrix.invert(tmpM)
        tmpPt[0] = x - w.left; tmpPt[1] = y - w.top
        tmpM.mapPoints(tmpPt)
        out.set(tmpPt[0], tmpPt[1])
        return tmpPt[0] >= 0 && tmpPt[1] >= 0 && tmpPt[0] < w.width && tmpPt[1] < w.height
    }

    private val localPt = PointF()

    /**
     * Called by BoardView on every pointer-down BEFORE it starts a stroke.
     * @return true if this layer takes the pointer (BoardView must ignore it).
     */
    fun claim(ev: MotionEvent, idx: Int, pointerId: Int, recentDownId: Int, recentDownAt: Long): Boolean {
        val x = ev.getX(idx); val y = ev.getY(idx)
        val w = windowAt(x, y, localPt)
        showChromeBriefly()
        if (w == null) return false

        // Already pinching a window and a third finger lands: ignore it.
        if (pinching) return true

        // Second finger inside the same window we are dragging: upgrade to pinch.
        if (dragging && target === w) {
            beginPinch(ev, idA, pointerId, w)
            return true
        }

        // Two fingers landing inside the same window within ~250ms: pinch,
        // even though the first was heading for ink. BoardView cancels its
        // stroke for `recentDownId` when we return true here for it.
        if (target == null && recentDownId >= 0 && recentDownId != pointerId && ev.eventTime - recentDownAt < 250) {
            val ri = ev.findPointerIndex(recentDownId)
            if (ri >= 0 && toLocal(w, ev.getX(ri), ev.getY(ri), PointF())) {
                bringToFront(w)
                beginPinch(ev, recentDownId, pointerId, w)
                pinchStoleFirst = recentDownId
                return true
            }
        }

        // Title strip: buttons handle their own clicks; the rest drags.
        if (w.isOnButton(localPt.x, localPt.y)) {
            // Let the normal view hierarchy dispatch the click.
            return false
        }
        if (w.isOnChrome(localPt.x, localPt.y)) {
            bringToFront(w)
            beginDrag(ev, pointerId, w)
            return true
        }

        // Body: arm a long-press pickup; until it fires, the pointer inks.
        pressCandidate = w; pressX = x; pressY = y; pressId = pointerId
        main.removeCallbacks(longPress)
        main.postDelayed(longPress, 420)
        return false
    }

    /** Set when a pinch stole a pointer BoardView had already started inking with. */
    var pinchStoleFirst = -1

    /** Called by BoardView to feed us pointer moves/ups for pointers we own. */
    fun onPointerMove(ev: MotionEvent) {
        // Long-press is cancelled by movement.
        pressCandidate?.let {
            val i = ev.findPointerIndex(pressId)
            if (i >= 0 && hypot(ev.getX(i) - pressX, ev.getY(i) - pressY) > dpPx(10f)) { pressCandidate = null; main.removeCallbacks(longPress) }
        }
        val w = target ?: return
        if (pinching) {
            val ia = ev.findPointerIndex(idA); val ib = ev.findPointerIndex(idB)
            if (ia < 0 || ib < 0) return
            val ax = ev.getX(ia); val ay = ev.getY(ia); val bx = ev.getX(ib); val by = ev.getY(ib)
            val dist = max(hypot(bx - ax, by - ay), 1f)
            val ang = Math.toDegrees(atan2(by - ay, bx - ax).toDouble()).toFloat()
            val midX = (ax + bx) / 2f; val midY = (ay + by) / 2f
            val s = (startScaleX * dist / startDist).coerceIn(minScale(w), maxScale(w))
            w.scaleX = s; w.scaleY = s
            if (rotateEnabled) {
                var rot = startRot + (ang - startAngle)
                // Gentle snap to 0/90/180/270 so a slightly-off pinch stays square.
                val nearest = Math.round(rot / 90f) * 90f
                if (abs(rot - nearest) < snapDeg) rot = nearest
                w.rotation = rot
            }
            // Translate so the point under the fingers' midpoint follows them.
            w.x = startCX + (midX - startMidX) - w.width / 2f
            w.y = startCY + (midY - startMidY) - w.height / 2f
        } else if (dragging) {
            val ia = ev.findPointerIndex(idA)
            if (ia < 0) return
            val ax = ev.getX(ia); val ay = ev.getY(ia)
            w.x += ax - lastAX; w.y += ay - lastAY
            lastAX = ax; lastAY = ay
        }
    }

    fun onPointerUp(ev: MotionEvent, pointerId: Int) {
        if (pressId == pointerId) { pressCandidate = null; main.removeCallbacks(longPress) }
        val w = target ?: return
        if (pinching && (pointerId == idA || pointerId == idB)) {
            // Bake scale into layout so chrome text re-renders crisply.
            bakeScale(w)
            pinching = false
            val remain = if (pointerId == idA) idB else idA
            val ri = ev.findPointerIndex(remain)
            if (ri >= 0) { beginDrag(ev, remain, w) } else resetGesture()
        } else if (dragging && pointerId == idA) {
            clampInside(w)
            resetGesture()
        }
    }

    fun ownsPointer(id: Int) = target != null && (id == idA || id == idB)

    private fun beginDrag(ev: MotionEvent, id: Int, w: SourceWindow) {
        if (w.isFullscreen) return
        target = w; idA = id; idB = -1; dragging = true; pinching = false
        val i = ev.findPointerIndex(id)
        if (i >= 0) { lastAX = ev.getX(i); lastAY = ev.getY(i) }
    }

    private fun beginPinch(ev: MotionEvent, a: Int, b: Int, w: SourceWindow) {
        if (w.isFullscreen) return
        target = w; idA = a; idB = b; dragging = false; pinching = true
        val ia = ev.findPointerIndex(a); val ib = ev.findPointerIndex(b)
        if (ia < 0 || ib < 0) { resetGesture(); return }
        val ax = ev.getX(ia); val ay = ev.getY(ia); val bx = ev.getX(ib); val by = ev.getY(ib)
        startDist = max(hypot(bx - ax, by - ay), 1f)
        startAngle = Math.toDegrees(atan2(by - ay, bx - ax).toDouble()).toFloat()
        startScaleX = w.scaleX
        startRot = w.rotation
        startCX = w.x + w.width / 2f; startCY = w.y + w.height / 2f
        startMidX = (ax + bx) / 2f; startMidY = (ay + by) / 2f
    }

    /** Convert a visual scale into real layout size (keeps the centre fixed). */
    private fun bakeScale(w: SourceWindow) {
        val s = w.scaleX
        if (abs(s - 1f) < 0.001f) return
        val cx = w.x + w.width / 2f; val cy = w.y + w.height / 2f
        val lp = w.layoutParams as LayoutParams
        lp.width = (w.width * s).toInt().coerceAtLeast(dpPx(160f).toInt())
        lp.height = (lp.width / w.aspect).toInt()
        w.layoutParams = lp
        w.scaleX = 1f; w.scaleY = 1f
        // Position from the new size so the centre does not jump.
        w.x = cx - lp.width / 2f; w.y = cy - lp.height / 2f
        clampInside(w)
    }

    private fun minScale(w: SourceWindow) = dpPx(160f) / max(w.width, 1)
    private fun maxScale(w: SourceWindow) = (width * 1.2f) / max(w.width, 1)

    private fun resetGesture() {
        target = null; idA = -1; idB = -1; dragging = false; pinching = false; pinchStoleFirst = -1
    }

    fun cancelGestures() {
        resetGesture(); pressCandidate = null; main.removeCallbacks(longPress)
    }

    /**
     * Called by BoardView glue after a long-press promotes a pointer to a
     * drag. No MotionEvent is available inside a delayed Runnable, so the
     * last known pointer position is supplied instead.
     */
    fun pickUp(pointerId: Int, w: SourceWindow, lastX: Float, lastY: Float) {
        if (w.isFullscreen) return
        bringToFront(w)
        target = w; idA = pointerId; idB = -1; dragging = true; pinching = false
        lastAX = lastX; lastAY = lastY
        w.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showChromeBriefly()
    }

    fun hasPressCandidate() = pressCandidate != null

    /** Screen-space test for a strip button, used to yield the gesture to it. */
    fun isOnButton(x: Float, y: Float): Boolean {
        val w = windowAt(x, y, localPt) ?: return false
        return w.isOnButton(localPt.x, localPt.y)
    }

    private fun showChromeBriefly() {
        windows.forEach { it.setChromeVisible(true) }
        main.removeCallbacks(hideChrome)
        main.postDelayed(hideChrome, chromeHideDelay)
    }

    private fun dpPx(v: Float) = v * resources.displayMetrics.density

    /**
     * The layer itself never intercepts touches - BoardView on top receives
     * them all and forwards to us through claim/onPointerMove/onPointerUp.
     * Button clicks are the exception: BoardView returns false from claim()
     * for those and dispatches the event down to us so the ImageButton gets
     * a normal click.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
}
