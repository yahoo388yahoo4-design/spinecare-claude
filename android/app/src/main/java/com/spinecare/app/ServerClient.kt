package com.spinecare.app

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Coaching-server streaming client. Sends pose landmarks (privacy mode) or
 *  JPEG frames over WebSocket and receives metrics, framing, reps and cues.
 *  The caller keeps on-device coaching running whenever this is not connected. */
class ServerClient(private val baseUrl: String, user: String = "", pass: String = "") {
    private val credentials: String? = if (user.isNotEmpty()) Credentials.basic(user, pass) else null
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    @Volatile var connected = false; private set
    @Volatile var lastPoseAtMs = 0L; private set

    interface Listener {
        fun onPose(msg: JSONObject)
        fun onSessionSaved(summary: JSONObject) {}
        fun onError(error: String) {}
        fun onStatus(connected: Boolean)
    }

    /** Opens one socket. Calling it again replaces the previous socket. */
    fun connect(listener: Listener) {
        ws?.cancel()
        val wsUrl = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> baseUrl
        } + "/ws/session"
        val req = Request.Builder().url(wsUrl).apply { credentials?.let { header("Authorization", it) } }.build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true; listener.onStatus(true)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                runCatching {
                    when (o.optString("type")) {
                        "pose" -> { lastPoseAtMs = System.currentTimeMillis(); listener.onPose(o) }
                        "session_saved" -> listener.onSessionSaved(o)
                        "error" -> listener.onError(o.optString("error"))
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === ws) { connected = false; listener.onStatus(false) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket === ws) { connected = false; listener.onStatus(false) }
            }
        })
    }

    /** OkHttp queues messages sent before the socket opens. */
    fun startSession(exerciseId: String, lang: String) {
        ws?.send(JSONObject().put("type", "session").put("exercise_id", exerciseId).put("lang", lang).toString())
    }

    fun recalibrate() { ws?.send(JSONObject().put("type", "calibrate").toString()) }

    /** Privacy mode: only the 33 normalized landmarks leave the phone. */
    fun sendLandmarks(lms: List<Metrics.LM>, w: Int, h: Int, tMs: Long, imu: JSONObject?) {
        if (!connected) return
        val arr = JSONArray()
        for (l in lms) arr.put(JSONObject().put("x", l.x.toDouble()).put("y", l.y.toDouble())
            .put("z", l.z.toDouble()).put("visibility", l.visibility.toDouble()))
        val msg = JSONObject().put("type", "landmarks").put("format", "mediapipe33")
            .put("t", tMs).put("w", w).put("h", h).put("landmarks", arr)
        if (imu != null) msg.put("imu", imu)
        ws?.send(msg.toString())
    }

    fun sendFrame(bitmap: Bitmap, tMs: Long, imu: JSONObject?, scale: Float = 0.5f) {
        if (!connected) return
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        if (scaled !== bitmap) scaled.recycle()
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val msg = JSONObject().put("type", "frame").put("t", tMs).put("img", b64).put("w", w).put("h", h)
        if (imu != null) msg.put("imu", imu)
        ws?.send(msg.toString())
    }

    fun endSession() { ws?.send(JSONObject().put("type", "end_session").toString()) }

    fun close() {
        ws?.close(1000, "bye"); ws = null; connected = false
        http.dispatcher.executorService.shutdown()
    }
}
