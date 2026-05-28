package com.heartwith.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class HeartwithAutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_QUICKBOOT_POWERON &&
            action != ACTION_HTC_QUICKBOOT_POWERON
        ) {
            return
        }
        if (!HeartRateForegroundService.hasBackgroundResumeConfig(context)) {
            return
        }
        runCatching { HeartRateForegroundService.autoStart(context, action) }
            .onFailure { error -> Log.w(TAG, "failed to resume background collection", error) }
    }

    private companion object {
        const val TAG = "HeartwithAutoStart"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
