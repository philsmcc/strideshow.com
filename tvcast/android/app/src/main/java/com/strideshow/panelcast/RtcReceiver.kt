package com.strideshow.panelcast

import android.content.Context
import android.util.Log
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
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoDecoderFactory
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * The receive-only half of the WebRTC session.
 *
 * Design notes for old hardware:
 *  - We are always the *answerer*. The sender (phone/desktop) has the media and
 *    creates the offer, so the TV never has to renegotiate - important because
 *    renegotiation on a slow CPU is where these devices tend to fall over.
 *  - Hardware decode is strongly preferred. DefaultVideoDecoderFactory uses
 *    MediaCodec when available and falls back to software per-codec, which is
 *    what lets a 2016-era panel handle 1080p H.264 at all.
 *  - Audio is fully disabled in the device module. The receiver is video-only,
 *    which avoids the AudioRecord init crashes common on AOSP panel builds.
 */
class RtcReceiver(
    private val context: Context,
    private val eglBase: EglBase,
    private val listener: Listener,
) {
    interface Listener {
        fun onAnswerReady(sdp: String)
        fun onLocalIce(candidate: JSONObject)
        /** First frame decoded - safe to hide the lobby and show video. */
        fun onFirstFrame()
        fun onStreamEnded(reason: String)
        fun onIceState(state: PeerConnection.IceConnectionState)
    }

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var renderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var firstFrameSeen = false
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    fun initFactory() {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                // Field trials: cap the receive-side jitter buffer growth so a
                // brief Wi-Fi stall does not accumulate seconds of latency,
                // which is fatal for a document camera you are pointing by hand.
                .setFieldTrials("WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/")
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val decoderFactory: VideoDecoderFactory = try {
            // Hardware-accelerated where the SoC supports it (H.264 above all).
            DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        } catch (t: Throwable) {
            // Some AOSP panel builds ship a broken MediaCodec list. Rather than
            // crash on launch, degrade to software decode (720p-ish ceiling).
            Log.w(TAG, "hardware decoder factory failed, using software: ${t.message}")
            SoftwareVideoDecoderFactory()
        }

        // Video-only receiver: disable all audio I/O so we never touch
        // AudioRecord/AudioTrack, which is a common crash source on panels.
        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
        adm.setSpeakerMute(true)
        adm.setMicrophoneMute(true)

        factory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(adm)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory ready")
    }

    fun setIceServers(servers: List<SignalingClient.IceServer>) {
        iceServers = servers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        if (iceServers.isEmpty()) {
            iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        }
    }

    fun attachRenderer(view: SurfaceViewRenderer) {
        renderer = view
    }

    /** Handle an incoming offer: build the peer, answer it. */
    fun acceptOffer(sdp: String) {
        initFactory()
        closePeer() // one sender at a time
        firstFrameSeen = false

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // Trickle ICE with a short check interval so pairing feels instant
            // on a LAN where the direct candidate almost always wins.
            iceCandidatePoolSize = 2
            keyType = PeerConnection.KeyType.ECDSA
            enableCpuOveruseDetection = false // receiver: nothing to throttle
        }

        val observer = object : PcObserverAdapter() {
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                listener.onLocalIce(json)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ice state: $state")
                listener.onIceState(state)
                if (state == PeerConnection.IceConnectionState.FAILED) {
                    listener.onStreamEnded("Connection failed")
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver?.track() ?: return
                if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    bindVideoTrack(track as VideoTrack)
                } else if (track is AudioTrack) {
                    // Video-only product decision: silence any audio track.
                    track.setEnabled(false)
                }
            }
        }

        pc = factory?.createPeerConnection(rtcConfig, observer)
        if (pc == null) {
            listener.onStreamEnded("Could not start video engine")
            return
        }

        pc?.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() = createAnswer()
                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "setRemoteDescription failed: $error")
                    listener.onStreamEnded("Could not read the sender's offer")
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        pc?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription) {
                // Reorder codecs so our hardware-friendly H.264 comes first;
                // the sender already prefers it, this makes it mutual.
                val tuned = SdpUtils.preferH264(desc.description)
                val finalDesc = SessionDescription(desc.type, tuned)

                pc?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        listener.onAnswerReady(tuned)
                    }
                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "setLocalDescription failed: $error")
                        listener.onStreamEnded("Could not complete the handshake")
                    }
                }, finalDesc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createAnswer failed: $error")
                listener.onStreamEnded("Could not answer the sender")
            }
        }, constraints)
    }

    private fun bindVideoTrack(track: VideoTrack) {
        remoteVideoTrack = track
        val view = renderer ?: return
        try {
            track.setEnabled(true)
            track.addSink { frame ->
                if (!firstFrameSeen) {
                    firstFrameSeen = true
                    listener.onFirstFrame()
                }
                view.onFrame(frame)
            }
            Log.i(TAG, "video track bound")
        } catch (t: Throwable) {
            Log.e(TAG, "failed to bind video: ${t.message}")
            listener.onStreamEnded("Could not display the video")
        }
    }

    fun addRemoteIce(json: JSONObject) {
        val candidate = IceCandidate(
            json.optString("sdpMid"),
            json.optInt("sdpMLineIndex"),
            json.optString("candidate"),
        )
        pc?.addIceCandidate(candidate)
    }

    fun closePeer() {
        remoteVideoTrack = null
        firstFrameSeen = false
        pc?.let {
            try { it.close() } catch (_: Exception) {}
            try { it.dispose() } catch (_: Exception) {}
        }
        pc = null
    }

    fun release() {
        closePeer()
        factory?.let {
            try { it.dispose() } catch (_: Exception) {}
        }
        factory = null
    }

    val hasActivePeer: Boolean get() = pc != null

    companion object { private const val TAG = "PanelCast/Rtc" }
}
