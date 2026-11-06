package com.akiva.batterydebugger.ui

import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.akiva.batterydebugger.R
import com.akiva.batterydebugger.domain.Thresholds
import com.akiva.batterydebugger.domain.chargeDerivedLevel
import com.akiva.batterydebugger.domain.dischargeUa
import com.akiva.batterydebugger.domain.learnedToDesignRatio
import com.akiva.batterydebugger.domain.timeLeftAtCurrentDrawMs

@Composable
fun NowScreen(data: Analysed, banner: @Composable () -> Unit = {}) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        banner()

        val latest = data.samples.lastOrNull()
        if (latest == null) {
            Text(stringResource(R.string.no_samples))
            return@Column
        }
        val coulomb = data.coulombLevels.last()
        val chargeDerived = chargeDerivedLevel(latest, data.learnedFullUah)

        val voltage = data.voltageLevels.last()
        Section(stringResource(R.string.estimated_real_level)) {
            Text(
                voltage?.let { formatPercent(it.level) } ?: stringResource(R.string.waiting),
                style = if (voltage != null) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (voltage != null) {
                Text(
                    stringResource(R.string.voltage_detail, formatTime(voltage.fromMs)) +
                        if (voltage.fromMs != latest.timestampMs) " " + stringResource(R.string.held) else "",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val draw = dischargeUa(latest)
            val waitingReason = when {
                latest.plugged != 0 ->
                    stringResource(R.string.waiting_plugged)
                draw != null && draw > Thresholds.VOLTAGE_MAX_DRAW_UA ->
                    stringResource(R.string.waiting_busy, (draw / 1000).toInt(), (Thresholds.VOLTAGE_MAX_DRAW_UA / 1000).toInt())
                else -> null
            }
            waitingReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Section(stringResource(R.string.displayed_level)) {
            Text("${latest.displayedLevel}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(formatTime(latest.timestampMs), style = MaterialTheme.typography.bodySmall)
            Field(stringResource(R.string.from_voltage), formatPercent(voltage?.level), divergence(latest.displayedLevel, voltage?.level))
            Field(stringResource(R.string.current_counted), formatPercent(coulomb), divergence(latest.displayedLevel, coulomb))
            Field(stringResource(R.string.charge_counter), formatPercent(chargeDerived), divergence(latest.displayedLevel, chargeDerived))
        }

        Section(stringResource(R.string.section_battery)) {
            Field(stringResource(R.string.voltage), "${latest.voltageMv} mV")
            val draw = dischargeUa(latest)
            Field(
                if (draw != null && draw < 0) stringResource(R.string.charging_current) else stringResource(R.string.draw),
                draw?.let { "${kotlin.math.abs(it) / 1000} mA" } ?: "—",
            )
            Field(stringResource(R.string.temperature), "%.1f °C".format(latest.temperatureDeciC / 10.0))
            Field(stringResource(R.string.state), chargingState(latest.status, latest.plugged))
        }

        Section(stringResource(R.string.section_time_left)) {
            Field(stringResource(R.string.system_predicts), formatDuration(latest.dischargePredictionMs))
            Field(stringResource(R.string.at_current_draw), formatDuration(timeLeftAtCurrentDrawMs(latest, coulomb, data.designCapacityUah)))
        }

        Section(stringResource(R.string.section_health)) {
            Field(stringResource(R.string.cycle_count), latest.cycleCount?.toString() ?: "—")
            Field(stringResource(R.string.state_of_health), latest.stateOfHealth?.let { "$it%" } ?: "—")
            Field(stringResource(R.string.learned_vs_design), learnedToDesignRatio(data.samples, data.designCapacityUah)?.let { "%.0f%%".format(it * 100) } ?: "—")
        }
    }
}

/** A full-width notice above the Now/Latest cards. */
@Composable
fun Banner(text: String, warning: Boolean = false) {
    val colors = if (warning) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    Card(colors = colors) {
        Text(text, Modifier.padding(16.dp).fillMaxWidth())
    }
}

@Composable
private fun divergence(displayed: Int, estimate: Double?): String? =
    estimate?.let { stringResource(R.string.divergence, formatSigned(displayed - it)) }

@Composable
private fun chargingState(status: Int, plugged: Int): String = stringResource(
    when {
        status == BatteryManager.BATTERY_STATUS_FULL -> R.string.state_full
        status == BatteryManager.BATTERY_STATUS_CHARGING -> R.string.state_charging
        plugged != 0 -> R.string.state_plugged_not_charging
        else -> R.string.state_on_battery
    },
)

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String, note: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(listOfNotNull(value, note).joinToString("  ·  "), style = MaterialTheme.typography.bodyMedium)
    }
}
