package com.akiva.batterydebugger.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.akiva.batterydebugger.R
import com.akiva.batterydebugger.data.BatteryDatabase
import com.akiva.batterydebugger.data.Exporter
import com.akiva.batterydebugger.data.Marker
import com.akiva.batterydebugger.data.Sample
import com.akiva.batterydebugger.domain.DailyDrain
import com.akiva.batterydebugger.domain.SuddenDrop
import com.akiva.batterydebugger.domain.Thresholds
import com.akiva.batterydebugger.domain.VoltageEstimate
import com.akiva.batterydebugger.domain.coulombCountedLevels
import com.akiva.batterydebugger.domain.dailyDrain
import com.akiva.batterydebugger.domain.learnedFullChargeUah
import com.akiva.batterydebugger.domain.recordingGaps
import com.akiva.batterydebugger.domain.suddenDrops
import com.akiva.batterydebugger.domain.voltageLevels
import com.akiva.batterydebugger.recording.Recorder
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the Now and History screens derive from the stored records. */
data class Analysed(
    val samples: List<Sample>,
    val markers: List<Marker>,
    val coulombLevels: List<Double?>,
    val voltageLevels: List<VoltageEstimate?>,
    val learnedFullUah: Long?,
    val drops: List<SuddenDrop>,
    val gaps: List<LongRange>,
    val daily: List<DailyDrain>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var setup by remember { mutableStateOf(Recorder.setupStatus(context)) }
    var recording by remember { mutableStateOf(Recorder.isRecording(context)) }
    LifecycleResumeEffect(Unit) {
        setup = Recorder.setupStatus(context)
        recording = Recorder.isRecording(context)
        Recorder.ensureRunning(context)
        onPauseOrDispose { }
    }

    // Setup is shown until recording has been started once.
    if (!hasEverRecorded(context)) {
        SetupScreen(setup, onRefresh = { setup = Recorder.setupStatus(context) }, onStart = {
            scope.launch { Recorder.start(context); recording = true }
        })
        return
    }

    val sinceMs = remember { System.currentTimeMillis() - Thresholds.RETENTION_MS }
    val dao = remember { BatteryDatabase.get(context).records() }
    val samples by dao.samplesSince(sinceMs).collectAsState(initial = emptyList())
    val markers by dao.markersSince(sinceMs).collectAsState(initial = emptyList())
    val analysed by produceState<Analysed?>(null, samples, markers) {
        value = withContext(Dispatchers.Default) { analyse(samples, markers) }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            if (recording) Recorder.stop(context) else Recorder.start(context)
                            recording = !recording
                        }
                    }) { Text(if (recording) stringResource(R.string.action_stop) else stringResource(R.string.action_start)) }
                    TextButton(onClick = {
                        scope.launch { context.startActivity(withContext(Dispatchers.IO) { Exporter.shareIntent(context) }) }
                    }) { Text(stringResource(R.string.action_export)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.tab_now)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_history)) })
            }
            val data = analysed
            when {
                data == null -> Text(stringResource(R.string.loading), Modifier.padding(16.dp))
                tab == 0 -> NowScreen(data, recording)
                else -> HistoryScreen(data)
            }
        }
    }
}

private fun hasEverRecorded(context: Context) =
    context.getSharedPreferences("recorder", Context.MODE_PRIVATE).contains("recording")

private fun analyse(samples: List<Sample>, markers: List<Marker>): Analysed {
    val now = System.currentTimeMillis()
    return Analysed(
        samples = samples,
        markers = markers,
        coulombLevels = coulombCountedLevels(samples, markers, now),
        voltageLevels = voltageLevels(samples),
        learnedFullUah = learnedFullChargeUah(samples),
        drops = suddenDrops(samples, markers, now),
        gaps = recordingGaps(markers, now),
        daily = dailyDrain(samples, markers, now, ZoneId.systemDefault()),
    )
}
