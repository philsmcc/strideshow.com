package com.strideshow.panelcast

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.strideshow.panelcast.databinding.ActivityMainBinding
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.EglRenderer
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
    private var revealTimer: Runnable? = null
    /** True between surfaceCreated and surfaceDestroyed on the video view. */
    private var surfaceReady = false
    private var currentRoom: String? = null
    private var lastJoinUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        // Media playback routing: MODE_NORMAL with STREAM_MUSIC is what a TV
        // expects. Without this some panels route WebRTC audio to the (absent)
        // voice-call stream and play nothing.
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        } catch (t: Throwable) {
            Log.w(TAG, "could not set audio mode: ${t.message}")
        }

        // A wall panel must never sleep or dim while showing a pairing code.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        eglBase = EglBase.create()

        // Watch the Surface lifecycle directly. EglRenderer silently discards
        // frames ("Dropping frame - No surface") when no Surface exists, which
        // is invisible from the app side unless we track it ourselves.
        binding.videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                Log.i(TAG, "video surface CREATED")
            }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                Log.i(TAG, "video surface changed ${w}x$h")
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                Log.w(TAG, "video surface DESTROYED")
            }
        })

        setupRenderer()

        rtc = RtcReceiver(this, eglBase!!, this).also {
            it.attachRenderer(binding.videoView)
        }
        syncPaintCounter()

        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnRetry.setOnClickListener { restartSignaling() }

        applyDisplayMetrics()
        showLobby()
        renderNetworkInfo()
        startSignaling()
    }

    // ---- renderer -----------------------------------------------------------

    /**
     * Apply overscan compensation by insetting our own content.
     *
     * Applied to BOTH the video surface and the lobby, so the QR code and the
     * shared picture are equally safe from a cropping TV. Also scales the
     * lobby's text with the screen's shortest side, because a TV panel and an
     * HDMI stick can report very different densities for the same physical
     * screen - which is why UI elements looked bigger on the stick.
     */
    private fun applyDisplayMetrics() {
        val pct = prefs.overscanPercent
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val insetX = w * pct / 100
        val insetY = h * pct / 100

        // Inset the video surface.
        (binding.videoView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.setMargins(insetX, insetY, insetX, insetY)
            binding.videoView.layoutParams = lp
        }
        // Inset the lobby by padding, preserving its own design padding.
        val basePadX = (56 * resources.displayMetrics.density).toInt()
        val basePadY = (40 * resources.displayMetrics.density).toInt()
        binding.lobby.setPadding(basePadX + insetX, basePadY + insetY,
                                 basePadX + insetX, basePadY + insetY)

        // Density-independent text sizing: base everything on the shortest
        // side in *pixels*, so the lobby looks the same on a 1080p panel and
        // a 1080p stick regardless of the density each one reports.
        val shortSide = minOf(w, h).toFloat()
        fun sp(fraction: Float) = shortSide * fraction / resources.displayMetrics.scaledDensity

        binding.txtBrand.textSize = sp(0.024f)
        binding.txtTitle.textSize = sp(0.037f)
        binding.txtQrLabel.textSize = sp(0.018f)
        binding.txtCodeLabel.textSize = sp(0.018f)
        binding.txtCode.textSize = sp(0.061f)
        binding.txtJoinHint.textSize = sp(0.018f)
        binding.txtStatus.textSize = sp(0.019f)
        binding.txtServer.textSize = sp(0.013f)
        binding.txtDiag.textSize = sp(0.013f)

        // QR: size from the screen, not a fixed dp, so it stays scannable.
        val qr = (shortSide * 0.30f).toInt()
        binding.qrPlate.layoutParams = binding.qrPlate.layoutParams.apply {
            width = qr; height = qr
        }
        Log.i(TAG, "display ${w}x$h density=${resources.displayMetrics.density} overscan=$pct% qr=$qr")
    }

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
    private val paintListener = EglRenderer.FrameListener { glPaintCount++ }
    private var paintListenerInstalled = false

    /**
     * Install/remove the proof-of-paint counter.
     *
     * This is diagnostics-only and NOT free: EglRenderer.notifyCallbacks does
     * a GPU readback into a Bitmap for every sampled frame, which is real
     * overhead on the weak hardware this app targets. It was essential for
     * finding the black-screen bug; now it is opt-in.
     */
    private fun syncPaintCounter() {
        val want = prefs.showDiagnostics
        if (want && !paintListenerInstalled) {
            try {
                // Sample sparsely (1 in 60) - we only need proof of life.
                binding.videoView.addFrameListener(paintListener, 1f / 60f)
                paintListenerInstalled = true
            } catch (t: Throwable) {
                Log.w(TAG, "could not install paint counter: ${t.message}")
            }
        } else if (!want && paintListenerInstalled) {
            try { binding.videoView.removeFrameListener(paintListener) } catch (_: Exception) {}
            paintListenerInstalled = false
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
                            if (surfaceReady) "surf✓" else "NO-SURFACE",
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

    /**
     * Lightweight always-on poll whose only job is to reveal the video once
     * frames are decoding. Needed because the renderer's first-frame callback
     * fires at most once per renderer lifetime, so it cannot be relied on for
     * the second and later sessions.
     */
    private fun startRevealPoll() {
        stopRevealPoll()
        val tick = object : Runnable {
            override fun run() {
                if (!isLive) {
                    rtc?.pollStats { st ->
                        if (st.framesDecoded > 0) main.post { if (!isLive) showLive() }
                    }
                    main.postDelayed(this, 1000)
                }
            }
        }
        revealTimer = tick
        main.post(tick)
    }

    private fun stopRevealPoll() {
        revealTimer?.let { main.removeCallbacks(it) }
        revealTimer = null
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
            // Primary trigger when diagnostics (and therefore the stats poll)
            // are off. Fires only once per renderer lifetime, so the poll
            // fallback below still matters on reconnects.
            main.post { if (!isLive) showLive() }
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

        // Report what this display can actually decode, so the sender's UI
        // only offers modes that will work here.
        rtc?.let { r ->
            r.initFactory()
            val caps = CodecCaps.query()
            signaling?.sendCaps(caps.maxHeight, r.decoderNames, Build.MODEL ?: "")
            if (prefs.showDiagnostics) {
                binding.txtDiag.visibility = View.VISIBLE
                binding.txtDiag.text = getString(
                    R.string.diag_decoders, r.decoderSummary, caps.maxHeight,
                )
            }
        }
        Log.i(TAG, "hosting room $room")
    }

    override fun onPeerJoin(peerId: String, role: String) {
        val what = if (role == "screen") getString(R.string.peer_screen) else getString(R.string.peer_camera)
        binding.txtStatus.text = getString(R.string.status_peer_joining, what)
    }

    override fun onPeerLeave(peerId: String) {
        stopStatsOverlay()
        stopRevealPoll()
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
        // Stats polling is for diagnosis only. Leaving a text overlay on a
        // wall-mounted display permanently risks burn-in and distracts from
        // the content, so it follows the diagnostics setting.
        if (prefs.showDiagnostics) startStatsOverlay()
        startRevealPoll()
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
        if (isLive) return
        isLive = true
        stopRevealPoll()
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
        applyDisplayMetrics()
        syncPaintCounter()
        renderNetworkInfo()
        // Settings may have changed the server; reconnect if so.
        if (binding.txtServer.text != prefs.signalingUrl) restartSignaling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatsOverlay()
        stopRevealPoll()
        main.removeCallbacksAndMessages(null)
        signaling?.close()
        rtc?.release()
        try { binding.videoView.release() } catch (_: Exception) {}
        eglBase?.release()
        eglBase = null
    }

    companion object { private const val TAG = "PanelCast/Main" }
}
