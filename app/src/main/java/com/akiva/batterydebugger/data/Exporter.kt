package com.akiva.batterydebugger.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes all records as CSVs in a zip and returns a share intent for it. */
object Exporter {
    suspend fun shareIntent(context: Context): Intent {
        val dao = BatteryDatabase.get(context).records()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val zip = File(dir, "battery-log-$stamp.zip")

        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("samples.csv"))
            out.write(samplesCsv(dao.allSamples()).toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("markers.csv"))
            out.write(markersCsv(dao.allMarkers()).toByteArray())
            out.closeEntry()
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    private fun samplesCsv(samples: List<Sample>) = buildString {
        appendLine(
            "timestamp_ms,trigger,displayed_level,charge_counter_uah,current_now_ua,voltage_mv," +
                "temperature_decic,plugged,status,screen_on,foreground_app,discharge_prediction_ms," +
                "cycle_count,state_of_health",
        )
        samples.forEach { s ->
            appendLine(
                listOf(
                    s.timestampMs, s.trigger, s.displayedLevel, s.chargeCounterUah, s.currentNowUa, s.voltageMv,
                    s.temperatureDeciC, s.plugged, s.status, s.screenOn, s.foregroundApp, s.dischargePredictionMs,
                    s.cycleCount, s.stateOfHealth,
                ).joinToString(",") { it?.toString() ?: "" },
            )
        }
    }

    private fun markersCsv(markers: List<Marker>) = buildString {
        appendLine("timestamp_ms,type")
        markers.forEach { appendLine("${it.timestampMs},${it.type}") }
    }
}
