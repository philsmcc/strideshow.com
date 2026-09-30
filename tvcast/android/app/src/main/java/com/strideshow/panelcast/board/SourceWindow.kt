package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.strideshow.panelcast.R
import org.webrtc.EglBase

/**
 * One floating window showing a live source (phone camera or screen share).
 *
 * Structure:
 *   FrameLayout (rounded, elevated, clipped)
 *     TextureViewRenderer   - the video
 *     title strip (top)     - label + role icon + close/fullscreen/mute buttons
 *     status overlay        - spinner + text until the first frame arrives
 *
 * Position/scale/rotation are applied via the standard View transform
 * properties, driven by `SourceWindowLayer`. The window keeps its *unscaled*
 * layout size and is scaled visually, which keeps the TextureView's buffer
 * size stable during a pinch (re-laying-out a video surface 60 times a
 * second would stutter). On gesture end the layer "bakes" the scale into
 * real layout size so text in the title strip stays crisp.
 */
class SourceWindow(
    context: Context,
    val peerId: String,
    val role: String,
    label: String,
    egl: EglBase.Context,
) : FrameLayout(context) {

    val renderer = TextureViewRenderer(context)
    private val title: TextView
    private val status: TextView
    private val spinner: ProgressBar
    private val statusWrap: LinearLayout
    private val chrome: LinearLayout
    val btnClose: ImageButton
    val btnFull: ImageButton
    val btnMute: ImageButton
    val btnInfo: TextView

    /** Aspect ratio of the incoming video; 16:9 until the first frame. */
    var aspect = 16f / 9f
        private set
    var isFullscreen = false
    var muted = false
        set(v) { field = v; btnMute.alpha = if (v) 0.45f else 1f; btnMute.contentDescription = if (v) "Unmute" else "Mute" }

    /** Saved geometry to restore after fullscreen. */
    var savedX = 0f; var savedY = 0f; var savedW = 0; var savedH = 0; var savedRot = 0f

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    init {
        // Rounded card look. Corners clip the video too via the outline.
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(Color.BLACK)
            setStroke(dp(1.5f).toInt(), 0x66FFFFFF)
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(14f))
            }
        }
        elevation = dp(10f)
        isClickable = true
        isFocusable = false

        renderer.init(egl, null)
        renderer.onFrameSize = { w, h -> onFrameSize(w, h) }
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // ---- title strip ----
        chrome = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(12f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        }
        val icon = TextView(context).apply {
            text = if (role == "screen") "\uD83D\uDDA5" else "\uD83D\uDCF7" // 🖥 / 📷
            textSize = 15f
            setPadding(0, 0, dp(8f).toInt(), 0)
        }
        title = TextView(context).apply {
            text = label.ifBlank { if (role == "screen") "Screen share" else "Camera" }
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        btnInfo = TextView(context).apply {
            setTextColor(0xAAFFFFFF.toInt()); textSize = 11f; maxLines = 1
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
            typeface = android.graphics.Typeface.MONOSPACE
            visibility = GONE
        }
        fun iconButton(res: Int, desc: String) = ImageButton(context).apply {
            setImageResource(res)
            contentDescription = desc
            background = null
            setColorFilter(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val s = dp(40f).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
        }
        btnMute = iconButton(R.drawable.ic_volume, "Mute")
        btnFull = iconButton(R.drawable.ic_fullscreen, "Fullscreen")
        btnClose = iconButton(R.drawable.ic_close, "Remove source")
        chrome.addView(icon)
        chrome.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chrome.addView(btnInfo)
        chrome.addView(btnMute)
        chrome.addView(btnFull)
        chrome.addView(btnClose)
        addView(chrome, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // ---- connecting overlay ----
        statusWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC0B0F17.toInt())
        }
        spinner = ProgressBar(context).apply { isIndeterminate = true }
        status = TextView(context).apply {
            text = "Connecting\u2026"; setTextColor(0xCCFFFFFF.toInt()); textSize = 16f
            setPadding(0, dp(12f).toInt(), 0, 0); gravity = Gravity.CENTER
        }
        statusWrap.addView(spinner, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()))
        statusWrap.addView(status)
        addView(statusWrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Only audio from one source at a time is sensible; default muted
        // for cameras (a phone's mic would be a feedback hazard anyway) and
        // unmuted for screen shares, which is where the audio content is.
        muted = role != "screen"
    }

    /** Height of the drag strip, so the layer knows where a title-drag begins. */
    val chromeHeight: Int get() = chrome.height

    fun setStatus(text: String) { status.text = text; statusWrap.visibility = VISIBLE }

    /** Called when the first frame is decoded: reveal the video. */
    fun onFirstFrame() {
        statusWrap.animate().alpha(0f).setDuration(220).withEndAction { statusWrap.visibility = GONE; statusWrap.alpha = 1f }.start()
    }

    fun showEnded(reason: String) {
        renderer.clearImage()
        spinner.visibility = GONE
        setStatus(reason)
    }

    /** Show/hide the small stats readout in the strip. */
    fun setInfo(text: String?) {
        if (text == null) { btnInfo.visibility = GONE } else { btnInfo.visibility = VISIBLE; btnInfo.text = text }
    }

    /** Chrome auto-hides when idle so the content is not always framed. */
    fun setChromeVisible(visible: Boolean) {
        chrome.animate().alpha(if (visible) 1f else 0f).setDuration(180).start()
    }

    private fun onFrameSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val a = w.toFloat() / h
        if (kotlin.math.abs(a - aspect) < 0.01f) return
        aspect = a
        renderer.setFitAspect(w, h)
        // Re-fit height to the new aspect while keeping width; the layer
        // listens and clamps to the screen.
        (parent as? SourceWindowLayer)?.onWindowAspectChanged(this)
    }

    /** Whether a point in this view's local coordinates is on the title strip. */
    fun isOnChrome(localX: Float, localY: Float): Boolean =
        chrome.alpha > 0.5f && localY >= 0 && localY < chrome.height && localX >= 0 && localX < width

    /** Is the local point over one of the strip's buttons? Those get click, not drag. */
    fun isOnButton(localX: Float, localY: Float): Boolean {
        if (!isOnChrome(localX, localY)) return false
        for (b in listOf(btnMute, btnFull, btnClose)) {
            if (b.visibility != VISIBLE) continue
            val l = chrome.left + b.left; val t = chrome.top + b.top
            if (localX >= l && localX < l + b.width && localY >= t && localY < t + b.height) return true
        }
        return false
    }

    fun release() {
        renderer.release()
    }
}
