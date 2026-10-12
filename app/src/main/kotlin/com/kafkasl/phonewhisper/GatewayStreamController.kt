package com.kafkasl.phonewhisper

import org.json.JSONObject

/** A stream transcript plus whether the gateway already cleaned it. */
data class StreamResult(val text: String, val isClean: Boolean)

/**
 * Coordinates streaming state and batch-upload fallback for a single dictation session.
 *
 * Implements [GatewayStream.Listener]. Tracks whether the stream is actively connected,
 * holds a copy of captured PCM audio if prepared for fallback at stop time, and
 * delegates the final transcript or fallback trigger to the caller.
 *
 * Results already containing clean_text are flagged [StreamResult.isClean] so the
 * caller can finish immediately (single pass); raw-text-only results need one
 * separate cleanup call.
 */
class GatewayStreamController(
    private val onResult: (StreamResult) -> Unit,
    private val onFallback: (ByteArray) -> Unit,
    private val onConnected: (() -> Unit)? = null,
) : GatewayStream.Listener {

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    private var fallbackPcm: ByteArray? = null

    override fun onConnected() {
        isActive = true
        onConnected?.invoke()
    }

    override fun onResult(json: JSONObject) {
        isActive = false
        val fallback = fallbackPcm
        fallbackPcm = null

        val error = json.optString("error")
        if (error.isNotBlank()) {
            if (fallback != null) {
                onFallback(fallback)
            } else {
                onResult(StreamResult("", isClean = false))
            }
            return
        }

        val cleanText = json.optString("clean_text")
        if (cleanText.isNotBlank()) {
            onResult(StreamResult(cleanText.trim(), isClean = true))
        } else {
            onResult(StreamResult(json.optString("text").trim(), isClean = false))
        }
    }

    override fun onFailure(message: String) {
        isActive = false
        val fallback = fallbackPcm
        fallbackPcm = null
        if (fallback != null) {
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
