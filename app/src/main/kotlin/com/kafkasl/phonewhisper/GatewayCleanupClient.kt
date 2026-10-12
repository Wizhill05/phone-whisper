package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Lightweight HTTP client for remote text cleanup via the VPS gateway (POST /v1/cleanup).
 *
 * Used for the single separate cleanup pass when a dictation result contains
 * raw text only (no clean_text). On any network or parsing failure, it safely
 * falls back to the original raw text.
 */
object GatewayCleanupClient {

    data class Result(val text: String, val cleanText: String, val error: String?)

    private const val TIMEOUT_SECONDS = 5L

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun clean(
        text: String,
        baseUrl: String,
        token: String,
        prompt: String? = null,
        callback: (Result) -> Unit,
    ) {
        val trimmedBase = baseUrl.trim().trimEnd('/')
        if (trimmedBase.isBlank()) {
            callback(Result(text = text, cleanText = text, error = "Empty gateway URL"))
            return
        }

        val url = try {
            if (!trimmedBase.startsWith("http://") && !trimmedBase.startsWith("https://")) {
                callback(Result(text = text, cleanText = text, error = "Gateway URL must start with http:// or https://"))
                return
            }
            "$trimmedBase/v1/cleanup"
        } catch (e: Exception) {
            callback(Result(text = text, cleanText = text, error = "Invalid URL: ${e.message}"))
            return
        }

        val jsonBody = JSONObject().apply {
            put("text", text)
            if (!prompt.isNullOrBlank()) {
                put("prompt", prompt)
            }
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(
                    Result(
                        text = text,
                        cleanText = text,
                        error = "Gateway unreachable: ${e.message ?: "connection failed"}",
                    )
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string().orEmpty()
                when {
                    response.code == 401 || response.code == 403 -> {
                        callback(
                            Result(
                                text = text,
                                cleanText = text,
                                error = "Invalid gateway token",
                            )
                        )
                    }
                    !response.isSuccessful -> {
                        callback(
                            Result(
                                text = text,
                                cleanText = text,
                                error = "Gateway error (HTTP ${response.code})",
                            )
                        )
                    }
                    else -> {
                        try {
                            val json = JSONObject(responseBody)
                            val clean = if (!json.isNull("clean_text")) {
                                json.getString("clean_text")
                            } else {
                                json.optString("text", text)
                            }
                            val raw = if (!json.isNull("text")) {
                                json.getString("text")
                            } else {
                                text
                            }
                            val resolvedClean = if (clean.isNotBlank()) clean else text
                            callback(Result(text = raw, cleanText = resolvedClean, error = null))
                        } catch (e: Exception) {
                            callback(
                                Result(
                                    text = text,
                                    cleanText = text,
                                    error = "Could not parse gateway response: ${e.message ?: "bad json"}",
                                )
                            )
                        }
                    }
                }
            }
        })
    }
}
