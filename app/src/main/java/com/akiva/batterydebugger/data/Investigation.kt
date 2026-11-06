package com.akiva.batterydebugger.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** An Export opened in Investigation Mode, and the name of the file it came from. */
data class OpenedExport(val contents: ExportContents, val fileName: String)

/**
 * The single Export being investigated. Kept as its own file, apart from this phone's recordings;
 * opening another Export replaces it.
 */
object Investigation {
    private const val PREFS = "investigation"
    private const val KEY_FILE_NAME = "file_name"

    private fun stored(context: Context) = File(context.filesDir, "investigation/current.zip")

    /** Copies and parses the Export at [uri]; throws if it isn't one, leaving the previous Export in place. */
    fun open(context: Context, uri: Uri): OpenedExport {
        val incoming = File(context.cacheDir, "incoming.zip")
        context.contentResolver.openInputStream(uri)!!.use { input -> incoming.outputStream().use { input.copyTo(it) } }
        val contents = incoming.inputStream().use { ExportFormat.read(it) }
        if (contents.samples.isEmpty()) throw InvalidExportException("The file has no samples")

        val target = stored(context).apply { parentFile?.mkdirs() }
        incoming.copyTo(target, overwrite = true)
        incoming.delete()
        val name = displayName(context, uri) ?: target.name
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FILE_NAME, name).apply()
        return OpenedExport(contents, name)
    }

    fun load(context: Context): OpenedExport? {
        val file = stored(context)
        if (!file.exists()) return null
        val contents = runCatching { file.inputStream().use { ExportFormat.read(it) } }.getOrNull() ?: return null
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FILE_NAME, null) ?: file.name
        return OpenedExport(contents, name)
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
