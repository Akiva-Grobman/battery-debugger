package com.akiva.batterydebugger.data

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The Subject Phone an Export came from. */
data class DeviceInfo(
    val model: String,
    val androidVersion: String,
    val designCapacityUah: Long,
    val exportedAtMs: Long,
)

/** Everything read back from an Export; [device] is null for Exports made before device.csv existed. */
data class ExportContents(
    val samples: List<Sample>,
    val markers: List<Marker>,
    val device: DeviceInfo?,
)

class InvalidExportException(message: String) : Exception(message)

/** Reads and writes the Export zip: samples.csv, markers.csv and device.csv. */
object ExportFormat {
    private const val SAMPLES = "samples.csv"
    private const val MARKERS = "markers.csv"
    private const val DEVICE = "device.csv"

    private val sampleColumns = listOf(
        "timestamp_ms", "trigger", "displayed_level", "charge_counter_uah", "current_now_ua", "voltage_mv",
        "temperature_decic", "plugged", "status", "screen_on", "foreground_app", "discharge_prediction_ms",
        "cycle_count", "state_of_health",
    )

    fun write(out: OutputStream, contents: ExportContents) {
        ZipOutputStream(out).use { zip ->
            zip.entry(SAMPLES, samplesCsv(contents.samples))
            zip.entry(MARKERS, markersCsv(contents.markers))
            contents.device?.let { zip.entry(DEVICE, deviceCsv(it)) }
        }
    }

    fun read(input: InputStream): ExportContents {
        val files = mutableMapOf<String, String>()
        ZipInputStream(input).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                if (name in setOf(SAMPLES, MARKERS, DEVICE)) files[name] = zip.readBytes().decodeToString()
            }
        }
        val samples = files[SAMPLES] ?: throw InvalidExportException("No $SAMPLES in the file")
        return ExportContents(
            samples = parseSamples(samples),
            markers = files[MARKERS]?.let(::parseMarkers).orEmpty(),
            device = files[DEVICE]?.let(::parseDevice),
        )
    }

    private fun ZipOutputStream.entry(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }

    private fun samplesCsv(samples: List<Sample>) = buildString {
        appendLine(sampleColumns.joinToString(","))
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

    private fun deviceCsv(d: DeviceInfo) = buildString {
        appendLine("key,value")
        appendLine("model,${d.model.replace(',', ' ')}")
        appendLine("android_version,${d.androidVersion}")
        appendLine("design_capacity_uah,${d.designCapacityUah}")
        appendLine("exported_at_ms,${d.exportedAtMs}")
    }

    /** Rows as column-name → value maps, so column order and extra columns don't matter. */
    private fun rows(csv: String): List<Map<String, String>> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.firstOrNull()?.split(',') ?: return emptyList()
        return lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
    }

    private fun parseSamples(csv: String): List<Sample> = rows(csv).mapNotNull { r ->
        // A row that doesn't parse is skipped rather than failing the whole import.
        runCatching {
            Sample(
                timestampMs = r.getValue("timestamp_ms").toLong(),
                trigger = Trigger.valueOf(r.getValue("trigger")),
                displayedLevel = r.getValue("displayed_level").toInt(),
                chargeCounterUah = r["charge_counter_uah"]?.toLongOrNull(),
                currentNowUa = r["current_now_ua"]?.toLongOrNull(),
                voltageMv = r.getValue("voltage_mv").toInt(),
                temperatureDeciC = r.getValue("temperature_decic").toInt(),
                plugged = r.getValue("plugged").toInt(),
                status = r.getValue("status").toInt(),
                screenOn = r.getValue("screen_on").toBooleanStrict(),
                foregroundApp = r["foreground_app"]?.takeIf { it.isNotEmpty() },
                dischargePredictionMs = r["discharge_prediction_ms"]?.toLongOrNull(),
                cycleCount = r["cycle_count"]?.toIntOrNull(),
                stateOfHealth = r["state_of_health"]?.toIntOrNull(),
            )
        }.getOrNull()
    }.sortedBy { it.timestampMs }

    private fun parseMarkers(csv: String): List<Marker> = rows(csv).mapNotNull { r ->
        runCatching { Marker(timestampMs = r.getValue("timestamp_ms").toLong(), type = MarkerType.valueOf(r.getValue("type"))) }
            .getOrNull()
    }.sortedBy { it.timestampMs }

    private fun parseDevice(csv: String): DeviceInfo? {
        val values = rows(csv).associate { (it["key"] ?: "") to (it["value"] ?: "") }
        return DeviceInfo(
            model = values["model"] ?: return null,
            androidVersion = values["android_version"].orEmpty(),
            designCapacityUah = values["design_capacity_uah"]?.toLongOrNull() ?: return null,
            exportedAtMs = values["exported_at_ms"]?.toLongOrNull() ?: 0,
        )
    }
}
