package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * Client for the gateway's streaming endpoint: WS /v1/dictation/stream.
 *
 * The socket opens up front, PCM chunks stream in while recording, and
 * finalize() ends the stream; the result arrives as one JSON text frame via
 * [Listener.onResult]. Any failure — including a close before the result
 * frame or an error returned by the server — reports [Listener.onFailure]
 * so the service can fall back to the batch upload path.
 */
class GatewayStream(
    private val url: String,
    private val token: String,
    private val resultTimeoutMs: Long = RESULT_TIMEOUT_MS,
    private val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
) {

    interface Listener {
        fun onConnected()
        fun onResult(json: JSONObject)
        fun onFailure(message: String)
    }

    private val lock = Any()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var connecting = false

    @Volatile
    private var listenerRef: Listener? = null

    @Volatile
    private var reportedFailure = false

    @Volatile
    private var reportedResult = false

    @Volatile
    private var watchdogThread: Thread? = null

    @Volatile
    private var connectWatchdogThread: Thread? = null

    private val pendingChunks = ArrayDeque<ByteArray>()

    /** True once the socket handshake completed and the stream is live. */
    val isConnected: Boolean
        get() = synchronized(lock) { socket != null }

    /**
     * Swaps the listener, e.g. when a pre-connected warm socket is adopted
     * by a dictation session. Only affects future callbacks.
     */
    fun setListener(listener: Listener) {
        listenerRef = listener
    }

    private val webSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                socket = webSocket
                connecting = false
                // Flush all audio buffered during the handshake
                while (pendingChunks.isNotEmpty()) {
                    val chunk = pendingChunks.removeFirst()
                    webSocket.send(chunk.toByteString())
                }
            }
            cancelConnectWatchdog()
            listenerRef?.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            cancelWatchdog()
            val parsed = try {
                JSONObject(text)
            } catch (e: Exception) {
                reportFailure("Could not parse gateway response: ${e.message ?: "bad json"}")
                return
            }
            reportedResult = true
            listenerRef?.onResult(parsed)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(lock) {
                socket = null
                connecting = false
                pendingChunks.clear()
            }
            cancelConnectWatchdog()
            cancelWatchdog()
            reportFailure(t.message ?: "Gateway stream failed")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(lock) {
                socket = null
                connecting = false
                pendingChunks.clear()
            }
            cancelConnectWatchdog()
            cancelWatchdog()
            // A close with no result frame means the stream died early
            reportFailure("Gateway stream closed before result (code $code)")
        }
    }

    fun connect(listener: Listener) {
        listenerRef = listener
        reportedFailure = false
        reportedResult = false
        synchronized(lock) {
            connecting = true
            pendingChunks.clear()
        }
        val request = Request.Builder()
            .url("${url.trimEnd('/')}/v1/dictation/stream")
            .header("Authorization", "Bearer $token")
            .build()
        startConnectWatchdog()
        sharedClient.newWebSocket(request, webSocketListener)
    }

    /**
     * Sends a chunk of PCM audio. If the connection handshake is still in progress,
     * the chunk is queued and flushed immediately upon socket open. Returns false
     * when the socket is closed or failed.
     */
    fun send(pcm: ByteArray): Boolean {
        if (reportedFailure) return false
        synchronized(lock) {
            val ws = socket
            if (ws != null) {
                return try {
                    ws.send(pcm.toByteString())
                } catch (e: Exception) {
                    reportFailure(e.message ?: "send failed")
                    false
                }
            }
            if (connecting) {
                // Buffer up to ~12.5 seconds of audio (50 chunks * 250ms) during handshake
                if (pendingChunks.size < 50) {
                    pendingChunks.add(pcm)
                    return true
                }
            }
            return false
        }
    }

    /** Sends the finalize frame; returns false when the socket is not open or queue is full. */
    fun finalize(): Boolean {
        synchronized(lock) {
            val ws = socket ?: return false
            return try {
                val sent = ws.send("""{"action":"finalize"}""")
                if (!sent) {
                    reportFailure("finalize frame could not be sent")
                    return false
                }
                startWatchdog()
                true
            } catch (e: Exception) {
                reportFailure(e.message ?: "finalize failed")
                false
            }
        }
    }

    private fun startWatchdog() {
        cancelWatchdog()
        val watchdog = Thread({
            try {
                Thread.sleep(resultTimeoutMs)
                reportFailure("Gateway stream timed out waiting for result")
            } catch (_: InterruptedException) {
                // Cancelled when result arrived or socket closed
            }
        }, "gateway-stream-watchdog")
        watchdog.isDaemon = true
        watchdogThread = watchdog
        watchdog.start()
    }

    private fun cancelWatchdog() {
        watchdogThread?.interrupt()
        watchdogThread = null
    }

    private fun startConnectWatchdog() {
        cancelConnectWatchdog()
        val watchdog = Thread({
            try {
                Thread.sleep(connectTimeoutMs)
                synchronized(lock) {
                    if (!connecting || socket != null) return@Thread
                }
                reportFailure("Gateway stream connect timed out after ${connectTimeoutMs}ms")
            } catch (_: InterruptedException) {
                // Cancelled when the socket opened or failed fast
            }
        }, "gateway-stream-connect-watchdog")
        watchdog.isDaemon = true
        connectWatchdogThread = watchdog
        watchdog.start()
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogThread?.interrupt()
        connectWatchdogThread = null
    }

    fun close() {
        cancelWatchdog()
        cancelConnectWatchdog()
        synchronized(lock) {
            connecting = false
            pendingChunks.clear()
            socket?.close(1000, null)
            socket = null
        }
    }

    private fun reportFailure(message: String) {
        synchronized(lock) {
            socket = null
            connecting = false
            pendingChunks.clear()
        }
        if (reportedResult) return
        if (!reportedFailure) {
            reportedFailure = true
            listenerRef?.onFailure(message)
        }
    }

    companion object {
        private const val READ_TIMEOUT_SECONDS = 300L
        private const val RESULT_TIMEOUT_MS = 300_000L // 300s per spec
        private const val CONNECT_TIMEOUT_MS = 8_000L // 8s handshake budget while mic is hot

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        }
    }
}
