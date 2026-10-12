package com.kafkasl.phonewhisper

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GatewayStreamTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Exception) {
        }
    }

    private class ResultCapture {
        val connected = CountDownLatch(1)
        val result = CountDownLatch(1)
        val failure = CountDownLatch(1)
        var json: JSONObject? = null
        var message: String? = null
    }

    private class CapturingListener(private val cap: ResultCapture) : GatewayStream.Listener {
        override fun onConnected() = cap.connected.countDown()
        override fun onResult(json: JSONObject) {
            cap.json = json
            cap.result.countDown()
        }
        override fun onFailure(message: String) {
            cap.message = message
            cap.failure.countDown()
        }
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun test_happy_path_returns_result() {
        // Server upgrades, then sends the result frame when it sees our finalize.
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send(
                            """{"clean_text":"We ship Friday.","text":"so um we ship friday","cleanup":"ok"}"""
                        )
                    }
                }
            )
        )

        val cap = ResultCapture()
        val stream = GatewayStream(baseUrl(), "test-token")
        stream.connect(CapturingListener(cap))

        assertTrue("client never connected", cap.connected.await(5, TimeUnit.SECONDS))
        assertTrue("send before open failed", stream.send(byteArrayOf(1, 2, 3)))
        assertTrue("finalize failed", stream.finalize())

        assertTrue("no result frame arrived", cap.result.await(5, TimeUnit.SECONDS))
        assertEquals("We ship Friday.", cap.json?.getString("clean_text"))
    }

    @Test
    fun test_connect_refused_fails() {
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/").toString().trimEnd('/')
        dead.shutdown()

        val cap = ResultCapture()
        val stream = GatewayStream(deadUrl, "test-token")
        stream.connect(CapturingListener(cap))

        assertTrue("failure not reported", cap.failure.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun test_send_before_connected_returns_false() {
        val stream = GatewayStream(baseUrl(), "test-token")
        assertFalse(stream.send(byteArrayOf(1, 2, 3)))
        assertFalse(stream.finalize())
    }

    @Test
    fun test_chunks_sent_during_handshake_are_received_by_server() {        val receivedFrames = CountDownLatch(2) // 1 audio chunk + 1 finalize
        val capturedBytes = mutableListOf<okio.ByteString>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                        capturedBytes.add(bytes)
                        receivedFrames.countDown()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        receivedFrames.countDown()
                        webSocket.send("""{"text":"ok"}""")
                    }
                }
            )
        )

        val cap = ResultCapture()
        val stream = GatewayStream(baseUrl(), "test-token")
        stream.connect(CapturingListener(cap))
        // Send chunk immediately while handshake is in progress
        val sent = stream.send(byteArrayOf(42, 43))
        assertTrue("pre-connect send should succeed by buffering", sent)
        assertTrue(cap.connected.await(5, TimeUnit.SECONDS))
        assertTrue(stream.finalize())
        assertTrue(receivedFrames.await(5, TimeUnit.SECONDS))
        assertEquals(1, capturedBytes.size)
        assertEquals(42.toByte(), capturedBytes[0][0])
        assertEquals(43.toByte(), capturedBytes[0][1])
    }

    @Test
    fun test_connect_timeout_fires_when_handshake_never_completes() {
        // A TCP black hole: accepts the connection but never speaks HTTP,
        // so the WebSocket handshake never completes.
        val blackHole = java.net.ServerSocket(0)
        val acceptThread = Thread({ blackHole.accept() }, "blackhole-accept")
        acceptThread.isDaemon = true
        acceptThread.start()
        try {
            val cap = ResultCapture()
            val stream = GatewayStream(
                "http://127.0.0.1:${blackHole.localPort}",
                "test-token",
                connectTimeoutMs = 500L,
            )
            stream.connect(CapturingListener(cap))

            assertTrue("connect timeout not reported", cap.failure.await(5, TimeUnit.SECONDS))
            assertTrue((cap.message ?: "").lowercase().contains("timed out"))
            stream.close()
        } finally {
            blackHole.close()
        }
    }

    @Test
    fun test_isConnected_reflects_socket_state() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("""{"text":"ok"}""")
                    }
                }
            )
        )

        val cap = ResultCapture()
        val stream = GatewayStream(baseUrl(), "test-token")
        assertFalse(stream.isConnected)
        stream.connect(CapturingListener(cap))
        assertTrue("client never connected", cap.connected.await(5, TimeUnit.SECONDS))
        assertTrue(stream.isConnected)
        stream.close()
    }
}
