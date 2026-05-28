package com.heartwith.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class HeartwithRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != HeartRateForegroundService.ACTION_RESTART_COLLECT) return
        if (!HeartRateForegroundService.hasBackgroundResumeConfig(context)) {
            return
        }
        runCatching { HeartRateForegroundService.autoStart(context, "alarm") }
            .onFailure { error -> Log.w(TAG, "failed to restart background collection", error) }
    }

    private companion object {
        const val TAG = "HeartwithRestart"
    }
}
