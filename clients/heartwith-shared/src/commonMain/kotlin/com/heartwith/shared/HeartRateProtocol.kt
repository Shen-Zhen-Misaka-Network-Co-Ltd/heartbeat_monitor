package com.heartwith.shared

import kotlin.math.abs

data class HeartRateSample(val tMs: Long, val bpm: Int)

data class HeartRateMeasurement(
    val rawHex: String,
    val flags: Int,
    val bpm: Int,
    val bpmFormat: String,
    val contactSupported: Boolean,
    val contactDetected: Boolean?,
    val energyExpended: Int?,
    val rrIntervalsMs: List<Int>,
)

fun parseHeartRateMeasurement(data: ByteArray): Int? {
    return parseHeartRateMeasurementDetail(data)?.bpm
}

fun parseHeartRateMeasurementDetail(data: ByteArray): HeartRateMeasurement? {
    if (data.size < 2) return null
    val flags = data[0].toInt()
    var offset = 1
    val sixteenBit = (flags and 0x01) != 0
    val bpm = if (sixteenBit) {
        if (data.size < 3) return null
        val value = (data[1].toInt() and 0xff) or ((data[2].toInt() and 0xff) shl 8)
        offset = 3
        value
    } else {
        offset = 2
        data[1].toInt() and 0xff
    }
    if (bpm !in MIN_BPM..MAX_BPM) return null

    val contactSupported = (flags and 0x04) != 0
    val contactDetected = if (contactSupported) (flags and 0x02) != 0 else null

    val energyExpended = if ((flags and 0x08) != 0) {
        if (data.size < offset + 2) return null
        val value = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
        offset += 2
        value
    } else {
        null
    }

    val rrIntervals = mutableListOf<Int>()
    if ((flags and 0x10) != 0) {
        while (data.size >= offset + 2) {
            val raw = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
            rrIntervals += ((raw * 1000L) / 1024L).toInt()
            offset += 2
        }
    }

    return HeartRateMeasurement(
        rawHex = data.toHexString(),
        flags = flags and 0xff,
        bpm = bpm,
        bpmFormat = if (sixteenBit) "uint16" else "uint8",
        contactSupported = contactSupported,
        contactDetected = contactDetected,
        energyExpended = energyExpended,
        rrIntervalsMs = rrIntervals,
    )
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

class HeartRateBatcher(private val policy: UploadPolicy = UploadPolicy()) {
    private val samples = ArrayDeque<HeartRateSample>()
    private var lastFlushMs: Long? = null
    private var lastUploadedBpm: Int? = null

    fun add(bpm: Int, tMs: Long = nowMs()): Boolean {
        if (bpm !in MIN_BPM..MAX_BPM) return false
        samples.addLast(HeartRateSample(tMs, bpm))
        trim(tMs)
        return true
    }

    fun size(): Int = samples.size

    fun shouldFlush(lowPower: Boolean = false, tMs: Long = nowMs()): Boolean {
        if (samples.isEmpty()) return false
        val first = samples.first()
        val last = samples.last()
        if (lastFlushMs == null) lastFlushMs = first.tMs
        val targetWindow = if (lowPower) policy.lowPowerBatchWindowMs else policy.batchWindowMs
        val maxWindow = if (lowPower) policy.lowPowerBatchWindowMs else policy.maxBatchWindowMs
        if (tMs - (lastFlushMs ?: first.tMs) >= targetWindow) return true
        if (last.tMs - first.tMs >= maxWindow) return true
        val uploaded = lastUploadedBpm
        return uploaded != null && abs(last.bpm - uploaded) >= policy.changeFlushBpm
    }

    fun buildPayload(
        collectorId: String,
        seq: Long,
        displayName: String,
        deviceModel: String,
        ble: BleInfo,
        sentAtMs: Long = nowMs(),
    ): BatchPayload = BatchPayload(
        collectorId = collectorId,
        seq = seq,
        sentAtMs = sentAtMs,
        displayName = displayName,
        deviceModel = deviceModel,
        samples = payloadSamples().map { RelativeSample(dtMs = it.tMs - sentAtMs, bpm = it.bpm) },
        ble = ble,
    )

    fun markUploaded(tMs: Long = nowMs()) {
        lastUploadedBpm = samples.lastOrNull()?.bpm
        samples.clear()
        lastFlushMs = tMs
    }

    private fun trim(currentMs: Long) {
        val cutoff = currentMs - policy.offlineCacheSeconds * 1000
        while (samples.firstOrNull()?.tMs?.let { it < cutoff } == true) {
            samples.removeFirst()
        }
    }

    private fun payloadSamples(): List<HeartRateSample> {
        if (samples.size <= MAX_DIRECT_UPLOAD_SAMPLES) return samples.toList()

        val last = samples.last()
        val downsampled = mutableListOf<HeartRateSample>()
        var nextBucketStart = Long.MIN_VALUE
        for (sample in samples) {
            if (sample === last) continue
            if (sample.tMs >= nextBucketStart) {
                downsampled.add(sample)
                nextBucketStart = sample.tMs + OFFLINE_DOWNSAMPLE_STEP_MS
            }
        }
        if (downsampled.lastOrNull() != last) {
            downsampled.add(last)
        }
        return downsampled
    }

    private companion object {
        const val MAX_DIRECT_UPLOAD_SAMPLES = 120
        const val OFFLINE_DOWNSAMPLE_STEP_MS = 5_000L
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
