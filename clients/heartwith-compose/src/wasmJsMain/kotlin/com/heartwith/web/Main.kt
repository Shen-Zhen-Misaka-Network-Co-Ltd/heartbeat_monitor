package com.heartwith.web

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        val loadedFont = webFont ?: return@ComposeViewport
        HeartwithTheme(fontFamily = FontFamily(loadedFont)) {
            val api = remember { HeartwithApi("") }
            val scope = rememberCoroutineScope()
            val json = remember { Json { ignoreUnknownKeys = true } }
            val useEnglishLabels = remember { !browserLanguage().lowercase().startsWith("zh") }
            var expandedParticipantIds by remember { mutableStateOf(emptySet<String>()) }
            var seriesByParticipantId by remember { mutableStateOf(emptyMap<String, List<SeriesSample>>()) }
            var seriesStatusByParticipantId by remember { mutableStateOf(emptyMap<String, String>()) }
            var seriesWindowSeconds by remember { mutableStateOf(10 * 60L) }
            var offlineFilterSeconds by remember { mutableStateOf<Long?>(60 * 60L) }
            var state by remember {
                mutableStateOf(
                    HeartwithUiState(
                        serverUrl = "",
                        localStatus = if (useEnglishLabels) {
                            "Connecting live lobby events"
                        } else {
                            "正在连接实时事件"
                        },
                        localBpm = null,
                        participants = emptyList(),
                    ),
                )
            }
            val visibleParticipants = filterRecentlySeenParticipants(state.participants, offlineFilterSeconds)

            fun seriesWindowLabel(windowSeconds: Long): String =
                when (windowSeconds) {
                    10 * 60L -> if (useEnglishLabels) "Last 10 min" else "最近 10 分钟"
                    60 * 60L -> if (useEnglishLabels) "Last 1 hour" else "最近 1 小时"
                    6 * 60 * 60L -> if (useEnglishLabels) "Last 6 hours" else "最近 6 小时"
                    else -> if (useEnglishLabels) "Custom range" else "自定义范围"
                }

            suspend fun loadSeries(participant: Participant, windowSeconds: Long = seriesWindowSeconds) {
                val collectorId = participant.collectorId
                seriesStatusByParticipantId = seriesStatusByParticipantId + (
                    collectorId to if (useEnglishLabels) "Loading" else "加载中"
                )
                runCatching { api.participantSeries(participant.collectorId, windowSeconds = windowSeconds) }
                    .onSuccess { response ->
                        seriesByParticipantId = seriesByParticipantId + (collectorId to response.samples)
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
            }

            fun reloadExpandedSeries(windowSeconds: Long = seriesWindowSeconds) {
                scope.launch {
                    state.participants
                        .let { filterRecentlySeenParticipants(it, offlineFilterSeconds) }
                        .filter { it.collectorId in expandedParticipantIds }
                        .forEach { participant -> loadSeries(participant, windowSeconds) }
                }
            }

            suspend fun refresh() {
                runCatching { api.lobby() }
                    .onSuccess { lobby ->
                        val participants = lobby.participants
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
                                val participants = applyLobbyEvent(state.participants, event)
                                state = state.copy(
                                    localStatus = if (useEnglishLabels) "Live lobby events connected" else "已连接实时事件",
                                    participants = participants,
                                )
                                scope.launch {
                                    participants
                                        .let { filterRecentlySeenParticipants(it, offlineFilterSeconds) }
                                        .filter { it.collectorId in expandedParticipantIds }
                                        .forEach { participant -> loadSeries(participant) }
                                    clearMissingExpandedParticipants(participants)
                                }
                            }
                    },
                    {
                        state = state.copy(
                            localStatus = if (useEnglishLabels) {
                                "Live events disconnected; polling"
                            } else {
                                "实时事件断开，使用定时刷新"
                            },
                        )
                    },
                )
                try {
                    refresh()
                    while (true) {
                        delay(60_000)
                        refresh()
                    }
                } finally {
                    close()
                }
            }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(15_000)
                    val shouldPoll = state.localStatus.contains("disconnected", ignoreCase = true) ||
                        state.localStatus.contains("断开") ||
                        state.participants.isEmpty()
                    if (shouldPoll) {
                        refresh()
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
): List<Participant> {
    if (filterSeconds == null) return participants
    val cutoff = com.heartwith.shared.nowMs() - filterSeconds * 1000
    return participants.filter { participant ->
        participant.lastSeenMs?.let { it >= cutoff } == true
    }
}

@OptIn(InternalResourceApi::class)
private val heartwithCjkFont = FontResource(
    id = "font:HeartwithCJK",
    items = setOf(
        ResourceItem(
            qualifiers = setOf(),
            path = "composeResources/heartwith.heartwith_compose.generated.resources/font/HeartwithCJK.ttf",
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
        "snapshot" -> event.participants.sortedBy { it.displayName }
        "participant_update" -> {
            val participant = event.participant ?: return current
            val next = current
                .filterNot {
                    it.collectorId == participant.collectorId ||
                        it.displayName == participant.displayName
                }
                .plus(participant)
            next.sortedWith(compareBy<Participant> { it.status == "offline" }.thenBy { it.displayName })
        }
        else -> current
    }
