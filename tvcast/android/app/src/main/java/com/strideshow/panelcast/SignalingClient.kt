package com.strideshow.panelcast

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * WebSocket signaling client for the TV (host) side.
 *
 * Owns reconnection. A wall-mounted panel may sit for weeks and must recover
 * from Wi-Fi drops, server restarts and DHCP changes without anyone touching
 * it, so failures always retry with capped exponential backoff rather than
 * surfacing a dead end to the user.
 *
 * All callbacks are delivered on the main thread.
 */
class SignalingClient(
    private val url: String,
    private val listener: Listener,
) {
    interface Listener {
        /** Room is open; show the QR code. */
        fun onHosted(room: String, joinUrl: String, pcUrl: String, iceServers: List<IceServer>)
        /** A sender joined; expect an offer next. */
        fun onPeerJoin(peerId: String, role: String)
        fun onPeerLeave(peerId: String)
        fun onOffer(sdp: String)
        fun onIce(candidate: JSONObject)
        /** Transport-level state, for the status line. */
        fun onConnectionState(state: State, detail: String?)
        fun onServerError(code: String)
    }

    enum class State { CONNECTING, CONNECTED, RECONNECTING, FAILED }

    data class IceServer(val urls: List<String>, val username: String?, val credential: String?)

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        // Server pings every 25s; ours is a backstop for silent NAT timeouts.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var ws: WebSocket? = null
    private var attempt = 0
    private var closedByUs = false
    private var reconnectPending = false

    @Volatile var room: String? = null
        private set

    fun connect() {
        closedByUs = false
        openSocket()
    }

    private fun openSocket() {
        notifyState(if (attempt == 0) State.CONNECTING else State.RECONNECTING, null)

        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            // Malformed URL from Settings - not retryable, tell the user.
            notifyState(State.FAILED, "Invalid server address")
            return
        }

        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "socket open")
                attempt = 0
                notifyState(State.CONNECTED, null)
                // Claim a room immediately; the TV is always the host.
                send(JSONObject().put("type", "host"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure: ${t.message}")
                room = null
                if (!closedByUs) scheduleReconnect(t.message ?: "network error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "socket closed $code $reason")
                room = null
                if (!closedByUs) scheduleReconnect(reason.ifEmpty { "disconnected" })
            }
        })
    }

    private fun handleMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) {
            Log.w(TAG, "bad json from server"); return
        }
        when (msg.optString("type")) {
            "hosted" -> {
                val code = msg.optString("room")
                room = code
                main.post {
                    listener.onHosted(
                        code,
                        msg.optString("joinUrl"),
                        msg.optString("pcUrl"),
                        parseIceServers(msg.optJSONArray("iceServers")),
                    )
                }
            }
            "peer-join" -> main.post {
                listener.onPeerJoin(msg.optString("peerId"), msg.optString("role"))
            }
            "peer-leave" -> main.post { listener.onPeerLeave(msg.optString("peerId")) }
            "offer" -> {
                val sdp = msg.optString("sdp")
                if (sdp.isNotEmpty()) main.post { listener.onOffer(sdp) }
            }
            "ice" -> {
                val c = msg.optJSONObject("candidate")
                if (c != null) main.post { listener.onIce(c) }
            }
            "error" -> {
                val code = msg.optString("code")
                Log.w(TAG, "server error: $code")
                main.post { listener.onServerError(code) }
            }
            "pong" -> { /* keepalive */ }
        }
    }

    private fun parseIceServers(arr: JSONArray?): List<IceServer> {
        if (arr == null) return emptyList()
        val out = mutableListOf<IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urls = mutableListOf<String>()
            when (val u = o.opt("urls")) {
                is String -> urls.add(u)
                is JSONArray -> for (j in 0 until u.length()) u.optString(j)?.let { urls.add(it) }
            }
            if (urls.isEmpty()) continue
            out.add(
                IceServer(
                    urls,
                    o.optString("username").ifEmpty { null },
                    o.optString("credential").ifEmpty { null },
                )
            )
        }
        return out
    }

    private fun scheduleReconnect(detail: String) {
        if (reconnectPending) return
        reconnectPending = true

        // 1s, 2s, 4s ... capped at 20s. Never give up: the panel must heal
        // itself after an overnight router reboot with nobody in the room.
        val delayMs = min(20_000.0, 1000.0 * 2.0.pow(attempt.toDouble())).toLong()
        attempt++
        notifyState(State.RECONNECTING, detail)
        Log.i(TAG, "reconnecting in ${delayMs}ms (attempt $attempt)")

        main.postDelayed({
            reconnectPending = false
            if (!closedByUs) openSocket()
        }, delayMs)
    }

    private fun notifyState(state: State, detail: String?) {
        main.post { listener.onConnectionState(state, detail) }
    }

    fun send(obj: JSONObject) {
        val socket = ws
        if (socket == null) { Log.w(TAG, "send with no socket"); return }
        if (!socket.send(obj.toString())) Log.w(TAG, "send failed (queue full)")
    }

    fun sendAnswer(sdp: String) {
        send(JSONObject().put("type", "answer").put("sdp", sdp))
    }

    fun sendIce(candidate: JSONObject) {
        send(JSONObject().put("type", "ice").put("candidate", candidate))
    }

    /** Drop the current sender but keep hosting the room. */
    fun sendBye() {
        send(JSONObject().put("type", "bye"))
    }

    fun close() {
        closedByUs = true
        main.removeCallbacksAndMessages(null)
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        room = null
    }

    companion object { private const val TAG = "PanelCast/Signal" }
}
