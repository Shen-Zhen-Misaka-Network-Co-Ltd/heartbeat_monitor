package com.heartwith.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"
const val HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
const val MIN_BPM = 30
const val MAX_BPM = 240

@Serializable
data class UploadPolicy(
    @SerialName("batch_window_ms") val batchWindowMs: Long = 10_000,
    @SerialName("max_batch_window_ms") val maxBatchWindowMs: Long = 15_000,
    @SerialName("low_power_batch_window_ms") val lowPowerBatchWindowMs: Long = 30_000,
    @SerialName("change_flush_bpm") val changeFlushBpm: Int = 3,
    @SerialName("offline_cache_seconds") val offlineCacheSeconds: Long = 300,
)

@Serializable
data class SessionRequest(
    @SerialName("display_name") val displayName: String,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("client_platform") val clientPlatform: String,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
data class SessionResponse(
    @SerialName("collector_id") val collectorId: String,
    @SerialName("collector_token") val collectorToken: String,
    @SerialName("ingest_url") val ingestUrl: String,
    val policy: UploadPolicy,
)

@Serializable
data class RelativeSample(
    @SerialName("dt_ms") val dtMs: Long,
    val bpm: Int,
)

@Serializable
data class BatchPayload(
    val schema: Int = 1,
    @SerialName("collector_id") val collectorId: String,
    val seq: Long,
    @SerialName("sent_at_ms") val sentAtMs: Long,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_model") val deviceModel: String,
    val samples: List<RelativeSample>,
    val ble: BleInfo = BleInfo(),
)

@Serializable
data class BleInfo(
    val rssi: Int? = null,
    val source: String = "heart_rate_service_2a37",
)

@Serializable
data class IngestResponse(
    val ok: Boolean,
    val accepted: Int,
    @SerialName("server_time_ms") val serverTimeMs: Long,
    @SerialName("next_policy") val nextPolicy: UploadPolicy? = null,
)

@Serializable
data class LobbyResponse(
    @SerialName("server_time_ms") val serverTimeMs: Long,
    val participants: List<Participant>,
)

data class BleDeviceCandidate(
    val address: String,
    val name: String,
    val rssi: Int,
    val hasHeartRateService: Boolean,
)

@Serializable
data class LobbyEventEnvelope(
    val type: String,
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    val participants: List<Participant> = emptyList(),
    val participant: Participant? = null,
)

@Serializable
data class Participant(
    @SerialName("collector_id") val collectorId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("device_model") val deviceModel: String,
    val status: String,
    @SerialName("last_bpm") val lastBpm: Int? = null,
    @SerialName("last_seen_ms") val lastSeenMs: Long? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long? = null,
)

@Serializable
data class SeriesResponse(
    @SerialName("collector_id") val collectorId: String,
    @SerialName("window_seconds") val windowSeconds: Long,
    val samples: List<SeriesSample>,
)

@Serializable
data class SeriesSample(
    @SerialName("t_ms") val tMs: Long,
    val bpm: Int,
)
