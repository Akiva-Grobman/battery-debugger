package com.akiva.batterydebugger.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.akiva.batterydebugger.R
import com.akiva.batterydebugger.recording.SetupStatus

@Composable
fun SetupScreen(status: SetupStatus, onRefresh: () -> Unit, onStart: () -> Unit) {
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.setup_intro))

        SetupItem(stringResource(R.string.setup_battery_stats), status.batteryStatsGranted) {
            Text(stringResource(R.string.setup_adb_hint))
            SelectionContainer {
                Text(
                    "adb shell pm grant ${context.packageName} android.permission.BATTERY_STATS",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onRefresh) { Text(stringResource(R.string.setup_check_again)) }
        }
        SetupItem(stringResource(R.string.setup_usage), status.usageAccessGranted) {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }) { Text(stringResource(R.string.setup_open_settings)) }
        }
        SetupItem(stringResource(R.string.setup_battery_opt), status.batteryOptimizationIgnored) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                )
            }) { Text(stringResource(R.string.setup_allow)) }
        }
        SetupItem(stringResource(R.string.setup_notifications), status.notificationsAllowed) {
            OutlinedButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.setup_allow)) }
        }

        Button(onClick = onStart, enabled = status.complete, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.setup_start))
        }
    }
}

@Composable
private fun SetupItem(title: String, done: Boolean, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (done) "✓" else "○", style = MaterialTheme.typography.titleMedium)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            if (!done) action()
        }
    }
}
