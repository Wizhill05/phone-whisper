package com.kafkasl.phonewhisper

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Remote-tunable streaming parameters fetched from the gateway (GET /v1/config).
 *
 * Every failure mode — 404 (endpoint not implemented yet), malformed JSON,
 * unreachable host — resolves to [DEFAULT], so tuning can never break
 * dictation. Fetched values are cached in prefs by the service.
 */
object GatewayTuning {

    data class Tuning(
        val chunkBytes: Int,
        val connectTimeoutMs: Long,
        val resultTimeoutMs: Long,
    )

    /** Hardcoded defaults used when the gateway has no /v1/config (yet). */
    val DEFAULT = Tuning(
        chunkBytes = 8000, // ~250 ms of 16 kHz mono PCM16
        connectTimeoutMs = 8_000L,
        resultTimeoutMs = 300_000L,
    )

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 10L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Parses a /v1/config body; unknown/missing/invalid fields fall back per-field. */
    fun parse(json: JSONObject): Tuning {
        val chunkBytes = json.optInt("chunk_bytes", DEFAULT.chunkBytes)
            .takeIf { it > 0 } ?: DEFAULT.chunkBytes
        val connectTimeoutMs = json.optLong("connect_timeout_ms", DEFAULT.connectTimeoutMs)
            .takeIf { it > 0 } ?: DEFAULT.connectTimeoutMs
        val resultTimeoutMs = json.optLong("result_timeout_ms", DEFAULT.resultTimeoutMs)
            .takeIf { it > 0 } ?: DEFAULT.resultTimeoutMs
        return Tuning(chunkBytes, connectTimeoutMs, resultTimeoutMs)
    }

    /** Serializes [tuning] for prefs caching. */
    fun toMap(tuning: Tuning): Map<String, Long> = mapOf(
        "chunk_bytes" to tuning.chunkBytes.toLong(),
        "connect_timeout_ms" to tuning.connectTimeoutMs,
        "result_timeout_ms" to tuning.resultTimeoutMs,
    )

    /** Restores cached tuning; missing keys resolve to [DEFAULT] per-field. */
    fun fromMap(values: Map<String, Long>): Tuning {
        val chunkBytes = values["chunk_bytes"]?.takeIf { it > 0 }?.toInt() ?: DEFAULT.chunkBytes
        val connectTimeoutMs = values["connect_timeout_ms"]?.takeIf { it > 0 }
            ?: DEFAULT.connectTimeoutMs
        val resultTimeoutMs = values["result_timeout_ms"]?.takeIf { it > 0 }
            ?: DEFAULT.resultTimeoutMs
        return Tuning(chunkBytes, connectTimeoutMs, resultTimeoutMs)
    }

    /**
     * GETs {baseUrl}/v1/config. The callback always fires exactly once, with
     * the server values on success or [DEFAULT] on any failure.
     */
    fun fetch(baseUrl: String, token: String, callback: (Tuning) -> Unit) {
        val url = "${baseUrl.trim().trimEnd('/')}/v1/config"
        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        } catch (e: Exception) {
            callback(DEFAULT)
            return
        }

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                callback(DEFAULT)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    callback(DEFAULT)
                    return
                }
                callback(
                    try {
                        parse(JSONObject(body))
                    } catch (_: Exception) {
                        DEFAULT
                    }
                )
            }
        })
    }
}
