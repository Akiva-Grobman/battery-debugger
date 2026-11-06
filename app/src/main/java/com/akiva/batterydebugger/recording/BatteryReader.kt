package com.akiva.batterydebugger.recording

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.akiva.batterydebugger.data.Sample
import com.akiva.batterydebugger.data.Trigger

/** BatteryManager property id for state of health; public value, needs BATTERY_STATS. */
private const val PROPERTY_STATE_OF_HEALTH = 10

/** Reads one Sample from the public battery, power and usage APIs. */
class BatteryReader(private val context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val usage = context.getSystemService(UsageStatsManager::class.java)

    private var foregroundApp: String? = null
    private var usageQueriedUntilMs = System.currentTimeMillis() - 60 * 60_000L

    fun read(trigger: Trigger, nowMs: Long = System.currentTimeMillis()): Sample? {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val screenOn = power.isInteractive

        return Sample(
            timestampMs = nowMs,
            trigger = trigger,
            displayedLevel = level * 100 / scale,
            chargeCounterUah = intProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.toLong(),
            currentNowUa = intProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toLong(),
            voltageMv = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            temperatureDeciC = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            status = status.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            screenOn = screenOn,
            foregroundApp = if (screenOn) updateForegroundApp(nowMs) else null,
            dischargePredictionMs = power.batteryDischargePrediction?.toMillis(),
            cycleCount = status.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 },
            stateOfHealth = try {
                intProperty(PROPERTY_STATE_OF_HEALTH)
            } catch (_: SecurityException) {
                null
            },
        )
    }

    private fun intProperty(id: Int): Int? =
        battery.getIntProperty(id).takeIf { it != Int.MIN_VALUE }

    private fun updateForegroundApp(nowMs: Long): String? {
        val events = usage.queryEvents(usageQueriedUntilMs, nowMs) ?: return foregroundApp
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foregroundApp = event.packageName
        }
        usageQueriedUntilMs = nowMs
        return foregroundApp
    }
}
