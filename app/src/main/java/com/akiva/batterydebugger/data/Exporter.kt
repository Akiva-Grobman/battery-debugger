package com.akiva.batterydebugger.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.akiva.batterydebugger.domain.DEFAULT_DESIGN_CAPACITY_UAH
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Writes this phone's records as an Export and returns a share intent for it. */
object Exporter {
    suspend fun shareIntent(context: Context): Intent {
        val dao = BatteryDatabase.get(context).records()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val zip = File(dir, "battery-log-$stamp.zip")

        val device = DeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            designCapacityUah = DEFAULT_DESIGN_CAPACITY_UAH,
            exportedAtMs = System.currentTimeMillis(),
        )
        zip.outputStream().use { ExportFormat.write(it, ExportContents(dao.allSamples(), dao.allMarkers(), device)) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}
