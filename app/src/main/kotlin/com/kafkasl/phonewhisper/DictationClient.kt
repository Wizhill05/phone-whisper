package com.kafkasl.phonewhisper

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the self-hosted dictation gateway (POST /v1/dictation).
 * Mirrors TranscriberClient's callback style so the accessibility service
 * integration stays identical to the upstream cloud path.
 */
object DictationClient {

    data class Result(val text: String?, val error: String?)

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 60L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun dictate(wav: ByteArray, baseUrl: String, token: String, callback: (Result) -> Unit) {
        try {
            val url = "${baseUrl.trimEnd('/')}/v1/dictation"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("clean", "true")
                .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    callback(Result(null, "Gateway unreachable: ${e.message ?: "connection failed"}"))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val responseBody = response.body?.string() ?: ""
                    when {
                        response.code == 401 || response.code == 403 ->
                            callback(Result(null, "Invalid gateway token"))
                        !response.isSuccessful ->
                            callback(Result(null, "Gateway error (HTTP ${response.code})"))
                        else -> try {
                            val json = JSONObject(responseBody)
                            // Prefer the cleaned transcript; fall back to raw text
                            // when the gateway's LLM cleanup failed (cleanup=failed).
                            val text = if (!json.isNull("clean_text")) json.getString("clean_text")
                            else json.optString("text", "")
                            if (text.isBlank()) {
                                callback(Result(null, "Empty transcript"))
                            } else {
                                callback(Result(text, null))
                            }
                        } catch (e: Exception) {
                            callback(Result(null, "Could not parse gateway response: ${e.message ?: "bad json"}"))
                        }
                    }
                }
            })
        } catch (e: Exception) {
            // Bad URL, OOM, anything: never let an exception escape to the service.
            callback(Result(null, "Could not reach gateway: ${e.message ?: e.javaClass.simpleName}"))
        }
    }
}
