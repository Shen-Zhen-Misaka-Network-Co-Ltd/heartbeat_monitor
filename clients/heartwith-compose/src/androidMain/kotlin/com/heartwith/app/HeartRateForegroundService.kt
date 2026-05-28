package com.heartwith.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class HeartRateForegroundService : Service() {
    private var collector: AndroidHeartRateCollector? = null
    private var latestBpm: Int? = null
    private var foregroundStarted = false
    private var reconnectRetryCount = 0
    private var lastNotificationUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelScheduledResume(this)
            collector?.disconnect()
            HeartRateCollectorRuntime.clearIfCurrent(collector)
            collector = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val bpm = intent?.takeIf { it.hasExtra(EXTRA_BPM) }?.getIntExtra(EXTRA_BPM, 0)?.takeIf { it > 0 }
        if (bpm != null) latestBpm = bpm
        val appInForeground = intent?.getBooleanExtra(EXTRA_APP_FOREGROUND, false) ?: false
        val backgroundEnabled = true
        val notification = buildNotification(bpm ?: latestBpm, appInForeground, backgroundEnabled)
        val shouldResume = intent?.action == ACTION_RESUME_COLLECT || (intent == null && backgroundEnabled)
        val shouldEnterForeground = !foregroundStarted
        if (shouldEnterForeground) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foregroundStarted = true
            }.onFailure { error ->
                Log.w(TAG, "startForeground rejected; stop service to avoid crash", error)
                scheduleResume(this, BACKGROUND_RETRY_DELAY_MS)
                stopSelf(startId)
                return START_NOT_STICKY
            }
        } else {
            notifyStatus(notification)
        }
        if (shouldResume) {
            cancelScheduledResume(this)
            resumeCollectionFromPrefs()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleResume(this, TASK_REMOVED_RESTART_DELAY_MS)
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "心率采集状态",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "显示当前心率和前后台采集状态"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyStatus(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun updateRunningNotification(force: Boolean = false) {
        if (!foregroundStarted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateMs < BACKGROUND_NOTIFICATION_MIN_INTERVAL_MS) return
        lastNotificationUpdateMs = now
        notifyStatus(buildNotification(latestBpm, appInForeground = false, backgroundEnabled = true))
    }

    private fun buildNotification(
        bpm: Int?,
        appInForeground: Boolean,
        backgroundEnabled: Boolean,
    ): Notification {
        val mode = if (appInForeground) "前台模式" else "后台模式"
        val keepAlive = if (backgroundEnabled) "后台保活已开启" else "后台保活已关闭"
        val openIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_FROM_NOTIFICATION
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = bpm?.let { "当前心率 $it BPM" } ?: "正在后台保持采集和上传"
        val detail = bpm?.let {
            "当前心率 $it BPM\n$mode · $keepAlive · 正在采集并批量上传"
        } ?: "$mode · $keepAlive · 等待心率或重连"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heartwith_notification)
            .setColor(Color.rgb(10, 132, 255))
            .setContentTitle("Heartwith · $mode")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    private fun resumeCollectionFromPrefs() {
        val preferences = prefs()
        if (collector?.isCollectingOrConnecting() == true) {
            return
        }
        val address = preferences.getString(KEY_LAST_DEVICE_ADDRESS, null)?.takeIf { it.isNotBlank() }
        if (address == null) {
            return
        }
        val name = preferences.getString(KEY_LAST_DEVICE_NAME, null).orEmpty()
        val serverUrl = (preferences.getString(KEY_SERVER_URL, null) ?: DEFAULT_SERVER_URL).trimEnd('/')
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: (Build.MODEL ?: "Android")
        val nextCollector = HeartRateCollectorRuntime.get(applicationContext, serverUrl)
        collector = nextCollector
        nextCollector.setAppInForeground(false)
        nextCollector.connectAddress(
            address = address,
            name = name,
            displayName = displayName,
            onStatus = { status ->
                if (statusNeedsRetry(status)) {
                    latestBpm = null
                    scheduleResume(applicationContext, nextReconnectRetryDelayMs())
                } else if (status.startsWith("已订阅心率通知") || status.startsWith("已连接")) {
                    reconnectRetryCount = 0
                }
                updateRunningNotification(force = true)
            },
            onUploadStatus = {},
            onBpm = { bpm ->
                latestBpm = bpm
                reconnectRetryCount = 0
                updateRunningNotification()
            },
            scanFirst = true,
        )
    }

    private fun nextReconnectRetryDelayMs(): Long {
        val delayMs = if (reconnectRetryCount < FAST_RETRY_COUNT) {
            FAST_BACKGROUND_RETRY_DELAY_MS
        } else {
            BACKGROUND_RETRY_DELAY_MS
        }
        reconnectRetryCount += 1
        return delayMs
    }

    private fun statusNeedsRetry(status: String): Boolean {
        return status.startsWith("设备断开") ||
            status.startsWith("已断开") ||
            status.startsWith("重连失败") ||
            status.startsWith("后台重连失败") ||
            status.startsWith("后台扫描未发现") ||
            status.startsWith("连接超时") ||
            status.startsWith("发现服务失败") ||
            status.startsWith("订阅心率通知失败") ||
            status.startsWith("扫描失败") ||
            status.contains("连接失败")
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val CHANNEL_ID = "heartwith_collector_status_v2"
        private const val TAG = "HeartwithService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_OPEN_FROM_NOTIFICATION = "com.heartwith.app.OPEN_FROM_NOTIFICATION"
        private const val ACTION_START = "com.heartwith.app.COLLECTOR_SERVICE_START"
        private const val ACTION_RESUME_COLLECT = "com.heartwith.app.COLLECTOR_SERVICE_RESUME_COLLECT"
        const val ACTION_RESTART_COLLECT = "com.heartwith.app.COLLECTOR_SERVICE_RESTART_COLLECT"
        private const val ACTION_STOP = "com.heartwith.app.COLLECTOR_SERVICE_STOP"
        private const val EXTRA_BPM = "bpm"
        private const val EXTRA_APP_FOREGROUND = "app_foreground"
        const val PREFS_NAME = "heartwith_collector"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        const val KEY_LAST_DEVICE_NAME = "last_device_name"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
        const val KEY_COLLECTION_STOPPED = "collection_stopped"
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000"
        private const val RESTART_REQUEST_CODE = 23013
        private const val RESTART_DELAY_MS = 8_000L
        private const val TASK_REMOVED_RESTART_DELAY_MS = 3_000L
        private const val FAST_RETRY_COUNT = 3
        private const val FAST_BACKGROUND_RETRY_DELAY_MS = 20_000L
        private const val BACKGROUND_RETRY_DELAY_MS = 60_000L
        private const val BACKGROUND_NOTIFICATION_MIN_INTERVAL_MS = 10_000L

        fun start(context: Context, bpm: Int? = null, appInForeground: Boolean = false) {
            val intent = Intent(context, HeartRateForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_APP_FOREGROUND, appInForeground)
                bpm?.let { putExtra(EXTRA_BPM, it) }
            }
            runCatching {
                if (isRunning) {
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            }.onFailure { error ->
                Log.w(TAG, "status notification update rejected", error)
            }
        }

        fun resumeCollection(context: Context) {
            requestResume(context, reason = "manual", debounce = false)
        }

        fun autoStart(context: Context, reason: String) {
            requestResume(context, reason = reason, debounce = true)
        }

        private fun requestResume(context: Context, reason: String, debounce: Boolean) {
            val now = System.currentTimeMillis()
            if (debounce && now - lastAutoStartMs < AUTO_START_DEBOUNCE_MS) {
                return
            }
            if (!hasBackgroundResumeConfig(context)) {
                return
            }
            if (!canPostNotification(context)) {
                return
            }
            showResumeNotification(context)
            val intent = Intent(context, HeartRateForegroundService::class.java).apply {
                action = ACTION_RESUME_COLLECT
                putExtra(EXTRA_APP_FOREGROUND, false)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
                if (debounce) lastAutoStartMs = now
            }.onFailure { error ->
                Log.w(TAG, "foreground resume rejected for $reason", error)
                scheduleResume(context, BACKGROUND_RETRY_DELAY_MS)
            }
        }

        fun stop(context: Context) {
            cancelScheduledResume(context)
            val intent = Intent(context, HeartRateForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun scheduleResume(context: Context, delayMs: Long = RESTART_DELAY_MS) {
            if (!hasBackgroundResumeConfig(context)) {
                return
            }
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            val triggerAtMs = SystemClock.elapsedRealtime() + delayMs
            val pendingIntent = restartPendingIntent(context)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMs,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent)
                }
            }.onFailure {
                Log.w(TAG, "exact background resume scheduling failed; using allow-while-idle alarm", it)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMs,
                        pendingIntent,
                    )
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMs, pendingIntent)
                }
            }
        }

        fun cancelScheduledResume(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(restartPendingIntent(context))
        }

        fun hasBackgroundResumeConfig(context: Context): Boolean {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return !preferences.getBoolean(KEY_COLLECTION_STOPPED, false) &&
                !preferences.getString(KEY_LAST_DEVICE_ADDRESS, null).isNullOrBlank()
        }

        private fun canPostNotification(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
        }

        fun showResumeNotification(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "心率采集状态",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "显示当前心率和前后台采集状态"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
                context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val openIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_FROM_NOTIFICATION
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_heartwith_notification)
                .setColor(Color.rgb(10, 132, 255))
                .setContentTitle("Heartwith · 后台恢复中")
                .setContentText("正在恢复心率采集，点击打开应用")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("后台模式 · 正在恢复上次心率设备连接"),
                )
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }

        private fun restartPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, HeartwithRestartReceiver::class.java).apply {
                action = ACTION_RESTART_COLLECT
                setPackage(context.packageName)
            }
            return PendingIntent.getBroadcast(
                context,
                RESTART_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        @Volatile
        private var isRunning = false

        @Volatile
        private var lastAutoStartMs = 0L

        private const val AUTO_START_DEBOUNCE_MS = 1_000L
    }
}
