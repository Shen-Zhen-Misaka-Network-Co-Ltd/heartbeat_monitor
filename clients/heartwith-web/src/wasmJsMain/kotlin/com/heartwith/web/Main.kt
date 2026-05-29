package com.heartwith.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.ComposeViewport
import com.heartwith.shared.HeartwithApi
import com.heartwith.shared.HeartwithScreen
import com.heartwith.shared.HeartwithTheme
import com.heartwith.shared.HeartwithUiState
import com.heartwith.shared.LobbyEventEnvelope
import com.heartwith.shared.Participant
import com.heartwith.shared.SeriesSample
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.FontResource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.InternalResourceApi
import org.jetbrains.compose.resources.ResourceItem
import org.jetbrains.compose.resources.preloadFont

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (url, onMessage, onError) => {
        const source = new EventSource(url);
        source.onmessage = (event) => onMessage(event.data);
        source.onerror = () => onError();
        return () => source.close();
    }
    """,
)
private external fun openLobbyEvents(
    url: String,
    onMessage: (String) -> Unit,
    onError: () -> Unit,
): () -> Unit

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => navigator.language || ''")
private external fun browserLanguage(): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (key) => {
        try {
            return localStorage.getItem(key) || '';
        } catch (_) {
            return '';
        }
    }
    """,
)
private external fun readLocalStorage(key: String): String

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    """
    (key, value) => {
        try {
            localStorage.setItem(key, value);
        } catch (_) {
        }
    }
    """,
)
private external fun writeLocalStorage(key: String, value: String)

@OptIn(
    ExperimentalComposeUiApi::class,
    ExperimentalWasmJsInterop::class,
    InternalComposeUiApi::class,
    ExperimentalResourceApi::class,
    InternalResourceApi::class,
)
fun main() {
    ComposeViewport(viewportContainerId = "ComposeTarget") {
        val webFont by preloadFont(heartwithCjkFont)
        val fontFamily = webFont?.let { FontFamily(it) } ?: FontFamily.Default
        HeartwithTheme(fontFamily = fontFamily) {
            val api = remember { HeartwithApi("") }
            val scope = rememberCoroutineScope()
            val json = remember { Json { ignoreUnknownKeys = true } }
            val useEnglishLabels = remember { !browserLanguage().lowercase().startsWith("zh") }
            var expandedParticipantIds by remember { mutableStateOf(emptySet<String>()) }
            var seriesByParticipantId by remember { mutableStateOf(emptyMap<String, List<SeriesSample>>()) }
            var seriesStatusByParticipantId by remember { mutableStateOf(emptyMap<String, String>()) }
            var seriesWindowByParticipantId by remember { mutableStateOf(emptyMap<String, Long>()) }
            var seriesLoadedAtByParticipantId by remember { mutableStateOf(emptyMap<String, Long>()) }
            var seriesWindowSeconds by remember { mutableStateOf(10 * 60L) }
            var offlineFilterSeconds by remember { mutableStateOf<Long?>(60 * 60L) }
            var renderClockMs by remember { mutableStateOf(com.heartwith.shared.nowMs()) }
            var participantOrderIds by remember {
                mutableStateOf(readParticipantOrder())
            }
            var state by remember {
                mutableStateOf(
                    HeartwithUiState(
                        serverUrl = "",
                        localStatus = "",
                        localBpm = null,
                        participants = emptyList(),
                    ),
                )
            }
            val latestState by rememberUpdatedState(state)
            val latestParticipantOrderIds by rememberUpdatedState(participantOrderIds)
            val displayParticipants = state.participants.map { participant ->
                participant
                    .withLatestSeriesSample(seriesByParticipantId[participant.collectorId].orEmpty())
                    .withLiveStatus(renderClockMs)
            }
            val visibleParticipants = stableParticipantsForDisplay(
                filterRecentlySeenParticipants(
                    displayParticipants,
                    offlineFilterSeconds,
                    renderClockMs,
                ),
                participantOrderIds,
            )

            fun updateParticipantOrder(next: List<String>) {
                if (next == latestParticipantOrderIds) return
                participantOrderIds = next
                writeParticipantOrder(next)
            }

            fun seriesWindowLabel(windowSeconds: Long): String =
                when (windowSeconds) {
                    10 * 60L -> if (useEnglishLabels) "Last 10 min" else "最近 10 分钟"
                    60 * 60L -> if (useEnglishLabels) "Last 1 hour" else "最近 1 小时"
                    6 * 60 * 60L -> if (useEnglishLabels) "Last 6 hours" else "最近 6 小时"
                    24 * 60 * 60L -> if (useEnglishLabels) "Last 24 hours" else "最近 24 小时"
                    else -> if (useEnglishLabels) "Custom range" else "自定义范围"
                }

            suspend fun loadSeries(
                participant: Participant,
                windowSeconds: Long = seriesWindowSeconds,
                force: Boolean = false,
            ) {
                val collectorId = participant.collectorId
                val currentMs = com.heartwith.shared.nowMs()
                val loadedWindow = seriesWindowByParticipantId[collectorId]
                val loadedAt = seriesLoadedAtByParticipantId[collectorId] ?: 0L
                if (!force && loadedWindow == windowSeconds && currentMs - loadedAt < 30_000L) {
                    return
                }
                runCatching { api.participantSeries(participant.collectorId, windowSeconds = windowSeconds) }
                    .onSuccess { response ->
                        seriesByParticipantId = seriesByParticipantId + (collectorId to response.samples)
                        seriesWindowByParticipantId = seriesWindowByParticipantId + (collectorId to response.windowSeconds)
                        seriesLoadedAtByParticipantId = seriesLoadedAtByParticipantId + (collectorId to currentMs)
                        seriesStatusByParticipantId = seriesStatusByParticipantId + (
                            collectorId to seriesWindowLabel(response.windowSeconds)
                        )
                    }
                    .onFailure { error ->
                        seriesByParticipantId = seriesByParticipantId + (collectorId to emptyList())
                        seriesStatusByParticipantId = seriesStatusByParticipantId + (
                            collectorId to if (useEnglishLabels) "Load failed" else "加载失败"
                        )
                        state = state.copy(
                            localStatus = if (useEnglishLabels) {
                                "Series failed: ${error.message}"
                            } else {
                                "历史心率加载失败：${error.message}"
                            },
                        )
                    }
            }

            fun clearMissingExpandedParticipants(
                participants: List<Participant> = state.participants,
                filterSeconds: Long? = offlineFilterSeconds,
            ) {
                val existingIds = filterRecentlySeenParticipants(participants, filterSeconds)
                    .map { it.collectorId }
                    .toSet()
                expandedParticipantIds = expandedParticipantIds.intersect(existingIds)
                seriesByParticipantId = seriesByParticipantId.filterKeys { it in existingIds }
                seriesStatusByParticipantId = seriesStatusByParticipantId.filterKeys { it in existingIds }
                seriesWindowByParticipantId = seriesWindowByParticipantId.filterKeys { it in existingIds }
                seriesLoadedAtByParticipantId = seriesLoadedAtByParticipantId.filterKeys { it in existingIds }
            }

            fun reloadExpandedSeries(windowSeconds: Long = seriesWindowSeconds) {
                scope.launch {
                    latestState.participants
                        .let { filterRecentlySeenParticipants(it, offlineFilterSeconds) }
                        .filter { it.collectorId in expandedParticipantIds }
                        .forEach { participant -> loadSeries(participant, windowSeconds, force = true) }
                }
            }

            fun appendLiveSeriesSample(participant: Participant) {
                val bpm = participant.lastBpm ?: return
                val tMs = participant.lastSeenMs ?: participant.updatedAtMs ?: return
                val collectorId = participant.collectorId
                if (collectorId !in expandedParticipantIds) return
                val cutoffMs = tMs - 24 * 60 * 60 * 1000L
                val current = seriesByParticipantId[collectorId].orEmpty()
                val existingIndex = current.indexOfFirst { it.tMs == tMs }
                val sample = SeriesSample(tMs = tMs, bpm = bpm)
                val next = if (existingIndex >= 0) {
                    current.toMutableList().also { it[existingIndex] = sample }
                } else {
                    current + sample
                }
                seriesByParticipantId = seriesByParticipantId + (
                    collectorId to next
                        .filter { it.tMs >= cutoffMs }
                        .sortedBy { it.tMs }
                        .takeLast(1_200)
                )
                seriesLoadedAtByParticipantId = seriesLoadedAtByParticipantId + (collectorId to com.heartwith.shared.nowMs())
            }

            suspend fun refresh() {
                runCatching { api.lobby() }
                    .onSuccess { lobby ->
                        val participants = lobby.participants
                        updateParticipantOrder(reconcileParticipantOrder(latestParticipantOrderIds, participants))
                        state = state.copy(
                            localStatus = if (useEnglishLabels) "Synced lobby snapshot" else "已同步服务端聚合数据",
                            participants = participants,
                        )
                        clearMissingExpandedParticipants(participants)
                    }
                    .onFailure { error ->
                        state = state.copy(
                            localStatus = if (useEnglishLabels) {
                                "Refresh failed: ${error.message}"
                            } else {
                                "刷新失败：${error.message}"
                            },
                        )
                    }
            }

            LaunchedEffect(Unit) {
                val close = openLobbyEvents(
                    "/api/v1/lobby/events",
                    { data ->
                        runCatching { json.decodeFromString<LobbyEventEnvelope>(data) }
                            .onSuccess { event ->
                                val participants = applyLobbyEvent(latestState.participants, event)
                                updateParticipantOrder(reconcileParticipantOrder(latestParticipantOrderIds, participants))
                                state = state.copy(
                                    localStatus = if (useEnglishLabels) "Live lobby events connected" else "已连接实时事件",
                                    participants = participants,
                                )
                                event.participant?.let { appendLiveSeriesSample(it) }
                                clearMissingExpandedParticipants(participants)
                            }
                    },
                    {
                        state = state.copy(
                            localStatus = if (useEnglishLabels) {
                                "Live events disconnected"
                            } else {
                                "实时事件断开"
                            },
                        )
                    },
                )
                try {
                    awaitCancellation()
                } finally {
                    close()
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(5_000L)
                    renderClockMs = com.heartwith.shared.nowMs()
                }
            }

            LaunchedEffect(expandedParticipantIds, seriesWindowSeconds, offlineFilterSeconds) {
                while (true) {
                    delay(15_000L)
                    if (expandedParticipantIds.isNotEmpty()) {
                        reloadExpandedSeries(seriesWindowSeconds)
                    }
                }
            }

            HeartwithScreen(
                state = state.copy(participants = visibleParticipants),
                canCollect = false,
                useEnglishLabels = useEnglishLabels,
                expandedParticipantIds = expandedParticipantIds,
                seriesByParticipantId = seriesByParticipantId,
                seriesStatusByParticipantId = seriesStatusByParticipantId,
                seriesWindowSeconds = seriesWindowSeconds,
                onSeriesWindowChange = { seconds ->
                    if (seconds == seriesWindowSeconds) return@HeartwithScreen
                    seriesWindowSeconds = seconds
                    reloadExpandedSeries(seconds)
                },
                offlineFilterSeconds = offlineFilterSeconds,
                onOfflineFilterChange = { seconds ->
                    offlineFilterSeconds = seconds
                    clearMissingExpandedParticipants(filterSeconds = seconds)
                },
                onParticipantClick = { participant ->
                    scope.launch {
                        if (participant.collectorId in expandedParticipantIds) {
                            expandedParticipantIds = expandedParticipantIds - participant.collectorId
                        } else {
                            expandedParticipantIds = expandedParticipantIds + participant.collectorId
                            loadSeries(participant)
                        }
                    }
                },
                onParticipantMove = { collectorId, delta ->
                    updateParticipantOrder(
                        moveParticipantOrder(
                            currentOrderIds = participantOrderIds,
                            allParticipants = displayParticipants,
                            visibleParticipants = visibleParticipants,
                            collectorId = collectorId,
                            delta = delta,
                        ),
                    )
                },
                onStartCollect = {},
                onRefresh = {
                    scope.launch {
                        refresh()
                    }
                },
            )
        }
    }
}

private fun filterRecentlySeenParticipants(
    participants: List<Participant>,
    filterSeconds: Long?,
    currentMs: Long = com.heartwith.shared.nowMs(),
): List<Participant> {
    if (filterSeconds == null) return participants
    val cutoff = currentMs - filterSeconds * 1000
    return participants.filter { participant ->
        participant.lastSeenMs?.let { it >= cutoff } == true
    }
}

private fun Participant.withLatestSeriesSample(samples: List<SeriesSample>): Participant {
    val latest = samples.maxByOrNull { it.tMs } ?: return this
    val currentLastSeen = lastSeenMs ?: Long.MIN_VALUE
    return if (latest.tMs >= currentLastSeen) {
        copy(lastBpm = latest.bpm, lastSeenMs = latest.tMs)
    } else {
        this
    }
}

private fun Participant.withLiveStatus(currentMs: Long): Participant {
    val lastSeen = lastSeenMs ?: return copy(status = "offline")
    val ageMs = currentMs - lastSeen
    val liveStatus = when {
        ageMs <= 20_000L -> "online"
        ageMs <= 120_000L -> "stale"
        else -> "offline"
    }
    return if (status == liveStatus) this else copy(status = liveStatus)
}

private const val ParticipantOrderStorageKey = "heartwith.participant.order.v1"

private fun readParticipantOrder(): List<String> =
    readLocalStorage(ParticipantOrderStorageKey)
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

private fun writeParticipantOrder(orderIds: List<String>) {
    writeLocalStorage(ParticipantOrderStorageKey, orderIds.distinct().joinToString("\n"))
}

private fun stableParticipantsForDisplay(
    participants: List<Participant>,
    orderIds: List<String>,
): List<Participant> {
    if (orderIds.isEmpty()) return defaultParticipantsForDisplay(participants)
    val byId = participants.associateBy { it.collectorId }
    val ordered = orderIds.mapNotNull { byId[it] }
    val orderedIds = ordered.map { it.collectorId }.toSet()
    val remaining = defaultParticipantsForDisplay(participants.filterNot { it.collectorId in orderedIds })
    return ordered + remaining
}

private fun reconcileParticipantOrder(
    currentOrderIds: List<String>,
    participants: List<Participant>,
): List<String> {
    val presentIds = participants.map { it.collectorId }.toSet()
    val current = currentOrderIds.filter { it in presentIds }.distinct()
    val currentSet = current.toSet()
    val missing = defaultParticipantsForDisplay(participants.filterNot { it.collectorId in currentSet })
        .map { it.collectorId }
    return current + missing
}

private fun moveParticipantOrder(
    currentOrderIds: List<String>,
    allParticipants: List<Participant>,
    visibleParticipants: List<Participant>,
    collectorId: String,
    delta: Int,
): List<String> {
    if (delta == 0 || visibleParticipants.size < 2) {
        return reconcileParticipantOrder(currentOrderIds, allParticipants)
    }
    val visibleIds = visibleParticipants.map { it.collectorId }
    val fromVisibleIndex = visibleIds.indexOf(collectorId)
    if (fromVisibleIndex < 0) {
        return reconcileParticipantOrder(currentOrderIds, allParticipants)
    }
    val toVisibleIndex = (fromVisibleIndex + delta).coerceIn(0, visibleIds.lastIndex)
    if (toVisibleIndex == fromVisibleIndex) {
        return reconcileParticipantOrder(currentOrderIds, allParticipants)
    }
    val targetId = visibleIds[toVisibleIndex]
    val fullOrder = reconcileParticipantOrder(currentOrderIds, allParticipants).toMutableList()
    fullOrder.remove(collectorId)
    val targetIndex = fullOrder.indexOf(targetId)
    if (targetIndex < 0) {
        fullOrder.add(collectorId)
        return fullOrder
    }
    val insertIndex = if (delta > 0) targetIndex + 1 else targetIndex
    fullOrder.add(insertIndex.coerceIn(0, fullOrder.size), collectorId)
    return fullOrder
}

private fun defaultParticipantsForDisplay(participants: List<Participant>): List<Participant> =
    participants.sortedWith(
        compareBy<Participant> { statusRank(it.status) }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.deviceModel.lowercase() }
            .thenBy { it.collectorId },
    )

private fun statusRank(status: String): Int =
    when (status) {
        "online" -> 0
        "stale" -> 1
        else -> 2
    }

@OptIn(InternalResourceApi::class)
private val heartwithCjkFont = FontResource(
    id = "font:HeartwithCJK",
    items = setOf(
        ResourceItem(
            qualifiers = setOf(),
            path = "composeResources/heartwith.heartwith_web.generated.resources/font/HeartwithCJK.ttf",
            offset = -1,
            size = -1,
        ),
    ),
)

private fun applyLobbyEvent(
    current: List<Participant>,
    event: LobbyEventEnvelope,
): List<Participant> =
    when (event.type) {
        "snapshot" -> event.participants
        "participant_update" -> {
            val participant = event.participant ?: return current
            val next = current
                .filterNot {
                    it.collectorId == participant.collectorId ||
                        it.displayName == participant.displayName
                }
                .plus(participant)
            next
        }
        else -> current
    }
