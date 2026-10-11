package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the gateway's streaming endpoint: WS /v1/dictation/stream.
 *
 * The socket opens up front, PCM chunks stream in while recording, and
 * finalize() ends the stream; the result arrives as one JSON text frame via
 * [Listener.onResult]. Any failure — including a close before the result
 * frame — reports [Listener.onFailure] so the service can fall back to the
 * batch upload path.
 */
class GatewayStream(
    private val url: String,
    private val token: String,
    private val resultTimeoutMs: Long = RESULT_TIMEOUT_MS,
) {

    interface Listener {
        fun onConnected()
        fun onResult(json: JSONObject)
        fun onFailure(message: String)
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var listenerRef: Listener? = null

    @Volatile
    private var reportedFailure = false

    @Volatile
    private var reportedResult = false

    private val webSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            listenerRef?.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val parsed = try {
                JSONObject(text)
            } catch (e: Exception) {
                listenerRef?.onFailure("Could not parse gateway response: ${e.message ?: "bad json"}")
                return
            }
            reportedResult = true
            listenerRef?.onResult(parsed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            reportFailure(t.message ?: "Gateway stream failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            // A close with no result frame means the stream died early (the
            // gateway dropped us, network flipped, ...). Report it so the
            // caller falls back to the batch upload path.
            reportFailure("Gateway stream closed before result (code $code)")
        }
    }

    fun connect(listener: Listener) {
        listenerRef = listener
        reportedFailure = false
        reportedResult = false
        val request = Request.Builder()
            .url("${url.trimEnd('/')}/v1/dictation/stream")
            .header("Authorization", "Bearer $token")
            .build()
        client.newWebSocket(request, webSocketListener)
    }

    /** Returns false when the socket is not open — the caller keeps recording locally. */
    fun send(pcm: ByteArray): Boolean {
        val ws = socket ?: return false
        return try {
            ws.send(okio.ByteString.of(*pcm))
        } catch (e: Exception) {
            reportFailure(e.message ?: "send failed")
            false
        }
    }

    /** Sends the finalize frame; returns false when the socket is not open. */
    fun finalize(): Boolean {
        val ws = socket ?: return false
        return try {
            ws.send("""{"action":"finalize"}""")
            startWatchdog()
            true
        } catch (e: Exception) {
            reportFailure(e.message ?: "finalize failed")
            false
        }
    }

    /**
     * Once finalize() is sent the caller is owed exactly one result frame. If
     * none arrives within the read timeout, treat it as a failure so the
     * service falls back to the batch upload path — OkHttp does not always
     * report a cleanly-closed socket (e.g. when the peer closed during the
     * handshake), so this watchdog is the guarantee.
     */
    private fun startWatchdog() {
        val watchdog = Thread({
            Thread.sleep(resultTimeoutMs)
            reportFailure("Gateway stream timed out waiting for result")
        }, "gateway-stream-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
    }

    private fun reportFailure(message: String) {
        socket = null
        // After a successful result, a later close is the normal end of the
        // session — not a failure.
        if (reportedResult) return
        if (!reportedFailure) {
            reportedFailure = true
            listenerRef?.onFailure(message)
        }
    }

    companion object {
        private const val READ_TIMEOUT_SECONDS = 300L
        private const val RESULT_TIMEOUT_MS = 60_000L
    }
}

