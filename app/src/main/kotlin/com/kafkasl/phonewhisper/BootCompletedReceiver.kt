package com.kafkasl.phonewhisper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores dictation availability after reboot. The accessibility overlay
 * itself restarts automatically when the service is enabled; this receiver
 * refreshes the cached gateway tuning early so the first post-boot
 * dictation already uses server-provided chunk sizes and timeouts.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed — overlay service restores automatically when enabled")
        try {
            val prefs = context.getSharedPreferences("phonewhisper", Context.MODE_PRIVATE)
            val baseUrl = (prefs.getString("gateway_url", "") ?: "").trim()
            val token = prefs.getString("gateway_token", "") ?: ""
            if (baseUrl.isBlank() || token.isBlank()) return
            GatewayTuning.fetch(baseUrl, token) { fresh ->
                prefs.edit()
                    .putLong("tuning_chunk_bytes", fresh.chunkBytes.toLong())
                    .putLong("tuning_connect_timeout_ms", fresh.connectTimeoutMs)
                    .putLong("tuning_result_timeout_ms", fresh.resultTimeoutMs)
                    .apply()
                Log.i(TAG, "Refreshed gateway tuning at boot")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not refresh gateway tuning at boot", t)
        }
    }

    companion object {
        private const val TAG = "PhoneWhisper"
    }
}
