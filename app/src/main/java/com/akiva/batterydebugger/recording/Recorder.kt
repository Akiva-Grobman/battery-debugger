package com.akiva.batterydebugger.recording

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import com.akiva.batterydebugger.AppMode
import com.akiva.batterydebugger.data.BatteryDatabase
import com.akiva.batterydebugger.data.Marker
import com.akiva.batterydebugger.data.MarkerType

/** The four things that must be true before recording can start. */
data class SetupStatus(
    val batteryStatsGranted: Boolean,
    val usageAccessGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val notificationsAllowed: Boolean,
) {
    val complete get() = batteryStatsGranted && usageAccessGranted && batteryOptimizationIgnored && notificationsAllowed
}

/** Starts and stops recording and remembers whether it is on. */
object Recorder {
    private const val PREFS = "recorder"
    private const val KEY_RECORDING = "recording"

    fun setupStatus(context: Context): SetupStatus {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val usageMode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        )
        return SetupStatus(
            batteryStatsGranted = granted(context, Manifest.permission.BATTERY_STATS),
            usageAccessGranted = usageMode == AppOpsManager.MODE_ALLOWED,
            batteryOptimizationIgnored = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
            notificationsAllowed = granted(context, Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    fun isRecording(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECORDING, false)

    suspend fun start(context: Context) {
        setRecording(context, true)
        mark(context, MarkerType.RECORDING_STARTED)
        launchService(context)
    }

    suspend fun stop(context: Context) {
        setRecording(context, false)
        mark(context, MarkerType.RECORDING_STOPPED)
        context.stopService(Intent(context, RecorderService::class.java))
    }

    /** A boot always resumes recording, even after a stop. */
    suspend fun onBoot(context: Context) {
        if (AppMode.get(context) != AppMode.RECORDING) return
        mark(context, MarkerType.BOOT)
        if (!setupStatus(context).complete) return
        setRecording(context, true)
        launchService(context)
    }

    /** Restarts the service if recording is on but the process was killed, e.g. by an app update. */
    fun ensureRunning(context: Context) {
        if (isRecording(context)) launchService(context)
    }

    suspend fun mark(context: Context, type: MarkerType) {
        BatteryDatabase.get(context).records().insert(Marker(timestampMs = System.currentTimeMillis(), type = type))
    }

    private fun launchService(context: Context) {
        context.startForegroundService(Intent(context, RecorderService::class.java))
    }

    private fun setRecording(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECORDING, on).apply()
    }

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
