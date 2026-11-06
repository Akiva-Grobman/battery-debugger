package com.akiva.batterydebugger.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.akiva.batterydebugger.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFormat)

@Composable
fun formatDuration(ms: Long?): String {
    if (ms == null) return "—"
    val minutes = (ms / 60_000).toInt()
    return if (minutes < 60) {
        stringResource(R.string.duration_minutes, minutes)
    } else {
        stringResource(R.string.duration_hours, minutes / 60, minutes % 60)
    }
}

fun formatPercent(value: Double?): String = value?.let { "%.0f%%".format(it) } ?: "—"

fun formatSigned(value: Double?): String = value?.let { "%+.0f".format(it) } ?: "—"
