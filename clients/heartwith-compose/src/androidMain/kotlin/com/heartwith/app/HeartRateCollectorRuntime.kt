package com.heartwith.app

import android.content.Context
import com.heartwith.shared.HeartwithApi

object HeartRateCollectorRuntime {
    private var collector: AndroidHeartRateCollector? = null
    private var serverUrlKey: String? = null

    @Synchronized
    fun get(context: Context, serverUrl: String): AndroidHeartRateCollector {
        val normalizedServerUrl = serverUrl.trimEnd('/')
        val current = collector
        if (current != null && serverUrlKey == normalizedServerUrl) {
            return current
        }
        current?.disconnect()
        return AndroidHeartRateCollector(
            context = context.applicationContext,
            api = HeartwithApi(normalizedServerUrl),
            serverUrl = normalizedServerUrl,
        ).also {
            collector = it
            serverUrlKey = normalizedServerUrl
        }
    }

    @Synchronized
    fun clearIfCurrent(instance: AndroidHeartRateCollector?) {
        if (collector == instance) {
            collector = null
            serverUrlKey = null
        }
    }
}
