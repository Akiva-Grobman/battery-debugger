package com.akiva.batterydebugger.domain

import com.akiva.batterydebugger.data.Marker
import com.akiva.batterydebugger.data.MarkerType
import com.akiva.batterydebugger.data.Sample
import com.akiva.batterydebugger.data.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

private const val MIN = 60_000L

private fun sample(
    minute: Long,
    level: Int,
    currentUa: Long = -350_000,
    plugged: Int = 0,
    status: Int = 3,
    screenOn: Boolean = true,
    predictionMs: Long? = null,
) = Sample(
    timestampMs = minute * MIN, trigger = Trigger.PERIODIC, displayedLevel = level,
    chargeCounterUah = null, currentNowUa = currentUa, voltageMv = 3700, temperatureDeciC = 300,
    plugged = plugged, status = status, screenOn = screenOn, foregroundApp = null,
    dischargePredictionMs = predictionMs, cycleCount = 341, stateOfHealth = null,
)

private fun marker(minute: Long, type: MarkerType) = Marker(timestampMs = minute * MIN, type = type)

class AnalysisTest {
    @Test
    fun `fall from 75 to 0 then shutdown is one drop ending in shutdown`() {
        val samples = listOf(
            sample(0, 75, predictionMs = 300 * MIN), sample(1, 60), sample(2, 30), sample(3, 0),
            sample(40, 0, plugged = 1, status = 2),
        )
        val markers = listOf(marker(20, MarkerType.BOOT))

        val drops = suddenDrops(samples, markers, nowMs = 60 * MIN)

        assertEquals(1, drops.size)
        assertTrue(drops[0].endedInShutdown)
        assertEquals(75, drops[0].start.displayedLevel)
        assertEquals(300 * MIN, drops[0].predictedRemainingMs)
        assertEquals(3 * MIN, drops[0].actualRemainingMs)
    }

    @Test
    fun `slow decline is not a drop`() {
        val samples = (0L..60L).map { sample(it * 5, 80 - it.toInt() / 2) }
        assertTrue(suddenDrops(samples, emptyList(), nowMs = 400 * MIN).isEmpty())
    }

    @Test
    fun `shutdown while showing more than 5 percent is a drop`() {
        val samples = listOf(sample(0, 42), sample(1, 41))
        val drops = suddenDrops(samples, listOf(marker(10, MarkerType.BOOT)), nowMs = 20 * MIN)
        assertEquals(1, drops.size)
        assertEquals(41, drops[0].start.displayedLevel)
    }

    @Test
    fun `no drop is flagged across a recording gap`() {
        val samples = listOf(sample(0, 60), sample(5, 40))
        val markers = listOf(marker(1, MarkerType.RECORDING_STOPPED), marker(4, MarkerType.RECORDING_STARTED))
        assertTrue(suddenDrops(samples, markers, nowMs = 10 * MIN).isEmpty())
    }

    @Test
    fun `phone switched off during a gap is not a shutdown drop`() {
        val samples = listOf(sample(0, 60))
        val markers = listOf(marker(1, MarkerType.RECORDING_STOPPED), marker(30, MarkerType.BOOT))
        assertTrue(shutdowns(samples, markers, nowMs = 40 * MIN).isEmpty())
    }

    @Test
    fun `stop with no restart leaves a gap open until now`() {
        val gaps = recordingGaps(listOf(marker(5, MarkerType.RECORDING_STOPPED)), nowMs = 9 * MIN)
        assertEquals(listOf(5 * MIN..9 * MIN), gaps)
    }

    @Test
    fun `current-counted level falls by charge drawn against design capacity`() {
        // 4.8 A for one hour drains the whole design capacity.
        val samples = listOf(sample(0, 100, currentUa = -4_800_000), sample(15, 99, currentUa = -4_800_000))
        val levels = coulombCountedLevels(samples, emptyList(), nowMs = 20 * MIN)
        assertEquals(100.0, levels[0]!!, 0.01)
        assertEquals(75.0, levels[1]!!, 0.01)
    }

    @Test
    fun `current-counted level is unknown before any full charge and after a gap`() {
        val samples = listOf(sample(0, 80), sample(1, 100), sample(2, 99), sample(10, 98))
        val markers = listOf(marker(3, MarkerType.RECORDING_STOPPED), marker(9, MarkerType.RECORDING_STARTED))
        val levels = coulombCountedLevels(samples, markers, nowMs = 20 * MIN)
        assertNull(levels[0])
        assertEquals(100.0, levels[1]!!, 0.01)
        assertNull(levels[3])
    }

    @Test
    fun `charging current adds charge whatever its sign`() {
        val samples = listOf(sample(0, 100), sample(15, 100, currentUa = -4_800_000, status = 2).copy(displayedLevel = 99))
        val levels = coulombCountedLevels(samples, emptyList(), nowMs = 20 * MIN)
        assertTrue(levels[1]!! > 100.0 - 1.5)
    }

    @Test
    fun `sampling speeds up below 50 percent on battery`() {
        assertEquals(Thresholds.FAST_INTERVAL_MS, nextSampleIntervalMs(listOf(sample(0, 49))))
        assertEquals(Thresholds.NORMAL_INTERVAL_MS, nextSampleIntervalMs(listOf(sample(0, 49, plugged = 1))))
        assertEquals(Thresholds.NORMAL_INTERVAL_MS, nextSampleIntervalMs(listOf(sample(0, 80))))
    }

    @Test
    fun `sampling speeds up when the level falls fast`() {
        val newestFirst = listOf(sample(1, 78).copy(timestampMs = 60 * MIN + 50_000), sample(60, 80))
        assertEquals(Thresholds.FAST_INTERVAL_MS, nextSampleIntervalMs(newestFirst))
    }

    @Test
    fun `daily drain splits screen on and off`() {
        val samples = listOf(
            sample(0, 80, screenOn = true), sample(10, 78, screenOn = false), sample(20, 77, screenOn = false),
        )
        val drain = dailyDrain(samples, emptyList(), nowMs = 30 * MIN, zone = ZoneOffset.UTC).single()
        assertEquals(12.0, drain.screenOnPointsPerHour!!, 0.01)
        assertEquals(6.0, drain.screenOffPointsPerHour!!, 0.01)
    }

    @Test
    fun `voltage maps onto the curve and clamps at the ends`() {
        assertEquals(50.0, levelFromVoltage(3880), 0.01)
        assertEquals(45.0, levelFromVoltage(3850), 0.01)
        assertEquals(0.0, levelFromVoltage(3000), 0.01)
        assertEquals(100.0, levelFromVoltage(4450), 0.01)
    }

    @Test
    fun `voltage estimate only refreshes at light load on battery and is held otherwise`() {
        val samples = listOf(
            sample(0, 80, currentUa = -200_000).copy(voltageMv = 3880),
            sample(1, 79, currentUa = -1_500_000).copy(voltageMv = 3500),
            sample(2, 79, currentUa = 900_000, plugged = 1, status = 2).copy(voltageMv = 4200),
        )
        val levels = voltageLevels(samples)
        assertEquals(50.0, levels[0]!!.level, 0.01)
        assertEquals(levels[0], levels[1])
        assertEquals(levels[0], levels[2])
    }

    @Test
    fun `sampling slows right down with the screen off`() {
        assertEquals(Thresholds.SCREEN_OFF_INTERVAL_MS, nextSampleIntervalMs(listOf(sample(0, 30, screenOn = false))))
    }

    @Test
    fun `long screen-off stretch uses the charge counter change instead of current`() {
        val samples = listOf(
            sample(0, 100, currentUa = -2_000_000, screenOn = false).copy(chargeCounterUah = 13_000_000),
            sample(120, 99, currentUa = -2_000_000, screenOn = false).copy(chargeCounterUah = 12_952_000),
        )
        val levels = coulombCountedLevels(samples, emptyList(), nowMs = 200 * MIN)
        // 48 mAh drawn out of 4800 mAh design.
        assertEquals(99.0, levels[1]!!, 0.01)
    }
}
