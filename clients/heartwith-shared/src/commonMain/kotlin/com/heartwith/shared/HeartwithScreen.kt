package com.heartwith.shared

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

data class HeartwithUiState(
    val serverUrl: String,
    val displayName: String = "Heartwith",
    val localStatus: String,
    val uploadStatus: String = "未上传",
    val localBpm: Int?,
    val participants: List<Participant>,
    val devices: List<BleDeviceCandidate> = emptyList(),
    val scanning: Boolean = false,
    val collecting: Boolean = false,
    val backgroundEnabled: Boolean = true,
    val hideFromRecents: Boolean = false,
)

@Composable
fun HeartwithScreen(
    state: HeartwithUiState,
    canCollect: Boolean,
    showLobby: Boolean = true,
    showServerUrl: Boolean = showLobby,
    useEnglishLabels: Boolean = false,
    onServerUrlChange: (String) -> Unit = {},
    onDisplayNameChange: (String) -> Unit = {},
    onHideFromRecentsChange: (Boolean) -> Unit = {},
    onOpenAutoStartSettings: () -> Unit = {},
    onScanDevices: () -> Unit = {},
    onConnectDevice: (BleDeviceCandidate) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onCloseCollection: () -> Unit = {},
    expandedParticipantIds: Set<String> = emptySet(),
    seriesByParticipantId: Map<String, List<SeriesSample>> = emptyMap(),
    seriesStatusByParticipantId: Map<String, String> = emptyMap(),
    seriesWindowSeconds: Long = 600,
    onSeriesWindowChange: (Long) -> Unit = {},
    offlineFilterSeconds: Long? = 60 * 60,
    onOfflineFilterChange: (Long?) -> Unit = {},
    onParticipantClick: (Participant) -> Unit = {},
    onStartCollect: () -> Unit,
    onRefresh: () -> Unit,
) {
    val readOnlyWeb = !canCollect
    val english = readOnlyWeb && useEnglishLabels
    Scaffold(
        topBar = {
            TopAppBar(
                title = "Heartwith",
                subtitle = when {
                    readOnlyWeb -> if (english) "Public heart-rate lobby" else "公共心率大厅"
                    canCollect && !showLobby -> "心率采集端"
                    else -> "公共心率大厅"
                },
                actions = {
                    if (showLobby) {
                        TextButton(text = if (english) "Refresh" else "刷新", onClick = onRefresh)
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val desktop = showLobby && !canCollect && maxWidth >= 900.dp
            val maxParticipantColumns = when {
                !desktop -> 1
                maxWidth >= 1520.dp -> 4
                maxWidth >= 1180.dp -> 3
                else -> 2
            }
            val participantColumns = chooseParticipantColumns(
                participantCount = state.participants.size,
                maxColumns = maxParticipantColumns,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (desktop) 24.dp else 12.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    StatusOverview(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = if (desktop) 1280.dp else 720.dp),
                        state = state,
                        canCollect = canCollect,
                        showLobby = showLobby,
                        readOnlyWeb = readOnlyWeb,
                        useEnglishLabels = useEnglishLabels,
                        onStartCollect = onStartCollect,
                        onDisconnect = onDisconnect,
                        onCloseCollection = onCloseCollection,
                    )
                }
                if (canCollect) {
                    item {
                        CollectorSettings(
                            state = state,
                            showServerUrl = showServerUrl,
                            onServerUrlChange = onServerUrlChange,
                            onDisplayNameChange = onDisplayNameChange,
                            onHideFromRecentsChange = onHideFromRecentsChange,
                            onOpenAutoStartSettings = onOpenAutoStartSettings,
                        )
                    }
                    item {
                        DeviceSection(
                            state = state,
                            onScanDevices = onScanDevices,
                            onConnectDevice = onConnectDevice,
                        )
                    }
                }
                if (showLobby) {
                    item {
                        LobbyHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 1280.dp),
                            useEnglishLabels = english,
                            offlineFilterSeconds = offlineFilterSeconds,
                            onOfflineFilterChange = onOfflineFilterChange,
                        )
                    }
                    if (state.participants.isEmpty()) {
                        item {
                            EmptyLobbyCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 1280.dp),
                                useEnglishLabels = english,
                            )
                        }
                    } else {
                        items(state.participants.chunked(participantColumns), key = { row ->
                            row.joinToString("|") { it.collectorId }
                        }) { rowParticipants ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 1280.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                rowParticipants.forEach { participant ->
                                    ParticipantColumn(
                                        modifier = Modifier.weight(1f),
                                        participant = participant,
                                        expanded = participant.collectorId in expandedParticipantIds,
                                        samples = seriesByParticipantId[participant.collectorId].orEmpty(),
                                        status = seriesStatusByParticipantId[participant.collectorId].orEmpty(),
                                        seriesWindowSeconds = seriesWindowSeconds,
                                        useEnglishLabels = english,
                                        onSeriesWindowChange = onSeriesWindowChange,
                                        onClick = { onParticipantClick(participant) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LobbyHeader(
    modifier: Modifier,
    useEnglishLabels: Boolean,
    offlineFilterSeconds: Long?,
    onOfflineFilterChange: (Long?) -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionTitle(text = if (useEnglishLabels) "Lobby" else "大厅")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (useEnglishLabels) "Hide offline after" else "隐藏离线超过",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            OfflineFilterOption.all.forEach { option ->
                StatusTagButton(
                    label = if (useEnglishLabels) option.englishLabel else option.zhLabel,
                    selected = option.seconds == offlineFilterSeconds,
                    onClick = { onOfflineFilterChange(option.seconds) },
                )
            }
        }
    }
}

@Composable
private fun StatusOverview(
    modifier: Modifier = Modifier,
    state: HeartwithUiState,
    canCollect: Boolean,
    showLobby: Boolean,
    readOnlyWeb: Boolean,
    useEnglishLabels: Boolean,
    onStartCollect: () -> Unit,
    onDisconnect: () -> Unit,
    onCloseCollection: () -> Unit,
) {
    if (readOnlyWeb && showLobby) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(82.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                pressFeedbackType = PressFeedbackType.None,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusTag(
                        label = if (useEnglishLabels) "Live lobby" else "实时大厅",
                        backgroundColor = HyperBlue.copy(alpha = 0.14f),
                        contentColor = HyperBlue,
                    )
                    Text(
                        text = state.localStatus,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
            MetricCard(
                modifier = Modifier
                    .width(112.dp)
                    .height(82.dp),
                title = if (useEnglishLabels) "Online" else "在线",
                value = state.participants.count { it.status == "online" }.toString(),
            )
            MetricCard(
                modifier = Modifier
                    .width(112.dp)
                    .height(82.dp),
                title = if (useEnglishLabels) "Total" else "全部",
                value = state.participants.size.toString(),
            )
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
            insideMargin = PaddingValues(16.dp),
            pressFeedbackType = if (canCollect) PressFeedbackType.Tilt else PressFeedbackType.None,
            onClick = {
                if (canCollect && !state.collecting) onStartCollect()
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusTag(
                    label = when {
                        state.collecting -> "采集中"
                        state.scanning -> "扫描中"
                        canCollect -> "未连接"
                        else -> if (useEnglishLabels) "Read only" else "只读"
                    },
                    backgroundColor = if (state.collecting) {
                        HyperBlue.copy(alpha = 0.14f)
                    } else {
                        MiuixTheme.colorScheme.surface
                    },
                    contentColor = if (state.collecting) HyperBlue else MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.localBpm?.let { "$it BPM" } ?: if (readOnlyWeb && useEnglishLabels) "Waiting" else "等待心率",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.localStatus,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (canCollect) {
                    Text(
                        text = "上传：${state.uploadStatus}",
                        fontSize = 13.sp,
                        color = if (state.uploadStatus.contains("失败")) {
                            Color(0xFFE5484D)
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                if (canCollect && (state.collecting || state.scanning)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(
                                text = "断开",
                                onClick = onDisconnect,
                            )
                            TextButton(
                                text = "关闭",
                                onClick = onCloseCollection,
                            )
                        }
                        Text(
                            text = "关闭会停止采集并禁止后台自启动；重新扫描或连接后恢复",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (showLobby) {
            Column(
                modifier = Modifier
                    .width(156.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetricCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp),
                    title = if (readOnlyWeb && useEnglishLabels) "Online" else "在线",
                    value = state.participants.count { it.status == "online" }.toString(),
                )
                MetricCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp),
                    title = if (readOnlyWeb && useEnglishLabels) "Total" else "全部",
                    value = state.participants.size.toString(),
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    title: String,
    value: String,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        Column {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun CollectorSettings(
    state: HeartwithUiState,
    showServerUrl: Boolean,
    onServerUrlChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onHideFromRecentsChange: (Boolean) -> Unit,
    onOpenAutoStartSettings: () -> Unit,
) {
    Card(pressFeedbackType = PressFeedbackType.None) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            SectionTitle("采集端")
            Spacer(Modifier.height(10.dp))
            if (showServerUrl) {
                TextField(
                    value = state.serverUrl,
                    onValueChange = onServerUrlChange,
                    label = "服务器地址",
                    singleLine = true,
                    enabled = !state.collecting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
            }
            TextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = "显示名称",
                singleLine = true,
                enabled = !state.collecting,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            BasicComponent(
                title = "自启动权限",
                summary = "后台采集默认开启。HyperOS 需要允许自启动，并把省电策略设为无限制；否则清后台后系统可能拒绝恢复采集",
                endActions = {
                    TextButton(
                        text = "打开设置",
                        onClick = onOpenAutoStartSettings,
                    )
                },
                insideMargin = PaddingValues(vertical = 8.dp),
            )
            BasicComponent(
                title = "后台隐藏",
                summary = if (state.hideFromRecents) {
                    "当前会从最近任务隐藏卡片，降低误清理概率；建议同时在系统最近任务中加锁"
                } else {
                    "当前会在最近任务中显示卡片，方便手动切回应用"
                },
                endActions = {
                    Switch(
                        checked = state.hideFromRecents,
                        onCheckedChange = onHideFromRecentsChange,
                    )
                },
                insideMargin = PaddingValues(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun DeviceSection(
    state: HeartwithUiState,
    onScanDevices: () -> Unit,
    onConnectDevice: (BleDeviceCandidate) -> Unit,
) {
    Card(pressFeedbackType = PressFeedbackType.None) {
        Column {
            BasicComponent(
                title = "心率设备",
                summary = if (state.devices.isEmpty()) {
                    if (state.scanning) "正在发现附近 BLE 设备" else "扫描附近 BLE 设备，选择你的小米手环"
                } else {
                    "发现 ${state.devices.size} 个设备，心率设备会排在前面"
                },
                endActions = {
                    TextButton(
                        text = if (state.scanning) "扫描中" else "扫描",
                        enabled = !state.scanning && !state.collecting,
                        onClick = onScanDevices,
                    )
                },
                insideMargin = PaddingValues(16.dp),
            )
            state.devices.forEach { device ->
                DeviceCandidateRow(
                    device = device,
                    enabled = !state.collecting,
                    onConnectDevice = onConnectDevice,
                )
            }
        }
    }
}

@Composable
private fun DeviceCandidateRow(
    device: BleDeviceCandidate,
    enabled: Boolean,
    onConnectDevice: (BleDeviceCandidate) -> Unit,
) {
    BasicComponent(
        title = device.name,
        summary = "${device.address} · RSSI ${device.rssi}" +
            if (device.hasHeartRateService) " · 心率服务" else " · 未声明心率服务",
        endActions = {
            TextButton(
                text = "连接",
                enabled = enabled,
                onClick = { onConnectDevice(device) },
            )
        },
        onClick = {
            if (enabled) onConnectDevice(device)
        },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun ParticipantColumn(
    modifier: Modifier,
    participant: Participant,
    expanded: Boolean,
    samples: List<SeriesSample>,
    status: String,
    seriesWindowSeconds: Long,
    useEnglishLabels: Boolean,
    onSeriesWindowChange: (Long) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ParticipantRow(
            modifier = Modifier.fillMaxWidth(),
            participant = participant,
            selected = expanded,
            onClick = onClick,
        )
        if (expanded) {
            HeartRateSeriesCard(
                modifier = Modifier.fillMaxWidth(),
                participant = participant,
                samples = samples,
                status = status,
                seriesWindowSeconds = seriesWindowSeconds,
                useEnglishLabels = useEnglishLabels,
                onSeriesWindowChange = onSeriesWindowChange,
            )
        }
    }
}

@Composable
private fun ParticipantRow(
    modifier: Modifier,
    participant: Participant,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.heightIn(min = 104.dp),
        colors = CardDefaults.defaultColors(
            color = if (selected) {
                HyperBlue.copy(alpha = 0.12f)
            } else {
                MiuixTheme.colorScheme.surfaceContainer
            },
        ),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        BasicComponent(
            title = participant.displayName,
            summary = "${participant.deviceModel} · ${participant.status}" +
                (participant.lastSeenMs?.let { " · ${relativeSeenText(it)}" } ?: ""),
            endActions = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusTag(
                        label = participant.status,
                        backgroundColor = when (participant.status) {
                            "online" -> HyperBlue.copy(alpha = 0.14f)
                            "stale" -> Color(0xFFFFB020).copy(alpha = 0.18f)
                            else -> MiuixTheme.colorScheme.surfaceVariant
                        },
                        contentColor = when (participant.status) {
                            "online" -> HyperBlue
                            "stale" -> Color(0xFF9A5A00)
                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                    Text(
                        text = participant.lastBpm?.let { "$it" } ?: "--",
                        color = HyperBlue,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        )
    }
}

@Composable
private fun HeartRateSeriesCard(
    modifier: Modifier,
    participant: Participant?,
    samples: List<SeriesSample>,
    status: String,
    seriesWindowSeconds: Long,
    useEnglishLabels: Boolean,
    onSeriesWindowChange: (Long) -> Unit,
) {
    val sortedSamples = samples
        .sortedBy { it.tMs }
        .fold(emptyList<SeriesSample>()) { acc, sample ->
            if (acc.lastOrNull()?.tMs == sample.tMs) acc.dropLast(1) + sample else acc + sample
        }
    val bounds = chartBounds(sortedSamples, seriesWindowSeconds)
    val averageBpm = if (sortedSamples.isEmpty()) null else sortedSamples.sumOf { it.bpm } / sortedSamples.size
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (useEnglishLabels) "Heart-rate trend" else "心率趋势",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = participant?.displayName
                            ?: if (useEnglishLabels) "Select a participant" else "选择一个用户",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
                SeriesWindowSelector(
                    selectedSeconds = seriesWindowSeconds,
                    useEnglishLabels = useEnglishLabels,
                    onSelect = onSeriesWindowChange,
                )
            }
            HeartRateChart(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3.2f),
                samples = sortedSamples,
                displaySamples = smoothSamplesForChart(sortedSamples, seriesWindowSeconds),
                bounds = bounds,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        samples.size >= 2 -> {
                            val minBpm = samples.minOf { it.bpm }
                            val maxBpm = samples.maxOf { it.bpm }
                            val avgText = averageBpm?.let { "avg $it" } ?: "avg --"
                            if (useEnglishLabels) {
                                "min $minBpm · max $maxBpm · $avgText · ${samples.size} samples"
                            } else {
                                "最低 $minBpm · 最高 $maxBpm · 平均 ${averageBpm ?: "--"} · ${samples.size} 个样本"
                            }
                        }
                        else -> if (useEnglishLabels) "Waiting for more samples" else "等待更多样本"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesWindowSelector(
    selectedSeconds: Long,
    useEnglishLabels: Boolean,
    onSelect: (Long) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SeriesWindowOption.all.forEach { option ->
            StatusTagButton(
                label = if (useEnglishLabels) option.englishLabel else option.zhLabel,
                selected = option.seconds == selectedSeconds,
                onClick = { onSelect(option.seconds) },
            )
        }
    }
}

@Composable
private fun StatusTagButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.defaultColors(
            color = if (selected) HyperBlue.copy(alpha = 0.16f) else MiuixTheme.colorScheme.surfaceVariant,
        ),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) HyperBlue else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun HeartRateChart(
    modifier: Modifier,
    samples: List<SeriesSample>,
    displaySamples: List<SeriesSample>,
    bounds: ChartBounds,
) {
    val boundedSamples = samples.filter { it.tMs in bounds.minTimeMs..bounds.maxTimeMs }
    val boundedDisplaySamples = displaySamples.filter { it.tMs in bounds.minTimeMs..bounds.maxTimeMs }
    var focusedSample by remember(boundedSamples) { mutableStateOf<SeriesSample?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .width(42.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text("${bounds.maxBpm}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("${(bounds.maxBpm + bounds.minBpm) / 2}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text("${bounds.minBpm}", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .pointerInput(boundedSamples, bounds) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val position = event.changes.firstOrNull()?.position ?: continue
                                focusedSample = nearestSampleForX(
                                    x = position.x,
                                    width = size.width.toFloat(),
                                    samples = boundedSamples,
                                    bounds = bounds,
                                )
                            }
                        }
                    }
                    .background(
                        color = MiuixTheme.colorScheme.surface.copy(alpha = 0.48f),
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                val gridColor = Color.White.copy(alpha = 0.08f)
                val mutedLineColor = Color.White.copy(alpha = 0.18f)
                repeat(4) { index ->
                    val y = size.height * (index + 1) / 5f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }
                if (boundedSamples.size < 2) {
                    drawLine(
                        color = mutedLineColor,
                        start = Offset(0f, size.height * 0.62f),
                        end = Offset(size.width, size.height * 0.62f),
                        strokeWidth = 2f,
                        cap = StrokeCap.Round,
                    )
                    return@Canvas
                }

                val timeSpan = max(1L, bounds.maxTimeMs - bounds.minTimeMs).toFloat()
                val bpmSpan = max(1, bounds.maxBpm - bounds.minBpm).toFloat()
                val horizontalPadding = 12f
                val verticalPadding = 14f
                val chartWidth = size.width - horizontalPadding * 2
                val chartHeight = size.height - verticalPadding * 2
                val points = boundedDisplaySamples.map { sample ->
                    val x = horizontalPadding + ((sample.tMs - bounds.minTimeMs).toFloat() / timeSpan) * chartWidth
                    val y = verticalPadding + (1f - ((sample.bpm - bounds.minBpm).toFloat() / bpmSpan)) * chartHeight
                    Offset(
                        x.coerceIn(horizontalPadding, horizontalPadding + chartWidth),
                        y.coerceIn(verticalPadding, verticalPadding + chartHeight),
                    )
                }
                if (points.size < 2) return@Canvas

                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (index in 0 until points.lastIndex) {
                        val previous = points.getOrElse(index - 1) { points[index] }
                        val current = points[index]
                        val next = points[index + 1]
                        val afterNext = points.getOrElse(index + 2) { next }
                        val control1 = Offset(
                            current.x + (next.x - previous.x) / 6f,
                            current.y + (next.y - previous.y) / 6f,
                        )
                        val control2 = Offset(
                            next.x - (afterNext.x - current.x) / 6f,
                            next.y - (afterNext.y - current.y) / 6f,
                        )
                        cubicTo(control1.x, control1.y, control2.x, control2.y, next.x, next.y)
                    }
                }
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(points.last().x, size.height - verticalPadding)
                    lineTo(points.first().x, size.height - verticalPadding)
                    close()
                }
                clipRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                ) {
                    drawPath(fillPath, HyperBlue.copy(alpha = 0.11f))
                    drawPath(
                        path = path,
                        color = HyperBlue,
                        style = Stroke(width = 3.5f, cap = StrokeCap.Round),
                    )
                    val markerPoints = if (boundedDisplaySamples.size <= MAX_MARKER_POINTS) points else emptyList()
                    markerPoints.forEach { point ->
                        drawCircle(color = HyperBlue.copy(alpha = 0.2f), radius = 5.5f, center = point)
                        drawCircle(color = HyperBlue, radius = 2.7f, center = point)
                    }
                    focusedSample?.let { sample ->
                        val x = (
                            horizontalPadding +
                                ((sample.tMs - bounds.minTimeMs).toFloat() / timeSpan) * chartWidth
                            ).coerceIn(horizontalPadding, horizontalPadding + chartWidth)
                        val y = (
                            verticalPadding +
                                (1f - ((sample.bpm - bounds.minBpm).toFloat() / bpmSpan)) * chartHeight
                            ).coerceIn(verticalPadding, verticalPadding + chartHeight)
                        drawLine(
                            color = HyperBlue.copy(alpha = 0.45f),
                            start = Offset(x, verticalPadding),
                            end = Offset(x, size.height - verticalPadding),
                            strokeWidth = 1.4f,
                        )
                        drawCircle(color = Color.White.copy(alpha = 0.86f), radius = 6.2f, center = Offset(x, y))
                        drawCircle(color = HyperBlue, radius = 4.1f, center = Offset(x, y))
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(50.dp))
            Text(
                text = bounds.startLabel,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "now",
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        focusedSample?.let { sample ->
            Text(
                modifier = Modifier.padding(start = 50.dp),
                text = "${sampleTimeLabel(sample.tMs)} · ${sample.bpm} BPM",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = HyperBlue,
            )
        }
    }
}

private fun smoothSamplesForChart(samples: List<SeriesSample>, windowSeconds: Long): List<SeriesSample> {
    if (samples.size <= MAX_DIRECT_CHART_POINTS || windowSeconds <= 60 * 60) return samples

    val bucketMs = when {
        windowSeconds >= 24 * 60 * 60 -> 5 * 60 * 1000L
        windowSeconds >= 6 * 60 * 60 -> 2 * 60 * 1000L
        windowSeconds >= 60 * 60 -> 30 * 1000L
        else -> 10 * 1000L
    }
    val smoothed = mutableListOf<SeriesSample>()
    var bucketStart = Long.MIN_VALUE
    var weightedTimeSum = 0L
    var bpmSum = 0
    var count = 0

    fun flushBucket() {
        if (count == 0) return
        smoothed += SeriesSample(
            tMs = weightedTimeSum / count,
            bpm = (bpmSum.toFloat() / count).toInt(),
        )
        weightedTimeSum = 0L
        bpmSum = 0
        count = 0
    }

    samples.forEach { sample ->
        if (bucketStart == Long.MIN_VALUE || sample.tMs >= bucketStart + bucketMs) {
            flushBucket()
            bucketStart = sample.tMs
        }
        weightedTimeSum += sample.tMs
        bpmSum += sample.bpm
        count += 1
    }
    flushBucket()

    val first = samples.first()
    val last = samples.last()
    if (smoothed.firstOrNull()?.tMs != first.tMs) smoothed.add(0, first)
    if (smoothed.lastOrNull()?.tMs != last.tMs) smoothed += last
    return smoothed
}

private data class SeriesWindowOption(
    val seconds: Long,
    val zhLabel: String,
    val englishLabel: String,
) {
    companion object {
        val all = listOf(
            SeriesWindowOption(10 * 60, "10 分钟", "10m"),
            SeriesWindowOption(60 * 60, "1 小时", "1h"),
            SeriesWindowOption(6 * 60 * 60, "6 小时", "6h"),
            SeriesWindowOption(24 * 60 * 60, "24 小时", "24h"),
        )
    }
}

private data class OfflineFilterOption(
    val seconds: Long?,
    val zhLabel: String,
    val englishLabel: String,
) {
    companion object {
        val all = listOf(
            OfflineFilterOption(10 * 60, "10 分钟", "10m"),
            OfflineFilterOption(60 * 60, "1 小时", "1h"),
            OfflineFilterOption(6 * 60 * 60, "6 小时", "6h"),
            OfflineFilterOption(null, "显示全部", "All"),
        )
    }
}

@Composable
private fun EmptyLobbyCard(
    modifier: Modifier,
    useEnglishLabels: Boolean,
) {
    Card(modifier = modifier, pressFeedbackType = PressFeedbackType.None) {
        BasicComponent(
            title = if (useEnglishLabels) "No participants" else "暂无接入者",
            summary = if (useEnglishLabels) {
                "Collectors will appear here after they upload heart-rate samples."
            } else {
                "连接手环后，大厅会显示所有采集端的最新心率"
            },
            insideMargin = PaddingValues(16.dp),
        )
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StatusTag(
    label: String,
    backgroundColor: Color,
    contentColor: Color,
) {
    Box(
        modifier = Modifier.background(
            color = backgroundColor,
            shape = RoundedCornerShape(6.dp),
        ),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            text = label,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun relativeSeenText(lastSeenMs: Long): String {
    val seconds = max(0L, (nowMs() - lastSeenMs) / 1000)
    return when {
        seconds < 5 -> "now"
        seconds < 60 -> "${seconds}s ago"
        else -> "${seconds / 60}m ago"
    }
}

private data class ChartBounds(
    val minBpm: Int,
    val maxBpm: Int,
    val minTimeMs: Long,
    val maxTimeMs: Long,
    val startLabel: String,
)

private fun chartBounds(samples: List<SeriesSample>, windowSeconds: Long): ChartBounds {
    val now = nowMs()
    val minTime = now - windowSeconds * 1000
    val label = windowLabel(windowSeconds)
    if (samples.isEmpty()) {
        return ChartBounds(
            minBpm = 50,
            maxBpm = 120,
            minTimeMs = minTime,
            maxTimeMs = now,
            startLabel = "$label ago",
        )
    }
    val rawMinBpm = samples.minOf { it.bpm }
    val rawMaxBpm = samples.maxOf { it.bpm }
    val paddingBpm = max(6, ((rawMaxBpm - rawMinBpm) * 0.28f).toInt())
    val minBpm = max(30, rawMinBpm - paddingBpm)
    val maxBpm = min(240, rawMaxBpm + paddingBpm)
    return ChartBounds(
        minBpm = minBpm,
        maxBpm = maxBpm,
        minTimeMs = minTime,
        maxTimeMs = now,
        startLabel = "$label ago",
    )
}

private fun nearestSampleForX(
    x: Float,
    width: Float,
    samples: List<SeriesSample>,
    bounds: ChartBounds,
): SeriesSample? {
    if (samples.isEmpty() || width <= 0f) return null
    val horizontalPadding = 12f
    val chartWidth = max(1f, width - horizontalPadding * 2f)
    val ratio = ((x - horizontalPadding) / chartWidth).coerceIn(0f, 1f)
    val targetTime = bounds.minTimeMs + ((bounds.maxTimeMs - bounds.minTimeMs) * ratio).toLong()
    return samples.minByOrNull { sample -> abs(sample.tMs - targetTime) }
}

private fun sampleTimeLabel(tMs: Long): String {
    val seconds = max(0L, (nowMs() - tMs) / 1000)
    return when {
        seconds < 5 -> "now"
        seconds < 60 -> "${seconds}s ago"
        else -> {
            val minutes = seconds / 60
            val restSeconds = seconds % 60
            if (restSeconds == 0L) "${minutes}m ago" else "${minutes}m ${restSeconds}s ago"
        }
    }
}

private fun windowLabel(seconds: Long): String =
    if (seconds >= 3600) "${seconds / 3600}h" else "${max(1L, seconds / 60)}m"

private fun chooseParticipantColumns(
    participantCount: Int,
    maxColumns: Int,
): Int {
    if (participantCount <= 1 || maxColumns <= 1) return 1
    val cappedMax = min(participantCount, maxColumns)
    return (2..cappedMax).minWith(
        compareBy<Int> { columns ->
            val remainder = participantCount % columns
            if (remainder == 0) 0 else columns - remainder
        }.thenByDescending { columns -> columns },
    )
}

private const val MAX_DIRECT_CHART_POINTS = 180
private const val MAX_MARKER_POINTS = 36

private val HyperBlue = Color(0xFF0A84FF)
