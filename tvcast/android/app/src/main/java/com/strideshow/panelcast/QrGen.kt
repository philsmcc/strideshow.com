package com.strideshow.panelcast

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR generation for the lobby screen.
 *
 * Tuned for scanning from across a room: high error correction (survives glare
 * and off-axis angles on a glossy panel) and a quiet zone, rendered as crisp
 * unscaled blocks so the camera sees hard edges.
 */
object QrGen {

    /**
     * @param content the URL to encode
     * @param sizePx  target square size in pixels
     */
    fun encode(content: String, sizePx: Int): Bitmap? {
        if (content.isEmpty() || sizePx <= 0) return null

        val hints = mapOf(
            // Level H tolerates ~30% damage. Costs density but our payload is a
            // short URL, so the module count stays low and readable.
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // 2-module quiet zone; the layout adds white padding around it too.
            EncodeHintType.MARGIN to 2,
        )

        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (t: Throwable) {
            // A QR failure must never take down the lobby: the numeric code is
            // still shown, so the user can type it manually.
            null
        }
    }
}
