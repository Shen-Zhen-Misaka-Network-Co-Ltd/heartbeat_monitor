package com.heartwith.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HeartRateProtocolTest {
    @Test
    fun parses8BitHeartRateMeasurements() {
        assertEquals(82, parseHeartRateMeasurement(byteArrayOf(0x00, 82)))
    }

    @Test
    fun parses16BitHeartRateMeasurements() {
        assertEquals(220, parseHeartRateMeasurement(byteArrayOf(0x01, 220.toByte(), 0x00)))
    }

    @Test
    fun rejectsEmptyShortAndOutOfRangeMeasurements() {
        assertEquals(null, parseHeartRateMeasurement(byteArrayOf()))
        assertEquals(null, parseHeartRateMeasurement(byteArrayOf(0x01, 0x2c)))
        assertEquals(null, parseHeartRateMeasurement(byteArrayOf(0x00, 20)))
    }

    @Test
    fun stableHeartRateDoesNotFlushEverySecond() {
        val batcher = HeartRateBatcher()
        repeat(5) { offset ->
            batcher.add(82, tMs = offset * 1_000L)
        }

        assertFalse(batcher.shouldFlush(tMs = 5_000))
        assertTrue(batcher.shouldFlush(tMs = 10_000))
    }

    @Test
    fun heartRateChangeFlushesEarlyAfterUploadBaseline() {
        val batcher = HeartRateBatcher()
        batcher.add(80, tMs = 0)
        batcher.markUploaded(tMs = 0)

        batcher.add(84, tMs = 1_000)

        assertTrue(batcher.shouldFlush(tMs = 1_000))
    }

    @Test
    fun lowPowerModeUsesRelaxedWindow() {
        val batcher = HeartRateBatcher()
        batcher.add(80, tMs = 0)
        batcher.add(81, tMs = 16_000)

        assertFalse(batcher.shouldFlush(lowPower = true, tMs = 16_000))
        assertTrue(batcher.shouldFlush(lowPower = true, tMs = 30_000))
    }

    @Test
    fun offlineCacheDropsSamplesOlderThanPolicy() {
        val batcher = HeartRateBatcher(UploadPolicy(offlineCacheSeconds = 10))
        batcher.add(80, tMs = 0)
        batcher.add(81, tMs = 9_000)
        batcher.add(82, tMs = 11_000)

        assertEquals(2, batcher.size())
    }

    @Test
    fun longOfflinePayloadIsDownsampledButKeepsNewestSample() {
        val batcher = HeartRateBatcher()
        repeat(300) { second ->
            batcher.add(70 + second % 10, tMs = second * 1_000L)
        }

        val payload = batcher.buildPayload(
            collectorId = "col_test",
            seq = 1,
            displayName = "Tester",
            deviceModel = "Mi Band",
            ble = BleInfo(rssi = -61),
            sentAtMs = 300_000,
        )

        assertTrue(payload.samples.size < 100)
        assertNotNull(payload.samples.lastOrNull())
        assertEquals(-1_000, payload.samples.last().dtMs)
    }
}
