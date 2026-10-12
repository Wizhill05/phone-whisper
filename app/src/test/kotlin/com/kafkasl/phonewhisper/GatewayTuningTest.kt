package com.kafkasl.phonewhisper

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GatewayTuningTest {

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

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun fetch(
        baseUrl: String = baseUrl(),
        token: String = "test-token",
    ): GatewayTuning.Tuning {
        val latch = CountDownLatch(1)
        var tuning: GatewayTuning.Tuning? = null
        GatewayTuning.fetch(baseUrl, token) { t ->
            tuning = t
            latch.countDown()
        }
        assertTrue("callback never fired", latch.await(5, TimeUnit.SECONDS))
        return tuning!!
    }

    @Test
    fun fetch_happy_path_returns_server_values() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"chunk_bytes":16000,"connect_timeout_ms":5000,"result_timeout_ms":60000}""")
        )

        val tuning = fetch()

        assertEquals(16000, tuning.chunkBytes)
        assertEquals(5000L, tuning.connectTimeoutMs)
        assertEquals(60000L, tuning.resultTimeoutMs)

        val request = server.takeRequest()
        assertEquals("/v1/config", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun fetch_404_returns_defaults() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))

        assertEquals(GatewayTuning.DEFAULT, fetch())
    }

    @Test
    fun fetch_malformed_json_returns_defaults() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))

        assertEquals(GatewayTuning.DEFAULT, fetch())
    }

    @Test
    fun fetch_connection_failure_returns_defaults() {
        val dead = MockWebServer()
        dead.start()
        val deadUrl = dead.url("/").toString().trimEnd('/')
        dead.shutdown()

        assertEquals(GatewayTuning.DEFAULT, fetch(baseUrl = deadUrl))
    }

    @Test
    fun parse_falls_back_per_field() {
        val tuning = GatewayTuning.parse(JSONObject("""{"chunk_bytes":16000}"""))

        assertEquals(16000, tuning.chunkBytes)
        assertEquals(GatewayTuning.DEFAULT.connectTimeoutMs, tuning.connectTimeoutMs)
        assertEquals(GatewayTuning.DEFAULT.resultTimeoutMs, tuning.resultTimeoutMs)
    }

    @Test
    fun parse_rejects_non_positive_values() {
        val tuning = GatewayTuning.parse(
            JSONObject("""{"chunk_bytes":0,"connect_timeout_ms":-1,"result_timeout_ms":0}""")
        )

        assertEquals(GatewayTuning.DEFAULT, tuning)
    }

    @Test
    fun map_round_trip_preserves_values() {
        val original = GatewayTuning.Tuning(16000, 5000L, 60000L)
        val restored = GatewayTuning.fromMap(GatewayTuning.toMap(original))

        assertEquals(original, restored)
    }

    @Test
    fun fromMap_missing_keys_returns_defaults() {
        assertEquals(GatewayTuning.DEFAULT, GatewayTuning.fromMap(emptyMap()))
    }
}
