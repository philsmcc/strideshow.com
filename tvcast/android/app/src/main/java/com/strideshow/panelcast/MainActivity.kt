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

            // INVISIBLE, not GONE. SurfaceViewRenderer is a SurfaceView: with
            // GONE it is not laid out, so no Surface is created, EGL never
            // attaches and every incoming frame is dropped - which showed up
            // as a black screen with a live connection. INVISIBLE keeps the
            // surface alive and ready behind the lobby.
            visibility = View.INVISIBLE
        }
    }

    /**
     * Fired on the render thread by the EGL renderer.
     */
    private val rendererEvents = object : RendererCommon.RendererEvents {
        override fun onFirstFrameRendered() {
            Log.i(TAG, "first frame rendered")
            main.post { showLive() }
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
        Log.i(TAG, "hosting room $room")
    }

    override fun onPeerJoin(peerId: String, role: String) {
        val what = if (role == "screen") getString(R.string.peer_screen) else getString(R.string.peer_camera)
        binding.txtStatus.text = getString(R.string.status_peer_joining, what)
    }

    override fun onPeerLeave(peerId: String) {
        rtc?.closePeer()
        isLive = false
        showLobby()
        binding.txtStatus.text = getString(R.string.status_ready)
    }

    override fun onOffer(sdp: String) {
        binding.txtStatus.text = getString(R.string.status_negotiating)
        rtc?.acceptOffer(sdp)
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
        binding.lobby.visibility = View.VISIBLE
        // INVISIBLE keeps the SurfaceView's surface allocated so the next
        // stream can render immediately; GONE would tear it down and we would
        // be back to dropping frames.
        binding.videoView.visibility = View.INVISIBLE
        binding.txtLiveHint.visibility = View.GONE
        goImmersive()
    }

    private fun showLive() {
        isLive = true
        binding.lobby.visibility = View.GONE
        binding.videoView.visibility = View.VISIBLE
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
        main.removeCallbacksAndMessages(null)
        signaling?.close()
        rtc?.release()
        try { binding.videoView.release() } catch (_: Exception) {}
        eglBase?.release()
        eglBase = null
    }

    companion object { private const val TAG = "PanelCast/Main" }
}
