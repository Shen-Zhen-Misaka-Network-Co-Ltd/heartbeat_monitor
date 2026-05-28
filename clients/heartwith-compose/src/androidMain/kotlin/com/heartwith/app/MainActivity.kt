package com.heartwith.app

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.heartwith.shared.HeartwithScreen
import com.heartwith.shared.HeartwithTheme
import com.heartwith.shared.HeartwithUiState

class MainActivity : ComponentActivity() {
    private var activeCollector: AndroidHeartRateCollector? = null
    private var appInForeground = true

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestBluetoothPermissions()

        setContent {
            HeartwithTheme {
                val preferences = remember {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                }
                var serverUrl by remember {
                    mutableStateOf(preferences.getString(KEY_SERVER_URL, null) ?: "http://10.0.2.2:8000")
                }
                var displayName by remember {
                    mutableStateOf(preferences.getString(KEY_DISPLAY_NAME, null) ?: (Build.MODEL ?: "Android"))
                }
                val backgroundEnabled = true
                LaunchedEffect(Unit) {
                    preferences.edit().putBoolean(KEY_BACKGROUND_ENABLED, true).apply()
                }
                var hideFromRecents by remember {
                    mutableStateOf(preferences.getBoolean(KEY_HIDE_FROM_RECENTS, false))
                }
                processBackgroundEnabled = backgroundEnabled
                LaunchedEffect(hideFromRecents) {
                    applyExcludeFromRecents(hideFromRecents)
                }
                var autoScanStarted by remember { mutableStateOf(false) }
                var autoConnectAttempted by remember { mutableStateOf(false) }
                val collector = remember(serverUrl) {
                    HeartRateCollectorRuntime.get(this, serverUrl)
                }
                activeCollector = collector
                var state by remember {
                    mutableStateOf(
                        HeartwithUiState(
                            serverUrl = serverUrl,
                            displayName = displayName,
                            localStatus = "等待开始，建议先在系统蓝牙中确保手环可被发现",
                            localBpm = null,
                            participants = emptyList(),
                            backgroundEnabled = backgroundEnabled,
                            hideFromRecents = hideFromRecents,
                        ),
                    )
                }

                fun onUi(block: () -> Unit) {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        block()
                    } else {
                        runOnUiThread(block)
                    }
                }

                fun updateCollectStatus(status: String) {
                    onUi {
                        if (status.startsWith("设备断开")) {
                            processNotificationBpm = null
                            showHeartRateNotification(null)
                        } else if (
                            status.startsWith("已断开") ||
                            status.startsWith("设备没有标准心率特征")
                        ) {
                            processNotificationBpm = null
                            hideHeartRateNotification()
                        }
                        state = state.copy(
                            localStatus = status,
                            devices = if (
                                status.startsWith("已连接") ||
                                status.startsWith("已订阅") ||
                                status.startsWith("收到心率")
                            ) {
                                emptyList()
                            } else {
                                state.devices
                            },
                        )
                    }
                }

                fun syncCollectorSnapshot() {
                    val snapshot = collector.snapshot()
                    if (!snapshot.collectingOrConnecting) return
                    processCollectorServiceActive = true
                    processNotificationBpm = snapshot.bpm
                    state = state.copy(
                        scanning = false,
                        collecting = true,
                        devices = emptyList(),
                        localStatus = snapshot.bpm?.let { bpm ->
                            "收到心率 $bpm BPM"
                        } ?: snapshot.status,
                        uploadStatus = snapshot.uploadStatus,
                        localBpm = snapshot.bpm,
                        backgroundEnabled = backgroundEnabled,
                        hideFromRecents = hideFromRecents,
                    )
                }

                DisposableEffect(collector) {
                    collector.setPassiveListener(
                        onStatus = { status -> updateCollectStatus(status) },
                        onUploadStatus = { status -> onUi { state = state.copy(uploadStatus = status) } },
                        onBpm = { bpm ->
                            onUi {
                                processNotificationBpm = bpm
                                state = state.copy(
                                    collecting = true,
                                    scanning = false,
                                    devices = emptyList(),
                                    localBpm = bpm,
                                    localStatus = "收到心率 $bpm BPM",
                                )
                            }
                        },
                    )
                    syncCollectorSnapshot()
                    onDispose { collector.clearPassiveListener() }
                }

                fun connectDevice(device: com.heartwith.shared.BleDeviceCandidate) {
                    preferences.edit()
                        .putString(KEY_LAST_DEVICE_ADDRESS, device.address)
                        .putString(KEY_LAST_DEVICE_NAME, device.name)
                        .putString(deviceNameKey(device.address), device.name)
                        .putBoolean(KEY_COLLECTION_STOPPED, false)
                        .apply()
                    state = state.copy(
                        scanning = false,
                        collecting = true,
                        localStatus = "准备连接 ${device.name}",
                        uploadStatus = "等待心率数据",
                        backgroundEnabled = backgroundEnabled,
                        hideFromRecents = hideFromRecents,
                    )
                    showHeartRateNotification(null, backgroundEnabled)
                    collector.connectCandidate(
                        candidate = device,
                        displayName = displayName.ifBlank { Build.MODEL ?: "Android" },
                        onStatus = { status -> updateCollectStatus(status) },
                        onUploadStatus = { status -> onUi { state = state.copy(uploadStatus = status) } },
                        onBpm = { bpm ->
                            onUi {
                                processNotificationBpm = bpm
                                state = state.copy(localBpm = bpm)
                                showHeartRateNotification(bpm, backgroundEnabled)
                            }
                        },
                    )
                }

                lateinit var scanDevices: (String?) -> Unit

                fun connectLastDevice(address: String, name: String) {
                    preferences.edit().putBoolean(KEY_COLLECTION_STOPPED, false).apply()
                    state = state.copy(
                        scanning = false,
                        collecting = true,
                        devices = emptyList(),
                        localStatus = "正在连接上次设备 ${name.ifBlank { address }}",
                        uploadStatus = "等待心率数据",
                        backgroundEnabled = backgroundEnabled,
                        hideFromRecents = hideFromRecents,
                    )
                    showHeartRateNotification(null, backgroundEnabled)
                    collector.connectAddress(
                        address = address,
                        name = name,
                        displayName = displayName.ifBlank { Build.MODEL ?: "Android" },
                        onStatus = { status ->
                            updateCollectStatus(status)
                            if (
                                status.startsWith("重连失败") ||
                                status.startsWith("连接超时") ||
                                status.startsWith("发现服务失败") ||
                                status.startsWith("订阅心率通知失败")
                            ) {
                                onUi {
                                    state = state.copy(
                                        collecting = false,
                                        localBpm = null,
                                        uploadStatus = "未上传",
                                    )
                                    scanDevices(address)
                                }
                            }
                        },
                        onUploadStatus = { status -> onUi { state = state.copy(uploadStatus = status) } },
                        onBpm = { bpm ->
                            onUi {
                                processNotificationBpm = bpm
                                state = state.copy(localBpm = bpm)
                                showHeartRateNotification(bpm, backgroundEnabled)
                            }
                        },
                        scanFirst = true,
                    )
                }

                scanDevices = { autoConnectAddress ->
                    state = state.copy(scanning = true, devices = emptyList(), localStatus = "正在扫描手环")
                    collector.scanDevices(
                        onStatus = { status -> updateCollectStatus(status) },
                        onDevices = { devices ->
                            onUi {
                                state = state.copy(devices = devices)
                                val target = autoConnectAddress?.let { address ->
                                    devices.firstOrNull { it.address.equals(address, ignoreCase = true) }
                                }
                                if (target != null && !autoConnectAttempted && !state.collecting) {
                                    autoConnectAttempted = true
                                    connectDevice(target)
                                }
                            }
                        },
                        onDone = { onUi { state = state.copy(scanning = false) } },
                    )
                }

                LaunchedEffect(Unit) {
                    val snapshot = collector.snapshot()
                    if (snapshot.collectingOrConnecting) {
                        autoScanStarted = true
                        syncCollectorSnapshot()
                        return@LaunchedEffect
                    }
                    if (
                        !autoScanStarted &&
                        !isOpenedFromNotification() &&
                        !collector.isCollectingOrConnecting()
                    ) {
                        autoScanStarted = true
                        val stopped = preferences.getBoolean(KEY_COLLECTION_STOPPED, false)
                        val lastAddress = preferences.getString(KEY_LAST_DEVICE_ADDRESS, null)
                            ?.takeIf { it.isNotBlank() }
                        when {
                            stopped -> {
                                state = state.copy(
                                    collecting = false,
                                    scanning = false,
                                    localBpm = null,
                                    localStatus = "采集已关闭，点击卡片或扫描重新开始",
                                    uploadStatus = "未上传",
                                )
                            }
                            lastAddress != null -> {
                                connectLastDevice(
                                    address = lastAddress,
                                    name = preferences.getString(KEY_LAST_DEVICE_NAME, null).orEmpty(),
                                )
                            }
                            else -> {
                                scanDevices(null)
                            }
                        }
                    }
                }

                HeartwithScreen(
                    state = state.copy(
                        serverUrl = serverUrl,
                        displayName = displayName,
                        backgroundEnabled = backgroundEnabled,
                        hideFromRecents = hideFromRecents,
                    ),
                    canCollect = true,
                    showLobby = false,
                    showServerUrl = true,
                    onServerUrlChange = {
                        if (!state.collecting) {
                            serverUrl = it
                            preferences.edit().putString(KEY_SERVER_URL, it).apply()
                        }
                    },
                    onDisplayNameChange = {
                        if (!state.collecting) {
                            displayName = it
                            preferences.edit().putString(KEY_DISPLAY_NAME, it).apply()
                        }
                    },
                    onHideFromRecentsChange = { enabled ->
                        hideFromRecents = enabled
                        preferences.edit().putBoolean(KEY_HIDE_FROM_RECENTS, enabled).apply()
                        applyExcludeFromRecents(enabled)
                        state = state.copy(hideFromRecents = enabled)
                    },
                    onOpenAutoStartSettings = {
                        openAppSettings()
                    },
                    onScanDevices = {
                        scanDevices(null)
                    },
                    onConnectDevice = { device -> connectDevice(device) },
                    onDisconnect = {
                        processNotificationBpm = null
                        HeartRateForegroundService.cancelScheduledResume(this)
                        hideHeartRateNotification()
                        state = state.copy(
                            collecting = false,
                            scanning = false,
                            localBpm = null,
                            localStatus = "正在关闭采集",
                            uploadStatus = "未上传",
                            backgroundEnabled = backgroundEnabled,
                            hideFromRecents = hideFromRecents,
                        )
                        collector.disconnect(
                            onStatus = { status -> updateCollectStatus(status) },
                            onUploadStatus = { status -> onUi { state = state.copy(uploadStatus = status) } },
                        )
                    },
                    onCloseCollection = {
                        processNotificationBpm = null
                        preferences.edit().putBoolean(KEY_COLLECTION_STOPPED, true).apply()
                        HeartRateForegroundService.cancelScheduledResume(this)
                        hideHeartRateNotification()
                        state = state.copy(
                            collecting = false,
                            scanning = false,
                            localBpm = null,
                            localStatus = "正在关闭采集",
                            uploadStatus = "未上传",
                            backgroundEnabled = backgroundEnabled,
                            hideFromRecents = hideFromRecents,
                        )
                        collector.disconnect(
                            onStatus = { status ->
                                onUi {
                                    state = state.copy(
                                        collecting = false,
                                        scanning = false,
                                        localBpm = null,
                                        localStatus = "采集已关闭，点击卡片或扫描重新开始",
                                        uploadStatus = "未上传",
                                    )
                                }
                            },
                            onUploadStatus = { status -> onUi { state = state.copy(uploadStatus = status) } },
                        )
                    },
                    onRefresh = {},
                    onStartCollect = {
                        autoConnectAttempted = false
                        preferences.edit().putBoolean(KEY_COLLECTION_STOPPED, false).apply()
                        state = state.copy(
                            collecting = false,
                            localBpm = null,
                            localStatus = "正在扫描心率设备",
                            uploadStatus = "未上传",
                            backgroundEnabled = backgroundEnabled,
                            hideFromRecents = hideFromRecents,
                        )
                        scanDevices(preferences.getString(KEY_LAST_DEVICE_ADDRESS, null))
                    },
                )
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissions.launch(requested)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        applyExcludeFromRecents(
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_HIDE_FROM_RECENTS, false),
        )
        HeartRateForegroundService.cancelScheduledResume(this)
        setAppForegroundState(true)
    }

    override fun onResume() {
        super.onResume()
        setAppForegroundState(true)
    }

    override fun onPause() {
        setAppForegroundState(false)
        super.onPause()
    }

    override fun onStop() {
        setAppForegroundState(false)
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            setAppForegroundState(false)
        }
        super.onDestroy()
    }

    private fun setAppForegroundState(inForeground: Boolean) {
        appInForeground = inForeground
        activeCollector?.setAppInForeground(inForeground)
        if (processCollectorServiceActive) {
            showHeartRateNotification(processNotificationBpm, processBackgroundEnabled, force = true)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            HeartRateForegroundService.CHANNEL_ID,
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

    private fun applyExcludeFromRecents(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        runCatching {
            getSystemService(ActivityManager::class.java).appTasks.forEach { task ->
                task.setExcludeFromRecents(enabled)
            }
        }
    }

    private fun showHeartRateNotification(
        bpm: Int?,
        backgroundEnabled: Boolean = processBackgroundEnabled,
        force: Boolean = false,
    ) {
        processBackgroundEnabled = backgroundEnabled
        if (!backgroundEnabled) return
        val now = System.currentTimeMillis()
        if (!force && bpm != null && now - processLastNotificationUpdateMs < NOTIFICATION_MIN_INTERVAL_MS) {
            return
        }
        processLastNotificationUpdateMs = now
        processCollectorServiceActive = true
        HeartRateForegroundService.start(this, bpm, appInForeground)
    }

    private fun hideHeartRateNotification() {
        processCollectorServiceActive = false
        HeartRateForegroundService.stop(this)
    }

    private fun isOpenedFromNotification(): Boolean {
        return intent?.action == HeartRateForegroundService.ACTION_OPEN_FROM_NOTIFICATION
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private companion object {
        const val PREFS_NAME = "heartwith_collector"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_LAST_DEVICE_ADDRESS = "last_device_address"
        const val KEY_LAST_DEVICE_NAME = "last_device_name"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
        const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
        const val KEY_COLLECTION_STOPPED = "collection_stopped"
        var processCollectorServiceActive: Boolean = false
        var processNotificationBpm: Int? = null
        var processBackgroundEnabled: Boolean = true
        var processLastNotificationUpdateMs: Long = 0L
        const val NOTIFICATION_MIN_INTERVAL_MS = 5_000L

        fun deviceNameKey(address: String): String = "device_name_${address.replace(':', '_')}"
    }
}
