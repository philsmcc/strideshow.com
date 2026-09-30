package com.strideshow.panelcast.board

import android.content.Context
import android.util.Log
import com.strideshow.panelcast.PcObserverAdapter
import com.strideshow.panelcast.SdpObserverAdapter
import com.strideshow.panelcast.SdpUtils
import com.strideshow.panelcast.SignalingClient
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One PeerConnection per live source.
 *
 * The original receiver held exactly one peer. The whiteboard shows many at
 * once, so each source gets its own `SourceSession`, all sharing a single
 * `PeerConnectionFactory` (holding one per peer would multiply the audio
 * device module and thread pools for no benefit). The panel is still the
 * answerer for every source: senders own the media and create offers.
 */
class SourceSession(
    val peerId: String,
    val role: String,
    val label: String,
    private val engine: RtcEngine,
    private val listener: Listener,
) {
    interface Listener {
        fun onAnswerReady(peerId: String, sdp: String)
        fun onLocalIce(peerId: String, candidate: JSONObject)
        fun onFirstFrame(peerId: String)
        fun onEnded(peerId: String, reason: String)
        fun onIceState(peerId: String, state: PeerConnection.IceConnectionState)
    }

    private var pc: PeerConnection? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null
    private var sink: VideoSink? = null
    private var firstFrameSeen = false
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false

    /** Attach the window's renderer. Safe before or after the track arrives. */
    fun attachSink(s: VideoSink) {
        sink?.let { old -> videoTrack?.let { t -> try { t.removeSink(old) } catch (_: Exception) {} } }
        sink = s
        videoTrack?.let { t -> try { t.addSink(s) } catch (_: Exception) {} }
    }

    /** Mute/unmute this window's audio (only one window should be audible). */
    fun setAudioEnabled(on: Boolean) {
        try { audioTrack?.setEnabled(on) } catch (_: Exception) {}
    }

    fun acceptOffer(sdp: String) {
        closePeer()
        firstFrameSeen = false
        remoteSet = false

        val cfg = PeerConnection.RTCConfiguration(engine.iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
            keyType = PeerConnection.KeyType.ECDSA
            enableCpuOveruseDetection = false
        }

        val observer = object : PcObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onLocalIce(peerId, JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex))
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                listener.onIceState(peerId, state)
                if (state == PeerConnection.IceConnectionState.FAILED) listener.onEnded(peerId, "Connection failed")
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver?.track() ?: return
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) bindVideo(track as VideoTrack)
                else if (track is AudioTrack) {
                    audioTrack = track
                    track.setEnabled(true)
                    try { track.setVolume(1.0) } catch (_: Exception) {}
                }
            }
        }

        pc = engine.factory.createPeerConnection(cfg, observer)
        if (pc == null) { listener.onEnded(peerId, "Could not start video engine"); return }

        pc?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                remoteSet = true
                // Flush candidates that raced ahead of the offer.
                synchronized(pendingIce) { pendingIce.forEach { pc?.addIceCandidate(it) }; pendingIce.clear() }
                createAnswer()
            }
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "[$peerId] setRemoteDescription: $error")
                listener.onEnded(peerId, "Could not read the sender's offer")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                val tuned = if (engine.hasH264Decoder) SdpUtils.preferH264(desc.description) else desc.description
                pc?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() = listener.onAnswerReady(peerId, tuned)
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "[$peerId] setLocalDescription: $error")
                        listener.onEnded(peerId, "Could not complete the handshake")
                    }
                }, SessionDescription(desc.type, tuned))
            }
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "[$peerId] createAnswer: $error")
                listener.onEnded(peerId, "Could not answer the sender")
            }
        }, constraints)
    }

    private fun bindVideo(track: VideoTrack) {
        videoTrack = track
        track.setEnabled(true)
        sink?.let { try { track.addSink(it) } catch (_: Exception) {} }
        track.addSink {
            if (!firstFrameSeen) { firstFrameSeen = true; listener.onFirstFrame(peerId) }
        }
    }

    fun addRemoteIce(json: JSONObject) {
        val c = IceCandidate(json.optString("sdpMid"), json.optInt("sdpMLineIndex"), json.optString("candidate"))
        if (remoteSet) pc?.addIceCandidate(c) else synchronized(pendingIce) { pendingIce.add(c) }
    }

    /**
     * Receive-side stats for the window's info badge.
     *
     * The three counters that separate the possible bottlenecks:
     *  - packetsLost rising      -> the network is dropping packets
     *  - framesDropped rising    -> frames arrived but the DECODER could not
     *                               keep up (or arrived too late to render)
     *  - jitterBufferDelay high  -> arrival timing is erratic; the receiver
     *                               is buffering to smooth it, adding latency
     */
    data class Stats(
        val width: Int, val height: Int, val fps: Double, val kbps: Int, val codec: String,
        val decoder: String,
        /** Frames dropped by this receiver in the last poll interval. */
        val droppedDelta: Long,
        /** Packets lost on the network in the last poll interval. */
        val lostDelta: Long,
        /** Average jitter-buffer delay per frame, ms. */
        val jitterMs: Int,
        val hwDecoder: Boolean,
    )

    fun pollStats(cb: (Stats) -> Unit) {
        val peer = pc ?: return
        peer.getStats { report ->
            var w = 0; var h = 0; var fps = 0.0; var bytes = 0L; var codecId: String? = null
            var dropped = 0L; var lost = 0L; var jbDelay = 0.0; var jbCount = 0L; var impl = "?"
            for (st in report.statsMap.values) {
                if (st.type == "inbound-rtp" && st.members["kind"] == "video") {
                    val m = st.members
                    w = (m["frameWidth"] as? Number)?.toInt() ?: w
                    h = (m["frameHeight"] as? Number)?.toInt() ?: h
                    fps = (m["framesPerSecond"] as? Number)?.toDouble() ?: fps
                    bytes = (m["bytesReceived"] as? Number)?.toLong() ?: bytes
                    dropped = (m["framesDropped"] as? Number)?.toLong() ?: dropped
                    lost = (m["packetsLost"] as? Number)?.toLong() ?: lost
                    jbDelay = (m["jitterBufferDelay"] as? Number)?.toDouble() ?: jbDelay
                    jbCount = (m["jitterBufferEmittedCount"] as? Number)?.toLong() ?: jbCount
                    impl = (m["decoderImplementation"] as? String) ?: impl
                    codecId = m["codecId"] as? String
                }
            }
            val codec = codecId?.let { id -> (report.statsMap[id]?.members?.get("mimeType") as? String) }
                ?.removePrefix("video/") ?: "?"
            val now = System.currentTimeMillis()
            var kbps = 0
            if (lastStatsAt > 0 && now > lastStatsAt) kbps = ((bytes - lastBytes) * 8 / (now - lastStatsAt)).toInt()
            val droppedDelta = if (lastStatsAt > 0) dropped - lastDropped else 0L
            val lostDelta = if (lastStatsAt > 0) lost - lastLost else 0L
            val jitterMs = if (jbCount > lastJbCount) (((jbDelay - lastJbDelay) / (jbCount - lastJbCount)) * 1000).toInt() else 0
            lastStatsAt = now; lastBytes = bytes; lastDropped = dropped; lastLost = lost
            lastJbDelay = jbDelay; lastJbCount = jbCount
            // libwebrtc names software decoders 'libvpx', 'FFmpeg', 'OpenH264';
            // MediaCodec hardware decoders carry the SoC vendor's name.
            val hw = !Regex("libvpx|ffmpeg|openh264|dav1d|libaom|unknown", RegexOption.IGNORE_CASE).containsMatchIn(impl)
            cb(Stats(w, h, fps, kbps, codec, impl, droppedDelta, lostDelta, jitterMs, hw))
        }
    }
    private var lastStatsAt = 0L
    private var lastBytes = 0L
    private var lastDropped = 0L
    private var lastLost = 0L
    private var lastJbDelay = 0.0
    private var lastJbCount = 0L

    fun closePeer() {
        videoTrack?.let { t -> sink?.let { s -> try { t.removeSink(s) } catch (_: Exception) {} } }
        videoTrack = null
        audioTrack = null
        pc?.let { try { it.close() } catch (_: Exception) {}; try { it.dispose() } catch (_: Exception) {} }
        pc = null
    }

    companion object { private const val TAG = "PanelCast/Source" }
}

/**
 * Shared WebRTC engine: one factory, one audio module, one decoder
 * enumeration, for every source on the board.
 */
class RtcEngine(private val context: Context, private val eglBase: EglBase) {
    lateinit var factory: PeerConnectionFactory
        private set
    var iceServers: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )
    var hasH264Decoder = false
        private set
    var decoderNames: List<String> = emptyList()
        private set
    private var adm: JavaAudioDeviceModule? = null
    private var ready = false

    fun init() {
        if (ready) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/")
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )
        val decoderFactory: VideoDecoderFactory = try {
            DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        } catch (t: Throwable) {
            Log.w(TAG, "hardware decoder factory failed: ${t.message}")
            SoftwareVideoDecoderFactory()
        }
        try {
            val codecs = decoderFactory.supportedCodecs
            decoderNames = codecs.map { it.name }.distinct()
            hasH264Decoder = codecs.any { it.name.equals("H264", ignoreCase = true) }
        } catch (_: Throwable) {}

        // Playback only; capture permanently off (see RtcReceiver for why).
        val module = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()
        module.setSpeakerMute(false)
        module.setMicrophoneMute(true)
        adm = module

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(module)
            .createPeerConnectionFactory()
        ready = true
        Log.i(TAG, "engine ready; decoders=${decoderNames.joinToString("/")}")
    }

    fun applyIceServers(servers: List<SignalingClient.IceServer>) {
        val list = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        if (list.isNotEmpty()) iceServers = list
    }

    fun release() {
        if (!ready) return
        ready = false
        adm?.let { try { it.release() } catch (_: Exception) {} }
        adm = null
        try { factory.dispose() } catch (_: Exception) {}
    }

    companion object { private const val TAG = "PanelCast/Engine" }
}
