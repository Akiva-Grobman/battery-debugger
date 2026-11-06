package com.akiva.batterydebugger.domain

import com.akiva.batterydebugger.data.Marker
import com.akiva.batterydebugger.data.MarkerType
import com.akiva.batterydebugger.data.Sample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS

/** BatteryManager.BATTERY_STATUS_CHARGING, duplicated so this file stays free of Android classes. */
private const val STATUS_CHARGING = 2

object Thresholds {
    const val DROP_POINTS = 10
    const val DROP_WINDOW_MS = 10 * MINUTE_MS
    const val SHUTDOWN_LEVEL = 5
    const val FAST_BELOW_LEVEL = 50
    const val FAST_FALL_POINTS = 2
    const val FAST_FALL_WINDOW_MS = MINUTE_MS
    const val NORMAL_INTERVAL_MS = 60_000L
    const val FAST_INTERVAL_MS = 10_000L
    /** Screen-off ticks only run while something else keeps the phone awake. */
    const val SCREEN_OFF_INTERVAL_MS = 5 * MINUTE_MS
    /** Longer than this between samples and averaging the two currents is no longer trustworthy. */
    const val MAX_INTEGRATION_STEP_MS = 15 * MINUTE_MS
    /** Longer than this between samples and the level in between is treated as unknown. */
    const val MAX_LEVEL_STEP_MS = 6 * HOUR_MS
    const val RETENTION_MS = 30L * 24 * HOUR_MS
    /** Above this draw, voltage sags too far below rest voltage to estimate level from it. */
    const val VOLTAGE_MAX_DRAW_UA = 300_000L
}

/**
 * Design Capacity assumed when recording, and for Exports that don't state one: a Pixel 9 Pro, as
 * reported by its own gauge (charge_full_design). No public API exposes the real value.
 */
const val DEFAULT_DESIGN_CAPACITY_UAH = 4_800_000L

// ---------------------------------------------------------------- sampling

fun nextSampleIntervalMs(recentNewestFirst: List<Sample>): Long {
    val latest = recentNewestFirst.firstOrNull() ?: return Thresholds.NORMAL_INTERVAL_MS
    if (!latest.screenOn) return Thresholds.SCREEN_OFF_INTERVAL_MS
    if (latest.plugged == 0 && latest.displayedLevel < Thresholds.FAST_BELOW_LEVEL) {
        return Thresholds.FAST_INTERVAL_MS
    }
    val windowStart = latest.timestampMs - Thresholds.FAST_FALL_WINDOW_MS
    val maxInWindow = recentNewestFirst
        .takeWhile { it.timestampMs >= windowStart }
        .maxOf { it.displayedLevel }
    return if (maxInWindow - latest.displayedLevel >= Thresholds.FAST_FALL_POINTS) {
        Thresholds.FAST_INTERVAL_MS
    } else {
        Thresholds.NORMAL_INTERVAL_MS
    }
}

// ---------------------------------------------------------------- recording gaps and shutdowns

/** Recording Gaps: from a stop until the next start or boot, or until now if still stopped. */
fun recordingGaps(markers: List<Marker>, nowMs: Long): List<LongRange> {
    val gaps = mutableListOf<LongRange>()
    var stoppedAt: Long? = null
    for (m in markers.sortedBy { it.timestampMs }) {
        when (m.type) {
            MarkerType.RECORDING_STOPPED -> if (stoppedAt == null) stoppedAt = m.timestampMs
            MarkerType.RECORDING_STARTED, MarkerType.BOOT -> stoppedAt?.let {
                gaps += it..m.timestampMs
                stoppedAt = null
            }
            MarkerType.SHUTDOWN -> Unit
        }
    }
    stoppedAt?.let { gaps += it..nowMs }
    return gaps
}

/** A time the phone went off while the app was recording. */
data class Shutdown(val lastSample: Sample, val bootAtMs: Long)

fun shutdowns(samples: List<Sample>, markers: List<Marker>, nowMs: Long): List<Shutdown> {
    val gaps = recordingGaps(markers, nowMs)
    return markers.filter { it.type == MarkerType.BOOT }.mapNotNull { boot ->
        val last = samples.lastOrNull { it.timestampMs < boot.timestampMs } ?: return@mapNotNull null
        // A gap ending at this boot means the app wasn't recording when the phone went off.
        val bootEndsGap = gaps.any { it.last == boot.timestampMs && it.first >= last.timestampMs }
        if (bootEndsGap) null else Shutdown(last, boot.timestampMs)
    }
}

private fun crossesBreak(fromMs: Long, toMs: Long, gaps: List<LongRange>, boots: List<Long>): Boolean =
    gaps.any { it.first < toMs && it.last > fromMs } || boots.any { it in (fromMs + 1)..toMs }

// ---------------------------------------------------------------- sudden drops

data class SuddenDrop(
    val start: Sample,
    /** Lowest sample reached during the fall, or the last sample before shutdown. */
    val end: Sample,
    val endedInShutdown: Boolean,
    /** Discharge Prediction made at the start of the drop. */
    val predictedRemainingMs: Long?,
    /** From the start of the drop until the phone next shut down, if it has. */
    val actualRemainingMs: Long?,
)

fun suddenDrops(samples: List<Sample>, markers: List<Marker>, nowMs: Long): List<SuddenDrop> {
    val gaps = recordingGaps(markers, nowMs)
    val boots = markers.filter { it.type == MarkerType.BOOT }.map { it.timestampMs }
    val offs = shutdowns(samples, markers, nowMs)

    fun nextShutdownAfter(t: Long) = offs.firstOrNull { it.lastSample.timestampMs >= t }

    val falls = mutableListOf<Pair<Sample, Sample>>()
    var i = 0
    while (i < samples.size) {
        val start = samples[i]
        var lowest = i
        var j = i + 1
        while (j < samples.size &&
            samples[j].timestampMs - start.timestampMs <= Thresholds.DROP_WINDOW_MS &&
            !crossesBreak(start.timestampMs, samples[j].timestampMs, gaps, boots)
        ) {
            if (samples[j].displayedLevel < samples[lowest].displayedLevel) lowest = j
            j++
        }
        if (start.displayedLevel - samples[lowest].displayedLevel >= Thresholds.DROP_POINTS) {
            falls += start to samples[lowest]
            i = lowest
        } else {
            i++
        }
    }

    val drops = falls.map { (start, end) ->
        val off = nextShutdownAfter(end.timestampMs)
        val endedInShutdown = off != null &&
            off.lastSample.timestampMs - end.timestampMs <= Thresholds.DROP_WINDOW_MS
        SuddenDrop(
            start = start,
            end = if (endedInShutdown) off!!.lastSample else end,
            endedInShutdown = endedInShutdown,
            predictedRemainingMs = start.dischargePredictionMs,
            actualRemainingMs = off?.let { it.lastSample.timestampMs - start.timestampMs },
        )
    }

    val shutdownDrops = offs
        .filter { it.lastSample.displayedLevel > Thresholds.SHUTDOWN_LEVEL }
        .filter { off -> drops.none { it.endedInShutdown && it.end == off.lastSample } }
        .map {
            SuddenDrop(
                start = it.lastSample,
                end = it.lastSample,
                endedInShutdown = true,
                predictedRemainingMs = it.lastSample.dischargePredictionMs,
                actualRemainingMs = 0,
            )
        }

    return (drops + shutdownDrops).sortedBy { it.start.timestampMs }
}

// ---------------------------------------------------------------- level estimates

/** Learned Full Charge: the charge counter at the latest sample showing 100%. */
fun learnedFullChargeUah(samples: List<Sample>): Long? =
    samples.lastOrNull { it.displayedLevel == 100 && it.chargeCounterUah != null }?.chargeCounterUah

fun chargeDerivedLevel(sample: Sample, learnedFullUah: Long?): Double? {
    val counter = sample.chargeCounterUah ?: return null
    if (learnedFullUah == null || learnedFullUah <= 0) return null
    return counter * 100.0 / learnedFullUah
}

/** Current flowing out of the battery, positive while discharging, whatever sign the platform uses. */
fun dischargeUa(sample: Sample): Long? {
    val current = sample.currentNowUa ?: return null
    return if (sample.status == STATUS_CHARGING) -abs(current) else abs(current)
}

/**
 * Level Estimate per sample: Design Capacity at the last 100%, minus current integrated since.
 * Measured against Design Capacity rather than the charge counter, which this phone's gauge corrupts.
 */
fun coulombCountedLevels(
    samples: List<Sample>,
    markers: List<Marker>,
    nowMs: Long,
    designCapacityUah: Long = DEFAULT_DESIGN_CAPACITY_UAH,
): List<Double?> {
    val gaps = recordingGaps(markers, nowMs)
    val boots = markers.filter { it.type == MarkerType.BOOT }.map { it.timestampMs }
    val result = ArrayList<Double?>(samples.size)
    var remainingUah: Double? = null
    var prev: Sample? = null
    for (s in samples) {
        val p = prev
        if (s.displayedLevel == 100) {
            remainingUah = designCapacityUah.toDouble()
        } else if (p != null && remainingUah != null) {
            val dt = s.timestampMs - p.timestampMs
            val inGap = gaps.any { it.first < s.timestampMs && it.last > p.timestampMs }
            val i0 = dischargeUa(p)
            val i1 = dischargeUa(s)
            remainingUah = when {
                inGap -> null
                // The phone was off between these samples and drew next to nothing.
                boots.any { it in (p.timestampMs + 1)..s.timestampMs } -> remainingUah
                // Over long sleeps the endpoint currents say little; use what the gauge counted instead.
                dt > Thresholds.MAX_INTEGRATION_STEP_MS || (!p.screenOn && !s.screenOn) ->
                    counterDeltaUah(p, s, dt)?.let { remainingUah + it }
                i0 == null || i1 == null -> null
                else -> remainingUah - (i0 + i1) / 2.0 * dt / HOUR_MS
            }
        }
        result += remainingUah?.let { it * 100.0 / designCapacityUah }
        prev = s
    }
    return result
}

/** Typical open-circuit voltage (mV) to level (%) for a 4.4 V lithium-ion cell; approximate by nature. */
private val OCV_CURVE = listOf(
    3400 to 0, 3600 to 5, 3680 to 10, 3730 to 20, 3780 to 30, 3820 to 40,
    3880 to 50, 3960 to 60, 4050 to 70, 4150 to 80, 4260 to 90, 4380 to 100,
)

fun levelFromVoltage(voltageMv: Int): Double {
    if (voltageMv <= OCV_CURVE.first().first) return 0.0
    if (voltageMv >= OCV_CURVE.last().first) return 100.0
    val (hi, i) = OCV_CURVE.withIndex().first { it.value.first >= voltageMv }.let { it.value to it.index }
    val lo = OCV_CURVE[i - 1]
    return lo.second + (voltageMv - lo.first).toDouble() / (hi.first - lo.first) * (hi.second - lo.second)
}

/** A voltage-based Level Estimate and the sample whose voltage it came from. */
data class VoltageEstimate(val level: Double, val fromMs: Long)

/**
 * Level Estimate per sample from voltage, only refreshed while the phone is on battery and drawing
 * little current (load and charging both distort voltage); otherwise the last value is held.
 */
fun voltageLevels(samples: List<Sample>): List<VoltageEstimate?> {
    var held: VoltageEstimate? = null
    return samples.map { s ->
        val draw = dischargeUa(s)
        if (s.plugged == 0 && draw != null && draw in 0..Thresholds.VOLTAGE_MAX_DRAW_UA) {
            held = VoltageEstimate(levelFromVoltage(s.voltageMv), s.timestampMs)
        }
        held
    }
}

/** Charge gained (negative when drawn) between two samples per the charge counter, if usable. */
private fun counterDeltaUah(a: Sample, b: Sample, dt: Long): Double? {
    if (dt > Thresholds.MAX_LEVEL_STEP_MS) return null
    val from = a.chargeCounterUah ?: return null
    val to = b.chargeCounterUah ?: return null
    return (to - from).toDouble()
}

/** How long the coulomb-counted remaining charge would last if the current draw continued. */
fun timeLeftAtCurrentDrawMs(sample: Sample, coulombCountedLevel: Double?, designCapacityUah: Long): Long? {
    val level = coulombCountedLevel ?: return null
    val draw = dischargeUa(sample) ?: return null
    if (sample.plugged != 0 || draw <= 0) return null
    return (level / 100 * designCapacityUah / draw * HOUR_MS).toLong()
}

/** Learned Full Charge as a fraction of Design Capacity; well above 1 means the gauge is confused. */
fun learnedToDesignRatio(samples: List<Sample>, designCapacityUah: Long): Double? =
    learnedFullChargeUah(samples)?.let { it.toDouble() / designCapacityUah }

// ---------------------------------------------------------------- daily drain

data class DailyDrain(
    val day: LocalDate,
    val screenOnPointsPerHour: Double?,
    val screenOffPointsPerHour: Double?,
)

fun dailyDrain(samples: List<Sample>, markers: List<Marker>, nowMs: Long, zone: ZoneId): List<DailyDrain> {
    val gaps = recordingGaps(markers, nowMs)
    val boots = markers.filter { it.type == MarkerType.BOOT }.map { it.timestampMs }

    class Acc { var onPts = 0.0; var onMs = 0L; var offPts = 0.0; var offMs = 0L }
    val byDay = sortedMapOf<LocalDate, Acc>()

    samples.zipWithNext().forEach { (a, b) ->
        val dt = b.timestampMs - a.timestampMs
        if (a.plugged != 0 || b.plugged != 0) return@forEach
        if (dt <= 0 || dt > Thresholds.MAX_LEVEL_STEP_MS) return@forEach
        if (crossesBreak(a.timestampMs, b.timestampMs, gaps, boots)) return@forEach
        val acc = byDay.getOrPut(Instant.ofEpochMilli(a.timestampMs).atZone(zone).toLocalDate()) { Acc() }
        val pts = (a.displayedLevel - b.displayedLevel).toDouble()
        if (a.screenOn) { acc.onPts += pts; acc.onMs += dt } else { acc.offPts += pts; acc.offMs += dt }
    }

    return byDay.map { (day, acc) ->
        DailyDrain(
            day = day,
            screenOnPointsPerHour = if (acc.onMs > 0) acc.onPts / acc.onMs * HOUR_MS else null,
            screenOffPointsPerHour = if (acc.offMs > 0) acc.offPts / acc.offMs * HOUR_MS else null,
        )
    }
}
