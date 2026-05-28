package com.heartwith.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.heartwith.shared.BleDeviceCandidate
import androidx.core.content.ContextCompat
import com.heartwith.shared.BleInfo
import com.heartwith.shared.HR_MEASUREMENT_UUID
import com.heartwith.shared.HR_SERVICE_UUID
import com.heartwith.shared.HeartRateBatcher
import com.heartwith.shared.HeartwithApi
import com.heartwith.shared.SessionRequest
import com.heartwith.shared.SessionResponse
import com.heartwith.shared.nowMs
import com.heartwith.shared.parseHeartRateMeasurementDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AndroidHeartRateCollector(
    private val context: Context,
    private val api: HeartwithApi,
    private val serverUrl: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var session: SessionResponse? = null
    private var sessionDeviceModel: String = DEFAULT_DEVICE_MODEL
    private var batcher = HeartRateBatcher()
    private var seq = 1L
    private var latestRssi: Int? = null
    private var currentGatt: BluetoothGatt? = null
    private var shouldReconnect = true
    private var activeScanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var uploadInFlight = false
    private var nextUploadAttemptMs = 0L
    private var appInForeground = true
    private var lastDevice: BluetoothDevice? = null
    private var latestStatus: String = "等待开始"
    private var latestUploadStatus: String = "未上传"
    private var latestBpm: Int? = null
    private var connectedDeviceName: String = DEFAULT_DEVICE_MODEL
    private var backgroundReconnectJob: Job? = null
    private var passiveStatusListener: ((String) -> Unit)? = null
    private var passiveUploadStatusListener: ((String) -> Unit)? = null
    private var passiveBpmListener: ((Int) -> Unit)? = null
    private val operationId = AtomicInteger(0)
    private val discoveredDevices = linkedMapOf<String, BluetoothDevice>()

    fun isCollectingOrConnecting(): Boolean {
        return currentGatt != null || activeScanCallback != null || backgroundReconnectJob?.isActive == true
    }

    @Synchronized
    fun setPassiveListener(
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
    ) {
        passiveStatusListener = onStatus
        passiveUploadStatusListener = onUploadStatus
        passiveBpmListener = onBpm
    }

    @Synchronized
    fun clearPassiveListener() {
        passiveStatusListener = null
        passiveUploadStatusListener = null
        passiveBpmListener = null
    }

    @Synchronized
    fun snapshot(): CollectorSnapshot {
        return CollectorSnapshot(
            collectingOrConnecting = isCollectingOrConnecting(),
            status = latestStatus,
            uploadStatus = latestUploadStatus,
            bpm = latestBpm,
        )
    }

    fun start(
        displayName: String,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
    ) {
        val opId = nextOperationId()
        scope.launch {
            runCatching {
                shouldReconnect = true
                scanAndConnect(displayName, DEFAULT_DEVICE_MODEL, onStatus, onUploadStatus, onBpm, opId)
            }.onFailure { error ->
                if (isCurrentOperation(opId)) reportStatus("采集启动失败：${error.message}", onStatus)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scanDevices(
        onStatus: (String) -> Unit,
        onDevices: (List<BleDeviceCandidate>) -> Unit,
        onDone: () -> Unit,
    ) {
        val opId = nextOperationId()
        scope.launch {
            runCatching {
                val scanner = adapter?.bluetoothLeScanner ?: error("蓝牙不可用")
                if (!hasBlePermission()) error("缺少蓝牙权限")

                stopActiveScan()
                discoveredDevices.clear()
                val candidates = linkedMapOf<String, BleDeviceCandidate>()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build()

                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (!isCurrentOperation(opId)) return
                        val candidate = result.toCandidate()

                        discoveredDevices[candidate.address] = result.device
                        rememberDeviceName(candidate.address, candidate.name)
                        candidates[candidate.address] = candidate
                        onDevices(
                            candidates.values
                                .sortedWith(
                                    compareByDescending<BleDeviceCandidate> { it.hasHeartRateService }
                                        .thenByDescending { it.rssi },
                                ),
                        )
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        if (!isCurrentOperation(opId)) return
                        results.forEach { result ->
                            val candidate = result.toCandidate()
                            discoveredDevices[candidate.address] = result.device
                            rememberDeviceName(candidate.address, candidate.name)
                            candidates[candidate.address] = candidate
                        }
                        onDevices(
                            candidates.values
                                .sortedWith(
                                    compareByDescending<BleDeviceCandidate> { it.hasHeartRateService }
                                        .thenByDescending { it.isLikelyHeartRateBand() }
                                        .thenByDescending { it.rssi },
                                ),
                        )
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (!isCurrentOperation(opId)) return
                        reportStatus("扫描失败：$errorCode", onStatus)
                        activeScanner = null
                        activeScanCallback = null
                        onDone()
                    }
                }

                reportStatus("正在扫描附近手环和心率设备", onStatus)
                activeScanner = scanner
                activeScanCallback = callback
                scanner.startScan(null, settings, callback)
                delay(SCAN_WINDOW_MS)
                if (isCurrentOperation(opId) && activeScanCallback == callback) {
                    stopActiveScan()
                    reportStatus(if (candidates.isEmpty()) "未发现蓝牙设备，请确认蓝牙和定位权限" else "请选择要连接的设备", onStatus)
                    onDone()
                }
            }.onFailure { error ->
                if (isCurrentOperation(opId)) {
                    reportStatus("扫描失败：${error.message}", onStatus)
                    stopActiveScan()
                    onDone()
                }
            }
        }
    }

    fun connectCandidate(
        candidate: BleDeviceCandidate,
        displayName: String,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
    ) {
        val opId = nextOperationId()
        scope.launch {
            runCatching {
                val device = discoveredDevices[candidate.address]
                    ?: adapter?.getRemoteDevice(candidate.address)
                    ?: error("无法恢复设备 ${candidate.address}，请重新扫描")
                discoveredDevices[candidate.address] = device
                rememberDeviceName(candidate.address, candidate.name)
                stopActiveScan()
                shouldReconnect = true
                latestRssi = candidate.rssi
                val deviceModel = candidate.name.toDeviceModel()
                connectedDeviceName = deviceModel
                reportStatus("正在连接 ${candidate.name}", onStatus)
                reportUploadStatus("等待心率数据", onUploadStatus)
                connect(device, displayName, deviceModel, onStatus, onUploadStatus, onBpm, opId)
            }.onFailure { error ->
                if (isCurrentOperation(opId)) reportStatus("连接失败：${error.message}", onStatus)
            }
        }
    }

    fun connectAddress(
        address: String,
        name: String,
        displayName: String,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
        scanFirst: Boolean = false,
    ) {
        scope.launch {
            val opId = nextOperationId()
            runCatching {
                if (!hasBlePermission()) error("缺少蓝牙权限")
                stopActiveScan()
                shouldReconnect = true
                latestRssi = null
                val deviceModel = name.toDeviceModel()
                connectedDeviceName = deviceModel
                reportUploadStatus("等待心率数据", onUploadStatus)
                if (scanFirst) {
                    reportStatus("正在扫描上次设备 ${name.ifBlank { address }}", onStatus)
                    scanAndConnect(
                        displayName = displayName,
                        deviceModel = deviceModel,
                        onStatus = onStatus,
                        onUploadStatus = onUploadStatus,
                        onBpm = onBpm,
                        opId = opId,
                        targetAddress = address,
                        timeoutMs = TARGET_SCAN_TIMEOUT_MS,
                    )
                } else {
                    val device = discoveredDevices[address] ?: adapter?.getRemoteDevice(address)
                        ?: error("无法恢复设备 $address")
                    discoveredDevices[address] = device
                    reportStatus("正在重连 ${name.ifBlank { address }}", onStatus)
                    connect(device, displayName, deviceModel, onStatus, onUploadStatus, onBpm, opId)
                }
            }.onFailure { error ->
                if (isCurrentOperation(opId)) reportStatus("重连失败：${error.message}", onStatus)
            }
        }
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        if (!inForeground) {
            stopScanning()
        } else {
            backgroundReconnectJob?.cancel()
            backgroundReconnectJob = null
        }
    }

    fun stopScanning() {
        stopActiveScan()
    }

    @SuppressLint("MissingPermission")
    fun disconnect(
        onStatus: (String) -> Unit = {},
        onUploadStatus: (String) -> Unit = {},
    ) {
        val opId = nextOperationId()
        scope.launch {
            shouldReconnect = false
            backgroundReconnectJob?.cancel()
            backgroundReconnectJob = null
            session = null
            sessionDeviceModel = DEFAULT_DEVICE_MODEL
            batcher = HeartRateBatcher()
            seq = 1L
            latestRssi = null
            latestBpm = null
            uploadInFlight = false
            nextUploadAttemptMs = 0L
            stopActiveScan()

            closeCurrentGatt(disconnectFirst = true, settleMs = 300)
            reportUploadStatus("未上传", onUploadStatus)
            if (isCurrentOperation(opId)) {
                reportStatus("已断开，可修改服务器地址和显示名称", onStatus)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanAndConnect(
        displayName: String,
        deviceModel: String,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
        opId: Int,
        targetAddress: String? = null,
        timeoutMs: Long? = null,
    ) {
        val scanner = adapter?.bluetoothLeScanner ?: error("蓝牙不可用")
        if (!hasBlePermission()) error("缺少蓝牙权限")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isCurrentOperation(opId)) return
                if (!result.isAcceptableAutoConnectTarget(targetAddress)) return
                latestRssi = result.rssi
                val candidate = result.toCandidate()
                val actualDeviceModel = preferredDeviceModel(candidate.name.toDeviceModel(), deviceModel)
                connectedDeviceName = actualDeviceModel
                rememberDeviceName(candidate.address, candidate.name)
                discoveredDevices[result.device.address] = result.device
                stopActiveScan()
                reportStatus("发现设备 $actualDeviceModel，正在连接", onStatus)
                reportUploadStatus("等待心率数据", onUploadStatus)
                connect(result.device, displayName, actualDeviceModel, onStatus, onUploadStatus, onBpm, opId)
            }

            override fun onScanFailed(errorCode: Int) {
                if (!isCurrentOperation(opId)) return
                reportStatus("扫描失败：$errorCode", onStatus)
            }
        }

        reportStatus("低功耗扫描附近 BLE 设备", onStatus)
        activeScanner = scanner
        activeScanCallback = callback
        scanner.startScan(null, settings, callback)
        if (timeoutMs != null) {
            delay(timeoutMs)
            if (isCurrentOperation(opId) && activeScanCallback == callback) {
                stopActiveScan()
                reportStatus("后台扫描未发现上次设备，稍后重试", onStatus)
            }
        }
    }

    private fun reportStatus(status: String, onStatus: (String) -> Unit) {
        latestStatus = status
        onStatus(status)
        passiveStatusListener?.invoke(status)
    }

    private fun reportUploadStatus(status: String, onUploadStatus: (String) -> Unit) {
        latestUploadStatus = status
        onUploadStatus(status)
        passiveUploadStatusListener?.invoke(status)
    }

    private fun reportBpm(bpm: Int, onBpm: (Int) -> Unit) {
        latestBpm = bpm
        onBpm(bpm)
        passiveBpmListener?.invoke(bpm)
    }

    @SuppressLint("MissingPermission")
    private fun stopActiveScan() {
        val scanner = activeScanner
        val callback = activeScanCallback
        if (scanner != null && callback != null && hasBlePermission()) {
            runCatching { scanner.stopScan(callback) }
        }
        activeScanner = null
        activeScanCallback = null
    }

    private suspend fun ensureSession(
        displayName: String,
        deviceModel: String,
        onUploadStatus: (String) -> Unit,
    ) {
        val actualDeviceModel = preferredDeviceModel(deviceModel, currentDeviceModel())
        if (actualDeviceModel == DEFAULT_DEVICE_MODEL) {
            reportUploadStatus("等待蓝牙设备名称，暂不创建上传会话", onUploadStatus)
            error(WAITING_FOR_DEVICE_NAME)
        }
        if (session != null && sessionDeviceModel == actualDeviceModel) return
        if (session != null && sessionDeviceModel != DEFAULT_DEVICE_MODEL) return
        reportUploadStatus("正在创建上传会话", onUploadStatus)
        session = api.createSession(
            SessionRequest(
                displayName = displayName,
                deviceModel = actualDeviceModel,
                clientPlatform = "android",
                appVersion = "0.1.0",
            ),
        )
        sessionDeviceModel = actualDeviceModel
        batcher = HeartRateBatcher(session!!.policy)
        reportUploadStatus("会话已创建，等待上传", onUploadStatus)
    }

    @SuppressLint("MissingPermission")
    private fun connect(
        device: BluetoothDevice,
        displayName: String,
        deviceModel: String,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
        opId: Int,
    ) {
        if (!isCurrentOperation(opId)) return
        lastDevice = device
        connectedDeviceName = preferredDeviceModel(deviceModel, bluetoothDeviceName(device).orEmpty().toDeviceModel())
        backgroundReconnectJob?.cancel()
        backgroundReconnectJob = null
        val oldGatt = currentGatt
        currentGatt = null
        if (oldGatt != null && hasBlePermission()) {
            runCatching { oldGatt.disconnect() }
            runCatching { oldGatt.close() }
        }
        var subscribed = false
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!isCurrentGatt(gatt, opId)) {
                    runCatching { gatt.close() }
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    stopActiveScan()
                    backgroundReconnectJob?.cancel()
                    backgroundReconnectJob = null
                    reportStatus("已连接，发现服务", onStatus)
                    val discoveryStarted = gatt.discoverServices()
                    if (!discoveryStarted) {
                        reportStatus("发现服务失败：未能启动", onStatus)
                        runCatching { gatt.disconnect() }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribed = false
                    val reconnect = shouldReconnect
                    if (currentGatt == gatt) {
                        currentGatt = null
                    }
                    gatt.close()
                    if (reconnect && appInForeground) {
                        reportStatus("设备断开，稍后重连上次设备", onStatus)
                        scope.launch {
                            delay(5_000)
                            if (isCurrentOperation(opId) && shouldReconnect && appInForeground) {
                                reconnectLastDevice(displayName, connectedDeviceName, onStatus, onUploadStatus, onBpm, opId)
                            }
                        }
                    } else if (reconnect) {
                        reportStatus("设备断开，后台定时重连上次设备", onStatus)
                        scheduleBackgroundReconnect(displayName, connectedDeviceName, onStatus, onUploadStatus, onBpm)
                    } else {
                        reportStatus("已断开，可修改服务器地址和显示名称", onStatus)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (!isCurrentGatt(gatt, opId)) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    reportStatus("发现服务失败：$status", onStatus)
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    if (currentGatt == gatt) currentGatt = null
                    return
                }
                val characteristic = gatt
                    .getService(UUID.fromString(HR_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(HR_MEASUREMENT_UUID))
                if (characteristic == null) {
                    reportStatus("设备没有标准心率特征 0x2A37", onStatus)
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    if (currentGatt == gatt) currentGatt = null
                    return
                }
                val notificationEnabled = gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                )
                var descriptorWriteStarted = false
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        descriptorWriteStarted = gatt.writeDescriptor(
                            descriptor,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        ) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        descriptorWriteStarted = gatt.writeDescriptor(descriptor)
                    }
                }
                if (!notificationEnabled || !descriptorWriteStarted) {
                    reportStatus("订阅心率通知失败，请断开后重试", onStatus)
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    if (currentGatt == gatt) currentGatt = null
                    return
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (!isCurrentGatt(gatt, opId)) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    subscribed = true
                    reportStatus("已订阅心率通知", onStatus)
                } else {
                    reportStatus("订阅心率通知失败：$status", onStatus)
                    runCatching { gatt.disconnect() }
                    runCatching { gatt.close() }
                    if (currentGatt == gatt) currentGatt = null
                }
            }

            @Deprecated("Kept for Android 12 and older callbacks")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (!isCurrentGatt(gatt, opId)) return
                subscribed = true
                onHeartRate(characteristic.value, displayName, connectedDeviceName, onBpm, onStatus, onUploadStatus)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (!isCurrentGatt(gatt, opId)) return
                subscribed = true
                onHeartRate(value, displayName, connectedDeviceName, onBpm, onStatus, onUploadStatus)
            }
        }
        @Suppress("DEPRECATION")
        currentGatt = device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            val gatt = currentGatt
            if (isCurrentOperation(opId) && gatt != null && !subscribed) {
                currentGatt = null
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                reportStatus("连接超时，已释放旧连接，请重试", onStatus)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun closeCurrentGatt(disconnectFirst: Boolean, settleMs: Long = 0L) {
        val gatt = currentGatt
        currentGatt = null
        if (gatt != null && hasBlePermission()) {
            if (disconnectFirst) runCatching { gatt.disconnect() }
            if (settleMs > 0L) delay(settleMs)
            runCatching { gatt.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleBackgroundReconnect(
        displayName: String,
        deviceModel: String,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
    ) {
        if (backgroundReconnectJob?.isActive == true) return
        val opId = operationId.get()
        val targetAddress = lastDevice?.address ?: return
        backgroundReconnectJob = scope.launch {
            while (shouldReconnect && !appInForeground) {
                delay(BACKGROUND_RECONNECT_INTERVAL_MS)
                if (!isCurrentOperation(opId) || !shouldReconnect || appInForeground) break
                if (!hasBlePermission()) {
                    reportStatus("后台重连失败：缺少蓝牙权限", onStatus)
                    continue
                }
                reportStatus("后台扫描上次设备", onStatus)
                runCatching {
                    scanAndConnect(
                        displayName = displayName,
                        deviceModel = deviceModel,
                        onStatus = onStatus,
                        onUploadStatus = onUploadStatus,
                        onBpm = onBpm,
                        opId = opId,
                        targetAddress = targetAddress,
                        timeoutMs = TARGET_SCAN_TIMEOUT_MS,
                    )
                }.onFailure { error ->
                    if (isCurrentOperation(opId)) reportStatus("后台重连失败：${error.message}", onStatus)
                }
                break
            }
        }
    }

    private fun reconnectLastDevice(
        displayName: String,
        deviceModel: String,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
        onBpm: (Int) -> Unit,
        opId: Int,
    ) {
        val device = lastDevice
        if (device != null) {
            reportStatus("正在重连上次设备", onStatus)
            connect(device, displayName, deviceModel, onStatus, onUploadStatus, onBpm, opId)
        } else {
            reportStatus("没有上次连接设备，请重新扫描选择", onStatus)
        }
    }

    private fun onHeartRate(
        value: ByteArray,
        displayName: String,
        deviceModel: String,
        onBpm: (Int) -> Unit,
        onStatus: (String) -> Unit,
        onUploadStatus: (String) -> Unit,
    ) {
        val measurement = parseHeartRateMeasurementDetail(value) ?: return
        val bpm = measurement.bpm
        reportBpm(bpm, onBpm)
        if (appInForeground) {
            reportStatus("收到心率 $bpm BPM${latestRssi?.let { " · RSSI $it" } ?: ""}", onStatus)
        }
        batcher.add(bpm)
        if (appInForeground) {
            reportUploadStatus("已缓存心率，等待批量上传", onUploadStatus)
        }
        val now = nowMs()
        if (batcher.shouldFlush(lowPower = !appInForeground, tMs = now)) {
            if (uploadInFlight) {
                if (appInForeground) {
                    reportUploadStatus("正在上传，继续缓存 ${batcher.size()} 条", onUploadStatus)
                }
                return
            }
            if (now < nextUploadAttemptMs) {
                val waitSeconds = ((nextUploadAttemptMs - now) / 1000).coerceAtLeast(1)
                if (appInForeground) {
                    reportUploadStatus("服务器暂不可用，${waitSeconds}s 后重试 · 已缓存 ${batcher.size()} 条", onUploadStatus)
                }
                return
            }
            uploadInFlight = true
            scope.launch {
                upload(displayName, deviceModel, onUploadStatus)
            }
        }
    }

    private suspend fun upload(
        displayName: String,
        deviceModel: String,
        onUploadStatus: (String) -> Unit,
    ) {
        runCatching {
            ensureSession(displayName, deviceModel, onUploadStatus)
            val currentSession = session ?: error("上传会话不可用")
            val actualDeviceModel = sessionDeviceModel
            if (batcher.size() == 0) {
                reportUploadStatus("会话已创建，等待心率批量上传", onUploadStatus)
                null
            } else {
                val payload = batcher.buildPayload(
                    collectorId = currentSession.collectorId,
                    seq = seq,
                    displayName = displayName,
                    deviceModel = actualDeviceModel,
                    ble = BleInfo(rssi = latestRssi),
                )
                reportUploadStatus("正在上传 ${payload.samples.size} 条到 $serverUrl", onUploadStatus)
                val result = api.uploadBatch(currentSession.collectorToken, payload)
                payload to result
            }
        }.onSuccess { result ->
            if (result == null) return@onSuccess
            val (_, response) = result
            seq += 1
            nextUploadAttemptMs = 0L
            batcher.markUploaded()
            reportUploadStatus("上传成功 ${response.accepted} 条 · seq ${seq - 1}", onUploadStatus)
        }.onFailure { error ->
            if (error.message == WAITING_FOR_DEVICE_NAME) {
                nextUploadAttemptMs = nowMs() + NAME_RETRY_BACKOFF_MS
                reportUploadStatus("等待蓝牙设备名称 · 已缓存 ${batcher.size()} 条", onUploadStatus)
            } else {
                nextUploadAttemptMs = nowMs() + UPLOAD_RETRY_BACKOFF_MS
                val summary = error.uploadSummary()
                reportUploadStatus("上传失败：$summary · 已缓存 ${batcher.size()} 条", onUploadStatus)
            }
        }
        uploadInFlight = false
    }

    private fun Throwable.uploadSummary(): String {
        val type = this::class.simpleName ?: "UnknownError"
        val message = message?.takeIf { it.isNotBlank() }
        return if (message == null) type else "$type: $message"
    }

    private fun nextOperationId(): Int = operationId.incrementAndGet()

    private fun isCurrentOperation(opId: Int): Boolean = operationId.get() == opId

    private fun isCurrentGatt(gatt: BluetoothGatt, opId: Int): Boolean {
        return isCurrentOperation(opId) && currentGatt == gatt
    }

    private fun hasBlePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toCandidate(): BleDeviceCandidate {
        val serviceUuids = scanRecord?.serviceUuids.orEmpty()
        val hasHeartRateService = serviceUuids.any { it.uuid.toString().equals(HR_SERVICE_UUID, ignoreCase = true) }
        return BleDeviceCandidate(
            address = device.address,
            name = device.name ?: scanRecord?.deviceName ?: "未知 BLE 设备",
            rssi = rssi,
            hasHeartRateService = hasHeartRateService,
        )
    }

    private fun ScanResult.isAcceptableAutoConnectTarget(targetAddress: String?): Boolean {
        if (targetAddress != null) {
            return device.address.equals(targetAddress, ignoreCase = true)
        }
        return toCandidate().isLikelyHeartRateBand()
    }

    private fun BleDeviceCandidate.isLikelyHeartRateBand(): Boolean {
        if (hasHeartRateService) return true
        val lowerName = name.lowercase()
        return lowerName.contains("band") ||
            lowerName.contains("mi ") ||
            lowerName.contains("xiaomi") ||
            lowerName.contains("redmi") ||
            lowerName.contains("smart band")
    }

    @SuppressLint("MissingPermission")
    private fun currentDeviceModel(): String {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val address = lastDevice?.address
        val persistedName = address
            ?.let { preferences.getString(deviceNameKey(it), null) }
            ?: preferences.getString(KEY_LAST_DEVICE_NAME, null)
        val model = sequenceOf(
            connectedDeviceName,
            lastDevice?.let { bluetoothDeviceName(it) },
            persistedName,
        )
            .map { it.orEmpty().toDeviceModel() }
            .firstOrNull { it != DEFAULT_DEVICE_MODEL }
            ?: DEFAULT_DEVICE_MODEL
        connectedDeviceName = model
        return model
    }

    private fun rememberDeviceName(address: String, name: String) {
        val model = name.toDeviceModel()
        if (model == DEFAULT_DEVICE_MODEL) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_DEVICE_NAME, model)
            .putString(deviceNameKey(address), model)
            .apply()
    }

    private fun preferredDeviceModel(primary: String, fallback: String): String =
        if (primary.toDeviceModel() != DEFAULT_DEVICE_MODEL) primary.toDeviceModel() else fallback.toDeviceModel()

    @SuppressLint("MissingPermission")
    private fun bluetoothDeviceName(device: BluetoothDevice): String? =
        if (hasBlePermission()) {
            runCatching { device.name }.getOrNull()
        } else {
            null
        }

    private fun String.toDeviceModel(): String {
        val trimmed = trim()
        return when {
            trimmed.isBlank() -> DEFAULT_DEVICE_MODEL
            trimmed == "未知 BLE 设备" -> DEFAULT_DEVICE_MODEL
            else -> trimmed
        }
    }

    private companion object {
        const val PREFS_NAME = "heartwith_collector"
        const val KEY_LAST_DEVICE_NAME = "last_device_name"
        const val DEFAULT_DEVICE_MODEL = "Android BLE"
        const val WAITING_FOR_DEVICE_NAME = "等待蓝牙设备名称"
        const val SCAN_WINDOW_MS = 12_000L
        const val UPLOAD_RETRY_BACKOFF_MS = 15_000L
        const val BACKGROUND_RECONNECT_INTERVAL_MS = 60_000L
        const val CONNECTION_TIMEOUT_MS = 20_000L
        const val TARGET_SCAN_TIMEOUT_MS = 15_000L
        const val NAME_RETRY_BACKOFF_MS = 8_000L

        fun deviceNameKey(address: String): String = "device_name_${address.replace(':', '_')}"
    }
}

data class CollectorSnapshot(
    val collectingOrConnecting: Boolean,
    val status: String,
    val uploadStatus: String,
    val bpm: Int?,
)
