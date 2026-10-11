package com.kafkasl.phonewhisper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStreamControllerTest {

    private var delivered: String? = null
    private var fallbackCount = 0

    private fun controller() = GatewayStreamController(
        onResult = { delivered = it },
        onFallback = { fallbackCount++ },
    )

    @Test
    fun onConnected_activates() {
        val c = controller()
        c.onConnected()
        assertTrue(c.isActive)
    }

    @Test
    fun onResult_delivers_clean_text_and_deactivates() {
        val c = controller()
        c.onConnected()
        c.onResult(JSONObject("""{"clean_text":"We ship Friday.","text":"so um we ship friday"}"""))
        assertFalse(c.isActive)
        assertEquals("We ship Friday.", delivered)
    }

    @Test
    fun onResult_falls_back_to_text_field() {
        val c = controller()
        c.onConnected()
        c.onResult(JSONObject("""{"text":"raw transcript only"}"""))
        assertEquals("raw transcript only", delivered)
    }

    @Test
    fun onResult_with_no_text_delivers_empty_string() {
        val c = controller()
        c.onConnected()
        c.onResult(JSONObject("""{"clean_text":""}"""))
        assertEquals("", delivered)
    }

    @Test
    fun failure_while_active_with_prepared_fallback_triggers_fallback() {
        val c = controller()
        c.onConnected()
        c.prepareFallback(ByteArray(10))
        c.onFailure("boom")
        assertFalse(c.isActive)
        assertEquals(1, fallbackCount)
        assertNull(delivered)
    }

    @Test
    fun failure_mid_recording_without_fallback_does_nothing() {
        val c = controller()
        c.onConnected()
        c.onFailure("dropped mid-record")
        assertFalse(c.isActive)
        assertEquals(0, fallbackCount)
    }

    @Test
    fun late_failure_after_result_does_not_double_fallback() {
        val c = controller()
        c.onConnected()
        c.prepareFallback(ByteArray(10))
        c.onResult(JSONObject("""{"text":"ok"}"""))
        c.onFailure("watchdog fired late")
        assertEquals("ok", delivered)
        assertEquals(0, fallbackCount)
    }

    @Test
    fun onResult_with_server_error_triggers_fallback_if_prepared() {
        val c = controller()
        c.onConnected()
        c.prepareFallback(ByteArray(10))
        c.onResult(JSONObject("""{"error":"internal model error"}"""))
        assertFalse(c.isActive)
        assertEquals(1, fallbackCount)
        assertNull(delivered)
    }

    @Test
    fun failure_before_connected_with_prepared_fallback_triggers_fallback() {
        val c = controller()
        c.prepareFallback(ByteArray(10))
        c.onFailure("connection refused")
        assertFalse(c.isActive)
        assertEquals(1, fallbackCount)
        assertNull(delivered)
    }
}
