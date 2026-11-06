package com.akiva.batterydebugger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.akiva.batterydebugger.R
import com.akiva.batterydebugger.domain.Thresholds
import com.akiva.batterydebugger.domain.chargeDerivedLevel

private const val HOUR_MS = 3_600_000L

@Composable
fun HistoryScreen(data: Analysed) {
    var days by rememberSaveable { mutableStateOf(1) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = days == 1, onClick = { days = 1 }, label = { Text(stringResource(R.string.window_24h)) })
            FilterChip(selected = days == 7, onClick = { days = 7 }, label = { Text(stringResource(R.string.window_7d)) })
        }

        val colors = SeriesColors(
            displayed = MaterialTheme.colorScheme.primary,
            coulomb = MaterialTheme.colorScheme.tertiary,
            voltage = Color(0xFFE8912D),
            counter = MaterialTheme.colorScheme.secondary,
            drop = MaterialTheme.colorScheme.error,
            gap = MaterialTheme.colorScheme.surfaceVariant,
            grid = MaterialTheme.colorScheme.outlineVariant,
        )
        LevelChart(data, days * 24 * HOUR_MS, colors)
        Legend(colors)

        Text(stringResource(R.string.sudden_drops), style = MaterialTheme.typography.titleMedium)
        if (data.drops.isEmpty()) Text(stringResource(R.string.none_recorded))
        data.drops.asReversed().forEach { drop ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formatTime(drop.start.timestampMs), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            if (drop.endedInShutdown) R.string.drop_summary_shutdown else R.string.drop_summary,
                            drop.start.displayedLevel,
                            drop.end.displayedLevel,
                            formatDuration(drop.end.timestampMs - drop.start.timestampMs),
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.drop_prediction,
                            formatDuration(drop.predictedRemainingMs),
                            drop.actualRemainingMs?.let { formatDuration(it) } ?: stringResource(R.string.still_on),
                        ),
                    )
                }
            }
        }

        Text(stringResource(R.string.daily_drain_title), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.col_day), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.col_screen_on), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.col_screen_off), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        }
        data.daily.asReversed().forEach { d ->
            Row(Modifier.fillMaxWidth()) {
                Text(d.day.toString(), Modifier.weight(1f))
                Text(d.screenOnPointsPerHour?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
                Text(d.screenOffPointsPerHour?.let { "%.1f".format(it) } ?: "—", Modifier.weight(1f))
            }
        }
    }
}

private data class SeriesColors(
    val displayed: Color, val coulomb: Color, val voltage: Color, val counter: Color, val drop: Color, val gap: Color, val grid: Color,
)

@Composable
private fun LevelChart(data: Analysed, windowMs: Long, colors: SeriesColors) {
    val endMs = System.currentTimeMillis()
    val startMs = endMs - windowMs
    val indices = data.samples.indices.filter { data.samples[it].timestampMs >= startMs }

    Canvas(Modifier.fillMaxWidth().height(240.dp)) {
        fun x(t: Long) = ((t - startMs).toFloat() / windowMs) * size.width
        fun y(level: Double) = size.height * (1f - (level / 100.0).toFloat().coerceIn(0f, 1.2f))

        data.gaps.forEach { gap ->
            val left = x(maxOf(gap.first, startMs))
            val right = x(minOf(gap.last, endMs))
            if (right > left) drawRect(colors.gap, Offset(left, 0f), Size(right - left, size.height))
        }
        listOf(0.0, 25.0, 50.0, 75.0, 100.0).forEach {
            drawLine(colors.grid, Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1f)
        }

        fun series(value: (Int) -> Double?, color: Color, dashed: Boolean = false) {
            val path = Path()
            var penDown = false
            var prevT = 0L
            indices.forEach { i ->
                val v = value(i)
                val t = data.samples[i].timestampMs
                if (v == null || t - prevT > Thresholds.MAX_LEVEL_STEP_MS) penDown = false
                if (v != null) {
                    if (penDown) path.lineTo(x(t), y(v)) else path.moveTo(x(t), y(v))
                    penDown = true
                }
                prevT = t
            }
            drawPath(
                path, color,
                style = Stroke(width = 4f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null),
            )
        }
        series({ chargeDerivedLevel(data.samples[it], data.learnedFullUah) }, colors.counter, dashed = true)
        series({ data.coulombLevels[it] }, colors.coulomb)
        series({ data.voltageLevels[it]?.level }, colors.voltage)
        series({ data.samples[it].displayedLevel.toDouble() }, colors.displayed)

        data.drops.filter { it.start.timestampMs >= startMs }.forEach {
            val dx = x(it.start.timestampMs)
            drawLine(colors.drop, Offset(dx, 0f), Offset(dx, size.height), strokeWidth = 3f)
        }
    }
}

@Composable
private fun Legend(colors: SeriesColors) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem(colors.displayed, stringResource(R.string.displayed_level))
        LegendItem(colors.voltage, stringResource(R.string.estimated_real_level))
        LegendItem(colors.coulomb, stringResource(R.string.current_counted))
        LegendItem(colors.counter, stringResource(R.string.legend_charge_counter))
        LegendItem(colors.drop, stringResource(R.string.legend_drop))
        LegendItem(colors.gap, stringResource(R.string.legend_gap))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 4.dp).size(12.dp).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
