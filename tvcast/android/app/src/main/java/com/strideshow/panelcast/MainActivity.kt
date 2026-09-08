package com.strideshow.panelcast

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.strideshow.panelcast.databinding.ActivityMainBinding
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon

/**
 * The receiver UI.
 *
 * Two states in one activity:
 *  - LOBBY: big QR code + pairing code, waiting for someone to join
 *  - LIVE:  fullscreen video from the connected sender
 *
 * Kept as a single activity because these panels are slow at activity
 * transitions and the switch needs to feel instant.
 */
class MainActivity : AppCompatActivity(), SignalingClient.Listener, RtcReceiver.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private var eglBase: EglBase? = null
    private var signaling: SignalingClient? = null
    private var rtc: RtcReceiver? = null

    private val main = Handler(Looper.getMainLooper())
    private var isLive = false
    /** Incremented only when the GL renderer has actually drawn a frame. */
    private var glPaintCount = 0
    private var statsTimer: Runnable? = null
    private var currentRoom: String? = null
    private var lastJoinUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // A wall panel must never sleep or dim while showing a pairing code.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        eglBase = EglBase.create()
        setupRenderer()

        rtc = RtcReceiver(this, eglBase!!, this).also {
            it.attachRenderer(binding.videoView)
        }
        installPaintCounter()

        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnRetry.setOnClickListener { restartSignaling() }

        showLobby()
        renderNetworkInfo()
        startSignaling()
    }

    // ---- renderer -----------------------------------------------------------

    private fun setupRenderer() {
        binding.videoView.apply {
            // RendererEvents tells us when a frame is genuinely painted, which
            // is the only reliable signal for swapping the lobby out.
            init(eglBase!!.eglBaseContext, rendererEvents)
            // SCALE_ASPECT_FIT: never crop the sender's content. A cropped
            // slide or document is worse than black bars.
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            setMirror(false)

            // ALWAYS VISIBLE. SurfaceViewRenderer is a SurfaceView, and a
            // SurfaceView only owns a Surface while it is VISIBLE - GONE *and*
            // INVISIBLE both tear it down, so EGL never attaches and frames
            // are dropped with no error. Instead of toggling this view, we
            // leave it visible for the whole session and simply draw the lobby
            // over it (the lobby is declared later in the layout, so it is
            // painted on top). The surface is then alive before the first
            // frame ever arrives.
            visibility = View.VISIBLE

            // Note: EglRenderer.setErrorCallback (GL OOM) is not re-exposed by
            // SurfaceViewRenderer, so GL OOM shows up only in logcat. The
            // paint counter below is our signal that rendering is healthy.
        }
    }

    /**
     * Proof-of-paint counter. addFrameListener's callback runs only after the
     * frame has been drawn on the GL thread, so it is the trustworthy
     * counterpart to the misleadingly-named onFirstFrameRendered().
     * Sampled at 1/30 of frames to keep the bitmap copies cheap.
     */
    private fun installPaintCounter() {
        try {
            binding.videoView.addFrameListener({ _ -> glPaintCount++ }, 1f / 30f)
        } catch (t: Throwable) {
            Log.w(TAG, "could not install paint counter: ${t.message}")
        }
    }

    /** Poll receive-side stats and show them on screen while a peer is live. */
    private fun startStatsOverlay() {
        stopStatsOverlay()
        val tick = object : Runnable {
            override fun run() {
                rtc?.pollStats { st ->
                    main.post {
                        // Reveal the video as soon as frames are genuinely
                        // being decoded. This replaces onFirstFrameRendered as
                        // the trigger: that callback only fires once per
                        // renderer lifetime (the flag is reset only in init),
                        // so on a reconnect it never fired and the lobby stuck.
                        if (!isLive && st.framesDecoded > 0) showLive()

                        val line = getString(
                            R.string.diag_stats,
                            st.width, st.height,
                            st.framesDecoded, st.framesDropped,
                            glPaintCount,
                            st.bytesReceived / 1024,
                            st.codec.removePrefix("video/"),
                            st.decoder,
                        )
                        binding.txtDiag.visibility = View.VISIBLE
                        binding.txtDiag.text = line
                        Log.i(TAG, "stats: $line")
                    }
                }
                main.postDelayed(this, 2000)
            }
        }
        statsTimer = tick
        main.post(tick)
    }

    private fun stopStatsOverlay() {
        statsTimer?.let { main.removeCallbacks(it) }
        statsTimer = null
    }

    /**
     * Fired on the render thread by the EGL renderer.
     */
    private val rendererEvents = object : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {
            // NOTE: despite the name, the WebRTC implementation fires this from
            // updateFrameDimensionsAndReportEvents() *before* the frame is
            // drawn, so it means "a frame reached the renderer", not "a frame
            // was painted". Verified against the 125.6422.07 bytecode. Use it
            // only as a progress signal.
            Log.i(TAG, "frame reached renderer")
        }

        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
            Log.i(TAG, "resolution ${width}x$height rot=$rotation")
            main.post {
                if (prefs.showDiagnostics) {
                    binding.txtDiag.visibility = View.VISIBLE
                    binding.txtDiag.text = getString(R.string.diag_video, width, height, rotation)
                }
            }
        }
    }

    // ---- signaling ----------------------------------------------------------

    private fun startSignaling() {
        val url = prefs.signalingUrl
        binding.txtServer.text = url
        signaling = SignalingClient(url, this).also { it.connect() }
    }

    private fun restartSignaling() {
        signaling?.close()
        signaling = null
        rtc?.closePeer()
        isLive = false
        showLobby()
        binding.txtStatus.text = getString(R.string.status_connecting)
        binding.btnRetry.visibility = View.GONE
        startSignaling()
    }

    override fun onHosted(
        room: String,
        joinUrl: String,
        pcUrl: String,
        iceServers: List<SignalingClient.IceServer>,
    ) {
        currentRoom = room
        lastJoinUrl = joinUrl
        rtc?.setIceServers(iceServers)

        binding.txtCode.text = formatCode(room)
        binding.txtStatus.text = getString(R.string.status_ready)
        binding.btnRetry.visibility = View.GONE

        // Render the QR once the view has been measured, so we know the size.
        binding.imgQr.post {
            val size = binding.imgQr.width.takeIf { it > 0 } ?: 480
            val bmp = QrGen.encode(joinUrl, size)
            if (bmp != null) {
                binding.imgQr.setImageBitmap(bmp)
                binding.imgQr.visibility = View.VISIBLE
                binding.txtQrFallback.visibility = View.GONE
            } else {
                // Fall back to instructions; the code is still typeable.
                binding.imgQr.visibility = View.GONE
                binding.txtQrFallback.visibility = View.VISIBLE
            }
        }

        val host = hostFromUrl(joinUrl)
        binding.txtJoinHint.text = getString(R.string.join_hint, host)

        // Always show the decoder list on the lobby: it is the single most
        // useful fact when video fails, and costs one small line of text.
        rtc?.let { r ->
            r.initFactory()
            binding.txtDiag.visibility = View.VISIBLE
            binding.txtDiag.text = getString(R.string.diag_decoders, r.decoderSummary)
        }
        Log.i(TAG, "hosting room $room")
    }

    override fun onPeerJoin(peerId: String, role: String) {
        val what = if (role == "screen") getString(R.string.peer_screen) else getString(R.string.peer_camera)
        binding.txtStatus.text = getString(R.string.status_peer_joining, what)
    }

    override fun onPeerLeave(peerId: String) {
        stopStatsOverlay()
        rtc?.closePeer()
        isLive = false
        showLobby()
        binding.txtStatus.text = getString(R.string.status_ready)
        if (!prefs.showDiagnostics) binding.txtDiag.visibility = View.GONE
    }

    override fun onOffer(sdp: String) {
        binding.txtStatus.text = getString(R.string.status_negotiating)
        glPaintCount = 0
        rtc?.acceptOffer(sdp)
        // Always run diagnostics during a session: a black screen is far more
        // costly than a small text overlay.
        startStatsOverlay()
    }

    override fun onIce(candidate: JSONObject) {
        rtc?.addRemoteIce(candidate)
    }

    override fun onConnectionState(state: SignalingClient.State, detail: String?) {
        when (state) {
            SignalingClient.State.CONNECTING -> {
                binding.txtStatus.text = getString(R.string.status_connecting)
                binding.txtCode.text = "······"
                binding.imgQr.visibility = View.INVISIBLE
            }
            SignalingClient.State.CONNECTED -> { /* onHosted fills in details */ }
            SignalingClient.State.RECONNECTING -> {
                // Don't tear down a live stream: media flows peer-to-peer and
                // survives a signaling blip perfectly well.
                if (!isLive) {
                    binding.txtStatus.text = getString(R.string.status_reconnecting)
                    binding.txtCode.text = "······"
                    binding.imgQr.visibility = View.INVISIBLE
                }
            }
            SignalingClient.State.FAILED -> {
                binding.txtStatus.text = detail ?: getString(R.string.status_failed)
                binding.btnRetry.visibility = View.VISIBLE
            }
        }
    }

    override fun onServerError(code: String) {
        Log.w(TAG, "server error $code")
        if (code == "rate_limited") {
            binding.txtStatus.text = getString(R.string.status_failed)
            binding.btnRetry.visibility = View.VISIBLE
        }
    }

    // ---- RTC callbacks ------------------------------------------------------

    override fun onAnswerReady(sdp: String) {
        signaling?.sendAnswer(sdp)
    }

    override fun onLocalIce(candidate: JSONObject) {
        signaling?.sendIce(candidate)
    }

    /**
     * Frames are arriving from the network. This is NOT the cue to show the
     * video - a frame can arrive and still fail to paint. We wait for the
     * renderer's onFirstFrameRendered() for that, and only use this to update
     * the status text so the lobby shows progress.
     */
    override fun onFirstFrame() {
        main.post {
            if (!isLive) binding.txtStatus.text = getString(R.string.status_receiving)
        }
    }

    override fun onStreamEnded(reason: String) {
        main.post {
            rtc?.closePeer()
            isLive = false
            showLobby()
            binding.txtStatus.text = reason
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        }
    }

    override fun onIceState(state: PeerConnection.IceConnectionState) {
        main.post {
            if (prefs.showDiagnostics) {
                binding.txtDiag.visibility = View.VISIBLE
                binding.txtDiag.text = getString(R.string.diag_ice, state.name)
            }
        }
    }

    // ---- UI state -----------------------------------------------------------

    private fun showLobby() {
        // The video view stays visible underneath; the lobby covers it. This
        // keeps the Surface alive so the next sender renders immediately.
        binding.lobby.visibility = View.VISIBLE
        binding.txtLiveHint.visibility = View.GONE
        goImmersive()
    }

    private fun showLive() {
        isLive = true
        // Just uncover the video view - it was already visible and rendering.
        binding.lobby.visibility = View.GONE
        goImmersive()

        // Brief hint that Back stops the stream, then fade out so it doesn't
        // burn into the panel or distract from the content.
        binding.txtLiveHint.apply {
            alpha = 1f
            visibility = View.VISIBLE
            animate().alpha(0f).setStartDelay(4000).setDuration(800)
                .withEndAction { visibility = View.GONE }.start()
        }
    }

    /** Pretty-print the code with a mid separator: ABC123 -> ABC 123 */
    private fun formatCode(code: String): String =
        if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

    private fun hostFromUrl(url: String): String =
        try {
            val noScheme = url.substringAfter("://")
            noScheme.substringBefore("/")
        } catch (_: Exception) { url }

    /** Show the panel's own IP; invaluable when debugging a dead network. */
    private fun renderNetworkInfo() {
        if (!prefs.showDiagnostics) {
            binding.txtDiag.visibility = View.GONE
            return
        }
        val ip = try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wm?.connectionInfo?.ipAddress?.let { Formatter.formatIpAddress(it) } ?: "?"
        } catch (_: Exception) { "?" }
        binding.txtDiag.visibility = View.VISIBLE
        binding.txtDiag.text = getString(R.string.diag_net, ip, Build.MODEL, Build.VERSION.SDK_INT)
    }

    private fun goImmersive() {
        // Hide system bars. On old panels the nav bar often cannot be dismissed
        // permanently, so we re-apply on focus changes below.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ---- remote control -----------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // Back while live: drop the sender, return to the lobby - but do
            // NOT exit the app, since a kiosk panel should stay on this screen.
            KeyEvent.KEYCODE_BACK -> {
                if (isLive) {
                    signaling?.sendBye()
                    rtc?.closePeer()
                    isLive = false
                    showLobby()
                    binding.txtStatus.text = getString(R.string.status_ready)
                    return true
                }
            }
            // Menu / settings gesture from a TV remote.
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                openSettings()
                return true
            }
            // Yellow / info button toggles diagnostics without a keyboard.
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_YELLOW -> {
                prefs.showDiagnostics = !prefs.showDiagnostics
                renderNetworkInfo()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- lifecycle ----------------------------------------------------------

    override fun onResume() {
        super.onResume()
        goImmersive()
        renderNetworkInfo()
        // Settings may have changed the server; reconnect if so.
        if (binding.txtServer.text != prefs.signalingUrl) restartSignaling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatsOverlay()
        main.removeCallbacksAndMessages(null)
        signaling?.close()
        rtc?.release()
        try { binding.videoView.release() } catch (_: Exception) {}
        eglBase?.release()
        eglBase = null
    }

    companion object { private const val TAG = "PanelCast/Main" }
}
