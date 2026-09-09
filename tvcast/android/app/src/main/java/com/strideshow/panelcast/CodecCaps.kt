package com.strideshow.panelcast

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * Queries the platform's real decoder capabilities via MediaCodecList.
 *
 * WebRTC's own VideoDecoderFactory only reports *which* codecs exist, not the
 * resolutions they can handle. That distinction matters here: advertising 4K
 * to a sender when the panel tops out at 1080p would put us straight back to a
 * black screen, which is the failure mode this project has already paid for
 * once. So we ask Android directly and let the receiver tell the sender what
 * it can actually cope with.
 */
object CodecCaps {

    data class Caps(
        /** Largest decodable height for the best available codec. */
        val maxHeight: Int,
        /** True when a hardware decoder can handle >= 2160p. */
        val supports4k: Boolean,
        /** True when a hardware decoder can handle >= 1440p. */
        val supports1440: Boolean,
        val detail: String,
    )

    private const val TAG = "PanelCast/Caps"

    /** MIME types we care about, best-supported first. */
    private val MIMES = listOf("video/avc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9")

    @Volatile private var cached: Caps? = null

    fun query(): Caps {
        cached?.let { return it }

        var best = 0
        val notes = mutableListOf<String>()

        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                // Skip software decoders: they will claim large sizes they
                // cannot actually sustain in real time on these SoCs.
                val name = info.name.lowercase()
                val isSoftware = name.startsWith("omx.google.") ||
                    name.startsWith("c2.android.") ||
                    name.contains(".sw.")

                for (mime in info.supportedTypes) {
                    if (mime.lowercase() !in MIMES) continue
                    val caps = try {
                        info.getCapabilitiesForType(mime)
                    } catch (_: Exception) { continue }
                    val video = caps.videoCapabilities ?: continue

                    val h = video.supportedHeights.upper
                    val w = video.supportedWidths.upper
                    // Report the smaller dimension bound: a decoder that does
                    // 3840x2160 is 4K-capable, one that does 1920x1088 is not.
                    val effective = minOf(h, w * 9 / 16)
                    if (!isSoftware && effective > best) best = effective
                    notes.add("${info.name}:$mime ${w}x$h${if (isSoftware) " (sw)" else ""}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MediaCodecList query failed: ${t.message}")
        }

        // Conservative default when nothing could be determined: assume 1080p,
        // which every device this app targets can handle.
        if (best <= 0) best = 1080

        val caps = Caps(
            maxHeight = best,
            supports4k = best >= 2160,
            supports1440 = best >= 1440,
            detail = notes.joinToString("; "),
        )
        Log.i(TAG, "decode caps: maxHeight=${caps.maxHeight} 4k=${caps.supports4k}")
        Log.d(TAG, "decoder detail: ${caps.detail}")
        cached = caps
        return caps
    }
}
