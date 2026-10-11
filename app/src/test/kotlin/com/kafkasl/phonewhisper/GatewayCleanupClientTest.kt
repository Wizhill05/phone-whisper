package com.kafkasl.phonewhisper

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GatewayCleanupClientTest {

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

    private fun call(
        text: String = "so um we ship friday",
        baseUrl: String = server.url("/").toString(),
        token: String = "test-token",
        prompt: String? = null,
    ): GatewayCleanupClient.Result {
        val latch = CountDownLatch(1)
        var result: GatewayCleanupClient.Result? = null
        GatewayCleanupClient.clean(text, baseUrl.trimEnd('/'), token, prompt) { r ->
            result = r
            latch.countDown()
        }
        assertTrue("callback never fired", latch.await(5, TimeUnit.SECONDS))
        return result!!
    }

    @Test
    fun test_happy_path_returns_clean_text() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"so um we ship friday","clean_text":"We ship Friday.","cleanup":"ok","llm_seconds":1.0}""")
        )

        val result = call()

        assertNull(result.error)
        assertEquals("so um we ship friday", result.text)
        assertEquals("We ship Friday.", result.cleanText)

        val request = server.takeRequest()
        assertEquals("/v1/cleanup", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type")?.lowercase())
        val json = JSONObject(request.body.readUtf8())
        assertEquals("so um we ship friday", json.getString("text"))
    }

    @Test
    fun test_custom_prompt_sent_in_body() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"input","clean_text":"output","cleanup":"ok","llm_seconds":0.5}""")
        )

        val result = call(text = "input", prompt = "custom prompt")

        assertNull(result.error)
        assertEquals("output", result.cleanText)

        val request = server.takeRequest()
        val json = JSONObject(request.body.readUtf8())
        assertEquals("custom prompt", json.getString("prompt"))
    }

    @Test
    fun test_cleanup_failed_upstream_falls_back_to_raw_text() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"raw input","clean_text":"raw input","cleanup":"failed","llm_seconds":0.5}""")
        )

        val result = call(text = "raw input")

        assertNull(result.error)
        assertEquals("raw input", result.cleanText)
    }

    @Test
    fun test_http_error_falls_back_to_raw_text_with_error() {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"detail":"internal error"}""")
        )

        val result = call(text = "raw speech")

        assertNotNull(result.error)
        assertEquals("raw speech", result.cleanText)
    }

    @Test
    fun test_connection_failure_falls_back_to_raw_text_with_error() {
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/").toString()
        dead.shutdown()

        val result = call(text = "spoken words", baseUrl = deadUrl)

        assertNotNull(result.error)
        assertEquals("spoken words", result.cleanText)
    }
}
