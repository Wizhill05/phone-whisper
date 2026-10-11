package com.kafkasl.phonewhisper

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DictationClientTest {

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
        baseUrl: String = server.url("/").toString(),
        token: String = "test-token",
    ): DictationClient.Result {
        val latch = CountDownLatch(1)
        var result: DictationClient.Result? = null
        DictationClient.dictate(byteArrayOf(1, 2, 3), baseUrl.trimEnd('/'), token) { r ->
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
                .setBody("""{"clean_text":"We ship Monday.","text":"so um we ship friday","cleanup":"ok"}""")
        )

        val result = call()

        assertNull(result.error)
        assertEquals("We ship Monday.", result.text)

        val request = server.takeRequest()
        assertEquals("/v1/dictation", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("""name="clean""""))
        assertTrue(body.contains("true"))
        assertTrue(body.contains("""name="file""""))
        assertTrue(body.contains("audio.wav"))
    }

    @Test
    fun test_cleanup_failed_still_returns_raw_text() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"raw transcript","cleanup":"failed"}""")
        )

        val result = call()

        assertNull(result.error)
        assertEquals("raw transcript", result.text)
    }

    @Test
    fun test_401_returns_token_error() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"invalid token"}"""))

        val result = call()

        assertNull(result.text)
        assertTrue(result.error!!.lowercase().contains("token"))
    }

    @Test
    fun test_malformed_json_returns_error() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        val result = call()

        assertNull(result.text)
        assertTrue(result.error != null)
    }

    @Test
    fun test_connection_failure_returns_error() {
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/").toString()
        dead.shutdown()

        val result = call(baseUrl = deadUrl)

        assertNull(result.text)
        assertTrue(result.error != null)
    }

    @Test
    fun test_url_without_scheme_returns_error() {
        val result = call(baseUrl = "100.122.152.101:8899")

        assertNull(result.text)
        assertTrue(result.error != null)
    }
}
