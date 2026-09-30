package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.strideshow.panelcast.QrGen

/**
 * The "+" sheet: how to connect a phone camera or a screen share.
 *
 * Replaces the old full-screen lobby. Because the board is always usable,
 * the pairing details live in a dismissible card instead of taking over the
 * display. It stays open while sources connect so a class can scan one
 * after another; each successful join briefly flashes the card.
 */
class AddSourceSheet(context: Context) : FrameLayout(context) {

    private val card: LinearLayout
    private val qr: ImageView
    private val codeText: TextView
    private val urlText: TextView
    private val statusText: TextView
    private val countText: TextView
    private val dm = resources.displayMetrics
    private val shortSide = minOf(dm.widthPixels, dm.heightPixels).toFloat()
    private val longSide = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
    /** Card width: most of the screen, but never so wide the text runs off. */
    private val cardW = (longSide * 0.72f).toInt()
    private val cardPad = (shortSide * 0.035f).toInt()
    /**
     * QR size: was 34% of the short side, which on a 16:9 panel left the
     * text column no room. Now a quarter of the card's width, so the
     * instructions always get at least half the card.
     */
    private val qrSize = (cardW * 0.28f).toInt()

    var onDismiss: (() -> Unit)? = null

    private fun sp(fraction: Float) = shortSide * fraction / dm.scaledDensity
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)

    init {
        // Dim scrim; tap outside the card to close.
        setBackgroundColor(0x99000000.toInt())
        setOnClickListener { hide() }

        card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24f); setColor(0xFF151B26.toInt()); setStroke(dp(1f).toInt(), 0x33FFFFFF)
            }
            elevation = dp(20f)
            setPadding(cardPad, cardPad, cardPad, cardPad)
            isClickable = true // swallow taps so they do not reach the scrim
        }

        // Left: QR on a white plate (QR needs a light quiet zone on a dark UI).
        val plate = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(Color.WHITE) }
            val p = (qrSize * 0.06f).toInt(); setPadding(p, p, p, p)
        }
        qr = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        plate.addView(qr, LayoutParams(qrSize, qrSize))
        card.addView(plate, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Right: instructions. weight=1 so this column owns whatever width
        // the QR does not, and text wraps inside it instead of clipping.
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((shortSide * 0.04f).toInt(), 0, 0, 0)
        }
        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        col.addView(text("Add a source", sp(0.034f), Color.WHITE, true))
        col.addView(text("Scan with a phone to send its camera,\nor open the address on a computer to share a screen.", sp(0.018f), 0xFF93A1B5.toInt()).apply {
            setPadding(0, (shortSide * 0.012f).toInt(), 0, (shortSide * 0.03f).toInt())
        })
        col.addView(text("On a computer, go to", sp(0.016f), 0xFF93A1B5.toInt()))
        urlText = text("\u2026", sp(0.024f), 0xFF4DA3FF.toInt(), true).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, (shortSide * 0.02f).toInt())
            // Long addresses wrap rather than vanish off the card edge.
            maxLines = 2
        }
        col.addView(urlText)
        col.addView(text("and enter this code", sp(0.016f), 0xFF93A1B5.toInt()))
        codeText = text("\u00B7\u00B7\u00B7 \u00B7\u00B7\u00B7", sp(0.055f), Color.WHITE, true).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.12f
            maxLines = 1
        }
        col.addView(codeText)
        statusText = text("Connecting to server\u2026", sp(0.016f), 0xFF93A1B5.toInt()).apply {
            setPadding(0, (shortSide * 0.02f).toInt(), 0, 0)
        }
        col.addView(statusText)
        countText = text("", sp(0.016f), 0xFF3DDC84.toInt())
        col.addView(countText)

        col.addView(TextView(context).apply {
            text = "Close"; textSize = sp(0.018f); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(0x22FFFFFF) }
            setPadding((shortSide * 0.04f).toInt(), (shortSide * 0.012f).toInt(), (shortSide * 0.04f).toInt(), (shortSide * 0.012f).toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (shortSide * 0.03f).toInt()
            }
            setOnClickListener { hide() }
        })
        card.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(card, LayoutParams(cardW, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        visibility = GONE
        alpha = 0f
    }

    fun setRoom(code: String, joinUrl: String) {
        codeText.text = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code
        urlText.text = joinUrl.substringAfter("://").removeSuffix("/").replace(Regex("/s/[A-Za-z0-9]{4,12}$"), "")
        val bmp: Bitmap? = QrGen.encode(joinUrl, qrSize)
        if (bmp != null) qr.setImageBitmap(bmp)
        statusText.text = "Ready \u2014 waiting for a connection"
    }

    fun setStatus(text: String) { statusText.text = text }

    fun setSourceCount(n: Int, max: Int) {
        countText.text = when (n) {
            0 -> ""
            1 -> "1 source connected"
            else -> "$n sources connected"
        } + if (n >= max) " \u2014 room is full" else ""
    }

    fun setConnecting() {
        codeText.text = "\u00B7\u00B7\u00B7 \u00B7\u00B7\u00B7"
        statusText.text = "Connecting to server\u2026"
    }

    fun show() {
        if (visibility == VISIBLE) return
        visibility = VISIBLE
        card.scaleX = 0.94f; card.scaleY = 0.94f
        animate().alpha(1f).setDuration(160).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
    }

    fun hide() {
        if (visibility != VISIBLE) return
        animate().alpha(0f).setDuration(140).withEndAction { visibility = GONE; onDismiss?.invoke() }.start()
    }

    val isShowing get() = visibility == VISIBLE

    /** Brief green flash on the card when a source joins. */
    fun flash() {
        val bg = card.background as GradientDrawable
        bg.setStroke(dp(2f).toInt(), 0xFF3DDC84.toInt())
        postDelayed({ bg.setStroke(dp(1f).toInt(), 0x33FFFFFF) }, 700)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        return super.onTouchEvent(event)
    }
}
