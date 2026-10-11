package com.kafkasl.phonewhisper

import org.json.JSONObject

/**
 * Coordinates streaming state and batch-upload fallback for a single dictation session.
 *
 * Implements [GatewayStream.Listener]. Tracks whether the stream is actively connected,
 * holds a copy of captured PCM audio if prepared for fallback at stop time, and
 * delegates the final transcript or fallback trigger to the caller.
 */
class GatewayStreamController(
    private val onResult: (String) -> Unit,
    private val onFallback: (ByteArray) -> Unit,
) : GatewayStream.Listener {

    var isActive: Boolean = false
        private set

    private var fallbackPcm: ByteArray? = null

    override fun onConnected() {
        isActive = true
    }

    override fun onResult(json: JSONObject) {
        isActive = false
        fallbackPcm = null
        val cleanText = json.optString("clean_text")
        val text = if (cleanText.isNotBlank()) cleanText else json.optString("text")
        onResult(text.trim())
    }

    override fun onFailure(message: String) {
        val wasActive = isActive
        isActive = false
        val fallback = fallbackPcm
        fallbackPcm = null
        if (wasActive && fallback != null) {
            onFallback(fallback)
        }
    }

    /**
     * Stores [pcm] for a possible post-finalize fallback in case the stream drops
     * or times out before the gateway returns a result.
     */
    fun prepareFallback(pcm: ByteArray) {
        fallbackPcm = pcm
    }
}
