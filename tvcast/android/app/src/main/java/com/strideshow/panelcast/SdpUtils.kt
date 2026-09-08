package com.strideshow.panelcast

/**
 * SDP rewriting for the receiver side.
 *
 * Mirrors web/js/sdp.js. The goal is to make H.264 the negotiated codec,
 * because it is the only format these older SoCs decode in hardware. If we let
 * WebRTC default to VP8, a 1080p stream lands in the software decoder and the
 * panel drops to a few frames per second.
 */
object SdpUtils {

    /** Move H.264 payload types to the front of the video m-line. */
    fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n", "\n").toMutableList()
        val mIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (mIndex == -1) return sdp

        val rtpmap = Regex("""^a=rtpmap:(\d+)\s+H264/90000""", RegexOption.IGNORE_CASE)
        val h264 = lines.mapNotNull { rtpmap.find(it)?.groupValues?.get(1) }
        if (h264.isEmpty()) return sdp

        // Prefer packetization-mode=1: it permits fragmented NAL units, which
        // hardware decoders handle far more consistently than mode 0.
        val fmtp = Regex("""^a=fmtp:(\d+)\s+(.*)$""")
        val modeOne = lines.mapNotNull { line ->
            fmtp.find(line)?.let { m ->
                val pt = m.groupValues[1]
                if (pt in h264 && m.groupValues[2].contains("packetization-mode=1")) pt else null
            }
        }.toSet()

        val ordered = h264.filter { it in modeOne } + h264.filter { it !in modeOne }

        val parts = lines[mIndex].split(" ")
        if (parts.size <= 3) return sdp
        val header = parts.take(3)
        val payloads = parts.drop(3)
        val rest = payloads.filter { it !in ordered }
        lines[mIndex] = (header + ordered + rest).joinToString(" ")

        return lines.joinToString("\r\n")
    }
}
