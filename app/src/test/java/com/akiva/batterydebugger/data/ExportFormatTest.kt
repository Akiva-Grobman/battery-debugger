package com.akiva.batterydebugger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportFormatTest {
    private val sample = Sample(
        timestampMs = 1_000, trigger = Trigger.PERIODIC, displayedLevel = 75, chargeCounterUah = 9_252_000,
        currentNowUa = -350_000, voltageMv = 3483, temperatureDeciC = 371, plugged = 0, status = 3,
        screenOn = true, foregroundApp = null, dischargePredictionMs = null, cycleCount = 341, stateOfHealth = 97,
    )

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { z ->
            files.forEach { (name, text) -> z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return bytes.toByteArray()
    }

    @Test
    fun `an export reads back exactly as written`() {
        val contents = ExportContents(
            samples = listOf(sample, sample.copy(timestampMs = 2_000, displayedLevel = 0, foregroundApp = "com.example")),
            markers = listOf(Marker(timestampMs = 3_000, type = MarkerType.BOOT)),
            device = DeviceInfo("Google Pixel 9 Pro", "17", 4_800_000, 4_000),
        )
        val bytes = ByteArrayOutputStream().also { ExportFormat.write(it, contents) }.toByteArray()

        assertEquals(contents, ExportFormat.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `an export from before device info existed still opens`() {
        val bytes = ByteArrayOutputStream()
            .also { ExportFormat.write(it, ExportContents(listOf(sample), emptyList(), device = null)) }
            .toByteArray()

        val read = ExportFormat.read(ByteArrayInputStream(bytes))

        assertNull(read.device)
        assertEquals(listOf(sample), read.samples)
    }

    @Test
    fun `rows that don't parse are skipped`() {
        val csv = "timestamp_ms,trigger,displayed_level,voltage_mv,temperature_decic,plugged,status,screen_on\n" +
            "1000,PERIODIC,75,3483,371,0,3,true\n" +
            "garbage\n" +
            "2000,NOT_A_TRIGGER,70,3480,371,0,3,true\n"

        val read = ExportFormat.read(ByteArrayInputStream(zip("samples.csv" to csv)))

        assertEquals(1, read.samples.size)
        assertEquals(75, read.samples[0].displayedLevel)
    }

    @Test(expected = InvalidExportException::class)
    fun `a zip without samples is rejected`() {
        ExportFormat.read(ByteArrayInputStream(zip("notes.txt" to "hello")))
    }
}
