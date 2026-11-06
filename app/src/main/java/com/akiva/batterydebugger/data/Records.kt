package com.akiva.batterydebugger.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** What caused a sample to be taken. */
enum class Trigger { PERIODIC, BATTERY_CHANGED, SCREEN, PLUG, RECORDING_STARTED }

/** One reading of the battery and its context at a moment in time. */
@Entity(tableName = "samples")
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val trigger: Trigger,
    /** Displayed Level, 0–100. */
    val displayedLevel: Int,
    val chargeCounterUah: Long?,
    /** As reported by the platform; sign is normalised in analysis, not here. */
    val currentNowUa: Long?,
    val voltageMv: Int,
    val temperatureDeciC: Int,
    /** BatteryManager.BATTERY_PLUGGED_* (0 = on battery). */
    val plugged: Int,
    /** BatteryManager.BATTERY_STATUS_*. */
    val status: Int,
    val screenOn: Boolean,
    val foregroundApp: String?,
    /** Discharge Prediction in ms, when the system offers one. */
    val dischargePredictionMs: Long?,
    val cycleCount: Int?,
    /** State of health %, only readable with BATTERY_STATS granted. */
    val stateOfHealth: Int?,
)

enum class MarkerType { BOOT, SHUTDOWN, RECORDING_STARTED, RECORDING_STOPPED }

/** A point event that is not a battery reading. */
@Entity(tableName = "markers")
data class Marker(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val type: MarkerType,
)
