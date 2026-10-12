package com.kafkasl.phonewhisper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores dictation availability after reboot. The accessibility overlay
 * itself restarts automatically when the service is enabled; this receiver
 * exists so the app gets an early wakeup to refresh cached gateway tuning
 * and to log that the overlay is expected back.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed — overlay service restores automatically when enabled")
    }

    companion object {
        private const val TAG = "PhoneWhisper"
    }
}
