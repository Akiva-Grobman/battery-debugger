package com.akiva.batterydebugger.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.akiva.batterydebugger.MainActivity
import com.akiva.batterydebugger.R
import com.akiva.batterydebugger.data.BatteryDatabase
import com.akiva.batterydebugger.data.MarkerType
import com.akiva.batterydebugger.data.Trigger
import com.akiva.batterydebugger.data.Sample
import com.akiva.batterydebugger.domain.Thresholds
import com.akiva.batterydebugger.domain.VoltageEstimate
import com.akiva.batterydebugger.domain.nextSampleIntervalMs
import com.akiva.batterydebugger.domain.voltageLevels
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val CHANNEL_ID = "recording"
private const val NOTIFICATION_ID = 1
private const val RETENTION_CHECK_MS = 60 * 60_000L

/**
 * Records Samples periodically and on battery, screen and plug events.
 * Deliberately holds no wakelock: it never keeps the phone awake, so screen-off sampling only
 * happens when something else wakes the phone, and the app adds almost nothing to the drain.
 */
class RecorderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val writeLock = Mutex()
    private val dao by lazy { BatteryDatabase.get(this).records() }
    private val reader by lazy { BatteryReader(this) }

    private var lastBatteryState: Triple<Int, Int, Int>? = null
    private var lastRetentionCheckMs = 0L
    private var voltageEstimate: VoltageEstimate? = null
    private var shownNotification: Pair<String, String>? = null

    private val tick = object : Runnable {
        override fun run() {
            val self = this
            scope.launch {
                record(Trigger.PERIODIC)
                val interval = nextSampleIntervalMs(dao.latestSamples(20))
                handler.postDelayed(self, interval)
            }
        }
    }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    // This broadcast also fires on every voltage or temperature change; only level, plug and status matter.
                    val state = Triple(
                        intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
                        intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
                    )
                    if (state != lastBatteryState) {
                        lastBatteryState = state
                        scope.launch { record(Trigger.BATTERY_CHANGED) }
                    }
                }
                Intent.ACTION_SCREEN_OFF -> scope.launch { record(Trigger.SCREEN) }
                // The pending tick may be a long screen-off one; restart at the screen-on pace.
                Intent.ACTION_SCREEN_ON -> {
                    handler.removeCallbacks(tick)
                    handler.post(tick)
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> scope.launch { record(Trigger.PLUG) }
                // The phone is about to go off; write before returning or the marker is lost.
                Intent.ACTION_SHUTDOWN -> runBlocking(Dispatchers.IO) {
                    record(Trigger.PERIODIC)
                    Recorder.mark(this@RecorderService, MarkerType.SHUTDOWN)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            NOTIFICATION_ID,
            notification(getString(R.string.notification_recording), getString(R.string.notification_waiting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        registerReceiver(events, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SHUTDOWN)
        })
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        handler.removeCallbacks(tick)
        unregisterReceiver(events)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun record(trigger: Trigger) = writeLock.withLock {
        val sample = reader.read(trigger)
        if (sample != null) {
            dao.insert(sample)
            updateNotification(sample)
        }
        val now = System.currentTimeMillis()
        if (now - lastRetentionCheckMs > RETENTION_CHECK_MS) {
            lastRetentionCheckMs = now
            dao.deleteSamplesBefore(now - Thresholds.RETENTION_MS)
            dao.deleteMarkersBefore(now - Thresholds.RETENTION_MS)
        }
    }

    /** Shows the voltage-based Level Estimate; only re-posts when the text actually changes. */
    private suspend fun updateNotification(latest: Sample) {
        if (voltageEstimate == null) {
            // First sample since the service started: recover the held estimate from history.
            voltageEstimate = voltageLevels(dao.latestSamples(2_000).asReversed()).lastOrNull()
        } else {
            voltageLevels(listOf(latest)).single()?.let { voltageEstimate = it }
        }
        val estimate = voltageEstimate
        val texts = if (estimate == null) {
            getString(R.string.notification_recording) to getString(R.string.notification_waiting)
        } else {
            val at = Instant.ofEpochMilli(estimate.fromMs).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            getString(R.string.notification_estimate, estimate.level.toInt(), latest.displayedLevel) to
                getString(R.string.notification_estimate_detail, at)
        }
        if (texts == shownNotification) return
        shownNotification = texts
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(texts.first, texts.second))
    }

    private fun notification(title: String, text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_recording), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}

/** Marks every boot and resumes recording; also resumes after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Recorder.ensureRunning(context)
            return
        }
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Recorder.onBoot(context)
            } finally {
                pending.finish()
            }
        }
    }
}
