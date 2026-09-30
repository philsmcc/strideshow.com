package com.strideshow.panelcast

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.strideshow.panelcast.board.AddSourceSheet
import com.strideshow.panelcast.board.Background
import com.strideshow.panelcast.board.Board
import com.strideshow.panelcast.board.BoardStore
import com.strideshow.panelcast.board.BoardView
import com.strideshow.panelcast.board.PageStrip
import com.strideshow.panelcast.board.RtcEngine
import com.strideshow.panelcast.board.ShapeType
import com.strideshow.panelcast.board.SourceSession
import com.strideshow.panelcast.board.SourceWindow
import com.strideshow.panelcast.board.SourceWindowLayer
import com.strideshow.panelcast.board.Toolbar
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.PeerConnection

/**
 * The whiteboard. This is what the panel boots into.
 *
 * View stack, bottom to top:
 *   SourceWindowLayer  - floating live-video windows
 *   BoardView          - paper + ink (transparent where there is no ink? No:
 *                        it paints the paper, so it must sit BELOW windows
 *                        for video to show... see note)
 *
 * Note on ordering: the paper has to be behind the windows and the ink has
 * to be in front of them. A single view cannot be both, so BoardView paints
 * the paper only when asked (`paintPaper`) and there are two instances: a
 * paper-only one at the bottom and the interactive ink one on top. The
 * bottom one shares the same Board so its grid follows pan/zoom.
 */
class BoardActivity : AppCompatActivity(),
    SignalingClient.Listener, SourceSession.Listener,
    Toolbar.Listener, BoardView.Listener, SourceWindowLayer.Listener, PageStrip.Listener {

    private lateinit var root: FrameLayout
    private lateinit var paper: BoardView
    private lateinit var windows: SourceWindowLayer
    private lateinit var ink: BoardView
    private lateinit var toolbar: Toolbar
    private lateinit var addSheet: AddSourceSheet
    private lateinit var pageStrip: PageStrip
    private lateinit var prefs: Prefs
    private lateinit var store: BoardStore

    private var eglBase: EglBase? = null
    private var engine: RtcEngine? = null
    private var signaling: SignalingClient? = null
    private val sessions = HashMap<String, SourceSession>()
    private val main = Handler(Looper.getMainLooper())
    private var maxSources = 8
    private var statsTimer: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = BoardStore(this)

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
        } catch (_: Throwable) {}
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        eglBase = EglBase.create()
        val board = store.load() ?: Board()

        root = FrameLayout(this)
        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        paper = BoardView(this).apply { this.board = board; paintPaper = true; paintInk = false; isEnabled = false }
        windows = SourceWindowLayer(this).apply { listener = this@BoardActivity }
        ink = BoardView(this).apply {
            this.board = board; paintPaper = false; paintInk = true
            listener = this@BoardActivity
            windowLayer = windows
            palmThresholdPx = resources.displayMetrics.xdpi / 25.4f * 22f // 22 mm contact = palm
        }
        // The ink layer must redraw the paper layer whenever the camera moves.
        ink.onInvalidatePaper = { paper.invalidate() }

        toolbar = Toolbar(this).apply { listener = this@BoardActivity }
        pageStrip = PageStrip(this).apply { listener = this@BoardActivity }
        addSheet = AddSourceSheet(this)

        root.addView(paper, full)
        root.addView(windows, full)
        root.addView(ink, full)
        root.addView(pageStrip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(toolbar, full)
        root.addView(addSheet, full)
        setContentView(root)

        applyToolbarDefaults()
        toolbar.setPageBadge(board.current, board.pages.size)
        toolbar.setHistory(board.page.canUndo, board.page.canRedo)

        engine = RtcEngine(this, eglBase!!)
        startSignaling()

        // First launch with no sources: show the sheet so the pairing code is
        // discoverable. After that the board comes up clean.
        if (prefs.showAddSheetOnLaunch) addSheet.show()

        // Autosave on a timer as well as on every edit, so a power cut
        // between two strokes costs at most a few seconds of work.
        main.postDelayed(object : Runnable {
            override fun run() { store.saveAsync(ink.board); main.postDelayed(this, 15_000) }
        }, 15_000)
    }

    private fun applyToolbarDefaults() {
        ink.tool = BoardView.Tool.PEN
        ink.inkColor = Toolbar.COLORS[0]
        ink.penWidthPx = 6f * resources.displayMetrics.density * 0.75f
        ink.highlighterWidthPx = 36f * resources.displayMetrics.density * 0.75f
        ink.eraserRadiusPx = resources.displayMetrics.xdpi / 25.4f * 12f // 12 mm
    }

    // =====================================================================
    // Signaling (host side)
    // =====================================================================

    private fun startSignaling() {
        signaling?.close()
        addSheet.setConnecting()
        signaling = SignalingClient(prefs.signalingUrl, this).also { it.connect() }
    }

    override fun onHosted(room: String, joinUrl: String, pcUrl: String, iceServers: List<SignalingClient.IceServer>) {
        engine?.applyIceServers(iceServers)
        engine?.init()
        addSheet.setRoom(room, joinUrl)
        addSheet.setSourceCount(sessions.size, maxSources)
        engine?.let { e ->
            val caps = CodecCaps.query()
            signaling?.sendCaps(caps.maxHeight, e.decoderNames, Build.MODEL ?: "")
        }
        Log.i(TAG, "hosting $room")
    }

    override fun onPeerJoin(peerId: String, role: String) = onPeerJoin(peerId, role, "")

    override fun onPeerJoin(peerId: String, role: String, label: String) {
        val engine = engine ?: return
        engine.init()
        // Replace any stale session with the same id (reconnect).
        sessions.remove(peerId)?.closePeer()
        windows.find(peerId)?.let { windows.remove(it) }

        val session = SourceSession(peerId, role, label, engine, this)
        sessions[peerId] = session
        val w = SourceWindow(this, peerId, role, label, eglBase!!.eglBaseContext)
        session.attachSink(w.renderer)
        session.setAudioEnabled(!w.muted)
        windows.add(w)
        w.setStatus("${label.ifBlank { if (role == "screen") "Screen" else "Camera" }} connecting\u2026")
        toolbar.pulseAdd()
        addSheet.flash()
        addSheet.setSourceCount(sessions.size, maxSources)
        // The first source auto-dismisses the sheet after a moment so the
        // teacher sees the window appear, not the pairing card.
        if (addSheet.isShowing && sessions.size == 1) main.postDelayed({ addSheet.hide() }, 1500)
        ensureStatsTimer()
    }

    override fun onPeerLeave(peerId: String) {
        sessions.remove(peerId)?.closePeer()
        windows.find(peerId)?.let { w ->
            w.showEnded("Source disconnected")
            // Leave the frozen window briefly so it does not vanish mid-sentence,
            // then remove it.
            main.postDelayed({ if (windows.find(peerId) === w) windows.remove(w) }, 2500)
        }
        addSheet.setSourceCount(sessions.size, maxSources)
    }

    override fun onOffer(sdp: String) { /* multi-source variant used */ }
    override fun onOffer(from: String, sdp: String) {
        val s = sessions[from] ?: run {
            // Offer for a source we never saw join (server restart race):
            // create the window now rather than dropping the stream.
            onPeerJoin(from, "camera", "")
            sessions[from] ?: return
        }
        s.acceptOffer(sdp)
    }

    override fun onIce(candidate: JSONObject) {}
    override fun onIce(from: String, candidate: JSONObject) { sessions[from]?.addRemoteIce(candidate) }

    override fun onConnectionState(state: SignalingClient.State, detail: String?) {
        when (state) {
            SignalingClient.State.CONNECTING -> addSheet.setStatus("Connecting to server\u2026")
            SignalingClient.State.RECONNECTING -> addSheet.setStatus("Reconnecting\u2026")
            SignalingClient.State.FAILED -> addSheet.setStatus(detail ?: "Cannot reach the server")
            SignalingClient.State.CONNECTED -> {}
        }
    }

    override fun onServerError(code: String) { Log.w(TAG, "server error $code") }

    // =====================================================================
    // Per-source RTC callbacks
    // =====================================================================

    override fun onAnswerReady(peerId: String, sdp: String) { signaling?.sendAnswer(peerId, sdp) }
    override fun onLocalIce(peerId: String, candidate: JSONObject) { signaling?.sendIce(peerId, candidate) }
    override fun onFirstFrame(peerId: String) { main.post { windows.find(peerId)?.onFirstFrame() } }
    override fun onEnded(peerId: String, reason: String) {
        main.post {
            windows.find(peerId)?.showEnded(reason)
            sessions.remove(peerId)?.closePeer()
        }
    }
    override fun onIceState(peerId: String, state: PeerConnection.IceConnectionState) {
        main.post {
            val w = windows.find(peerId) ?: return@post
            when (state) {
                PeerConnection.IceConnectionState.CHECKING -> w.setStatus("Connecting\u2026")
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> w.setStatus("Starting video\u2026")
                PeerConnection.IceConnectionState.DISCONNECTED -> w.setStatus("Reconnecting\u2026")
                else -> {}
            }
        }
    }

    private fun ensureStatsTimer() {
        if (statsTimer != null || !prefs.showDiagnostics) return
        val t = object : Runnable {
            override fun run() {
                for ((id, s) in sessions) {
                    s.pollStats { w, h, fps, kbps, codec ->
                        main.post { windows.find(id)?.setInfo(if (w > 0) "${w}\u00d7$h ${fps.toInt()}fps ${kbps / 1000}Mb $codec" else null) }
                    }
                }
                main.postDelayed(this, 2000)
            }
        }
        statsTimer = t
        main.post(t)
    }

    // =====================================================================
    // Windows
    // =====================================================================

    override fun onCloseRequested(w: SourceWindow) {
        signaling?.sendKick(w.peerId)
        sessions.remove(w.peerId)?.closePeer()
        windows.remove(w)
        addSheet.setSourceCount(sessions.size, maxSources)
    }

    override fun onFullscreenToggled(w: SourceWindow, full: Boolean) {
        // Hide the toolbar's pop-overs; the strip itself stays so you can still ink.
        toolbar.dismissPop()
    }

    override fun onMuteToggled(w: SourceWindow, muted: Boolean) { sessions[w.peerId]?.setAudioEnabled(!muted) }
    override fun onWindowFocused(w: SourceWindow) {}

    // =====================================================================
    // Toolbar
    // =====================================================================

    override fun onTool(tool: BoardView.Tool) { ink.tool = tool; pageStrip.hide() }
    override fun onColor(color: Int) { ink.inkColor = color }
    override fun onPenWidth(px: Float) {
        val scaled = px * resources.displayMetrics.density * 0.75f
        if (ink.tool == BoardView.Tool.HIGHLIGHTER) ink.highlighterWidthPx = scaled else ink.penWidthPx = scaled
    }
    override fun onShape(type: ShapeType, filled: Boolean) { ink.shapeType = type; ink.shapeFilled = filled }
    override fun onAutoShape(on: Boolean) { ink.autoShape = on }
    override fun onBackground(bg: Background) { ink.setBackground(bg); paper.invalidate() }
    override fun onUndo() = ink.undo()
    override fun onRedo() = ink.redo()
    override fun onClear() {
        if (ink.page.isEmpty) return
        AlertDialog.Builder(this)
            .setTitle("Clear this page?")
            .setMessage("Ink, shapes and text on this page will be removed. Live sources stay. You can undo.")
            .setPositiveButton("Clear") { _, _ -> ink.clearPage() }
            .setNegativeButton("Cancel", null)
            .show()
    }
    override fun onAddSource() { pageStrip.hide(); addSheet.show() }
    override fun onPages() { toolbar.dismissPop(); pageStrip.toggle(ink.board) }
    override fun onTile() = windows.tileAll()
    override fun onFit() = ink.fitToContent()
    override fun onLockCanvas(locked: Boolean) { ink.lockCanvas = locked; Toast.makeText(this, if (locked) "Canvas locked: pan and zoom off" else "Canvas unlocked", Toast.LENGTH_SHORT).show() }
    override fun onSave() {
        val bmp: Bitmap = ink.snapshotWithPaper(paper)
        val file = store.exportPng(bmp, ink.board.current)
        Toast.makeText(this, if (file != null) "Saved ${file.name} to Pictures/PanelCast" else "Could not save", Toast.LENGTH_LONG).show()
    }
    override fun onSettings() { startActivity(Intent(this, SettingsActivity::class.java)) }

    // =====================================================================
    // Board callbacks
    // =====================================================================

    override fun onHistoryChanged() {
        toolbar.setHistory(ink.page.canUndo, ink.page.canRedo)
        store.saveAsync(ink.board)
    }

    override fun onViewChanged() { paper.invalidate() }

    override fun onTextRequested(bx: Float, by: Float) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = "Type text"
            minLines = 1; maxLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("Add text")
            .setView(input)
            .setPositiveButton("Place") { _, _ -> ink.addText(bx, by, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    // =====================================================================
    // Pages
    // =====================================================================

    override fun onSelectPage(index: Int) {
        ink.board.current = index
        toolbar.setPageBadge(index, ink.board.pages.size)
        toolbar.setHistory(ink.page.canUndo, ink.page.canRedo)
        pageStrip.render(ink.board)
        ink.invalidate(); paper.invalidate()
        store.saveAsync(ink.board)
    }

    override fun onAddPage() {
        ink.board.addPage()
        onSelectPage(ink.board.current)
    }

    override fun onDeletePage(index: Int) {
        AlertDialog.Builder(this)
            .setTitle("Delete page ${index + 1}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> ink.board.removePage(index); onSelectPage(ink.board.current) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =====================================================================
    // System
    // =====================================================================

    private fun goImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Back peels UI layers: pop-over, sheet, page strip, fullscreen
                // window. It never exits the app - this is a kiosk display.
                when {
                    addSheet.isShowing -> addSheet.hide()
                    pageStrip.isShowing -> pageStrip.hide()
                    windows.windows.any { it.isFullscreen } -> windows.windows.first { it.isFullscreen }.let { windows.toggleFullscreen(it) }
                    else -> toolbar.dismissPop()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { onSettings(); return true }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_YELLOW -> {
                prefs.showDiagnostics = !prefs.showDiagnostics
                if (!prefs.showDiagnostics) { statsTimer?.let { main.removeCallbacks(it) }; statsTimer = null; windows.windows.forEach { it.setInfo(null) } }
                else ensureStatsTimer()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        if (signaling != null && prefs.signalingUrl != signalingUrlInUse) startSignaling()
        signalingUrlInUse = prefs.signalingUrl
    }
    private var signalingUrlInUse: String? = null

    override fun onPause() {
        super.onPause()
        store.saveNow(ink.board)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        store.saveNow(ink.board)
        signaling?.close()
        for (s in sessions.values) s.closePeer()
        sessions.clear()
        windows.windows.forEach { it.release() }
        engine?.release()
        eglBase?.release()
        eglBase = null
    }

    companion object { private const val TAG = "PanelCast/Board" }
}
