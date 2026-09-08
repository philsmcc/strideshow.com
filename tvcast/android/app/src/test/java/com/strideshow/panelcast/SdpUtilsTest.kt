package com.strideshow.panelcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the H.264 codec preference rewriting.
 *
 * This logic is the single most important quality lever in the app: if the
 * session negotiates VP8 at 1080p, an old panel decodes in software and the
 * picture collapses to a few frames per second. These tests lock in the
 * ordering guarantees and make sure we never corrupt an SDP while doing it.
 */
class SdpUtilsTest {

    /** A realistic Chrome offer: VP8 first, two H.264 profiles later. */
    private val offer = listOf(
        "v=0",
        "o=- 1 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "m=video 9 UDP/TLS/RTP/SAVPF 96 97 102 103 108",
        "c=IN IP4 0.0.0.0",
        "a=rtpmap:96 VP8/90000",
        "a=rtpmap:97 rtx/90000",
        "a=rtpmap:102 H264/90000",
        "a=fmtp:102 profile-level-id=42001f;packetization-mode=0",
        "a=rtpmap:103 H264/90000",
        "a=fmtp:103 profile-level-id=42e01f;packetization-mode=1",
        "a=rtpmap:108 VP9/90000",
    ).joinToString("\r\n")

    private fun videoPayloads(sdp: String): List<String> =
        sdp.split("\r\n").first { it.startsWith("m=video") }.split(" ").drop(3)

    @Test
    fun `h264 is promoted to first payload`() {
        val pts = videoPayloads(SdpUtils.preferH264(offer))
        assertTrue("expected H.264 (102/103) first but got ${pts[0]}", pts[0] == "103" || pts[0] == "102")
    }

    @Test
    fun `packetization mode 1 is preferred over mode 0`() {
        // Mode 1 permits fragmented NAL units, which hardware decoders on old
        // SoCs handle far more consistently than mode 0.
        val pts = videoPayloads(SdpUtils.preferH264(offer))
        assertTrue("103 (mode 1) should precede 102 (mode 0): $pts",
            pts.indexOf("103") < pts.indexOf("102"))
    }

    @Test
    fun `vp8 is demoted below h264`() {
        val pts = videoPayloads(SdpUtils.preferH264(offer))
        assertTrue("VP8 should rank below H.264: $pts", pts.indexOf("96") > pts.indexOf("103"))
    }

    @Test
    fun `no payload types are lost or duplicated`() {
        val pts = videoPayloads(SdpUtils.preferH264(offer))
        assertEquals(listOf("96", "97", "102", "103", "108").sorted(), pts.sorted())
        assertEquals("duplicates found: $pts", pts.size, pts.distinct().size)
    }

    @Test
    fun `attribute lines are left intact`() {
        val out = SdpUtils.preferH264(offer)
        assertTrue(out.contains("a=rtpmap:102 H264/90000"))
        assertTrue(out.contains("a=rtpmap:96 VP8/90000"))
        assertTrue(out.contains("a=fmtp:103 profile-level-id=42e01f;packetization-mode=1"))
    }

    @Test
    fun `output uses crlf line endings`() {
        // Some SDP parsers are strict about CRLF; a bare LF can break them.
        assertTrue(SdpUtils.preferH264(offer).contains("\r\n"))
    }

    @Test
    fun `audio only sdp passes through unchanged`() {
        val audio = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2"
        assertEquals(audio, SdpUtils.preferH264(audio))
    }

    @Test
    fun `sdp without h264 passes through unchanged`() {
        // A VP8-only sender: nothing to reorder, so don't touch it.
        val vp8 = "v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\nc=IN IP4 0.0.0.0\r\na=rtpmap:96 VP8/90000"
        assertEquals(vp8, SdpUtils.preferH264(vp8))
    }

    @Test
    fun `rewriting is idempotent`() {
        val once = SdpUtils.preferH264(offer)
        assertEquals(once, SdpUtils.preferH264(once))
    }

    @Test
    fun `malformed m line does not throw`() {
        val malformed = "v=0\r\nm=video 9\r\na=rtpmap:102 H264/90000"
        // Must degrade gracefully rather than crash the receiver.
        SdpUtils.preferH264(malformed)
    }

    @Test
    fun `handles lf only input`() {
        val lf = offer.replace("\r\n", "\n")
        val pts = videoPayloads(SdpUtils.preferH264(lf))
        assertTrue(pts[0] == "103" || pts[0] == "102")
    }
}
