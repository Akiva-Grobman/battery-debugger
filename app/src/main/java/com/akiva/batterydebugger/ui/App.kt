package com.akiva.batterydebugger.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.akiva.batterydebugger.AppMode
import com.akiva.batterydebugger.R
import com.akiva.batterydebugger.data.BatteryDatabase
import com.akiva.batterydebugger.data.Exporter
import com.akiva.batterydebugger.data.Investigation
import com.akiva.batterydebugger.data.Marker
import com.akiva.batterydebugger.data.MarkerType
import com.akiva.batterydebugger.data.OpenedExport
import com.akiva.batterydebugger.data.Sample
import com.akiva.batterydebugger.domain.DEFAULT_DESIGN_CAPACITY_UAH
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

/** Everything the Now/Latest and History screens derive from a set of records. */
data class Analysed(
    val samples: List<Sample>,
    val markers: List<Marker>,
    val designCapacityUah: Long,
    /** The moment the data runs up to: now when recording, the last record when investigating. */
    val endMs: Long,
    val coulombLevels: List<Double?>,
    val voltageLevels: List<VoltageEstimate?>,
    val learnedFullUah: Long?,
    val drops: List<SuddenDrop>,
    val gaps: List<LongRange>,
    val daily: List<DailyDrain>,
)

@Composable
fun App() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AppMode.get(context)) }
    when (mode) {
        null -> ModeChooser { chosen ->
            AppMode.choose(context, chosen)
            mode = chosen
        }
        AppMode.RECORDING -> RecordingApp()
        AppMode.INVESTIGATION -> InvestigationApp()
    }
}

@Composable
private fun ModeChooser(onChoose: (AppMode) -> Unit) {
    Column(
        Modifier.safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.mode_once))
        ModeOption(R.string.mode_recording, R.string.mode_recording_detail) { onChoose(AppMode.RECORDING) }
        ModeOption(R.string.mode_investigation, R.string.mode_investigation_detail) { onChoose(AppMode.INVESTIGATION) }
    }
}

@Composable
private fun ModeOption(title: Int, detail: Int, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(detail))
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
        }
    }
}

@Composable
private fun RecordingApp() {
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
        value = withContext(Dispatchers.Default) {
            analyse(samples, markers, DEFAULT_DESIGN_CAPACITY_UAH, System.currentTimeMillis())
        }
    }

    TabbedScaffold(
        analysed = analysed,
        firstTab = R.string.tab_now,
        actions = {
            TextButton(onClick = {
                scope.launch {
                    if (recording) Recorder.stop(context) else Recorder.start(context)
                    recording = !recording
                }
            }) { Text(stringResource(if (recording) R.string.action_stop else R.string.action_start)) }
            TextButton(onClick = {
                scope.launch { context.startActivity(withContext(Dispatchers.IO) { Exporter.shareIntent(context) }) }
            }) { Text(stringResource(R.string.action_export)) }
        },
        banner = { data ->
            if (!recording) {
                val stoppedAt = data.markers.lastOrNull { it.type == MarkerType.RECORDING_STOPPED }?.timestampMs
                Banner(stringResource(R.string.stopped_since, stoppedAt?.let(::formatTime) ?: "—"), warning = true)
            }
        },
    )
}

@Composable
private fun InvestigationApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<OpenedExport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        opened = withContext(Dispatchers.IO) { Investigation.load(context) }
        loading = false
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) { runCatching { Investigation.open(context, uri) } }
                .onSuccess { opened = it; error = null }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            loading = false
        }
    }
    val openExport = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }

    val export = opened
    val analysed by produceState<Analysed?>(null, export) {
        value = export?.let {
            withContext(Dispatchers.Default) {
                val c = it.contents
                val end = maxOf(c.samples.lastOrNull()?.timestampMs ?: 0, c.markers.lastOrNull()?.timestampMs ?: 0)
                analyse(c.samples, c.markers, c.device?.designCapacityUah ?: DEFAULT_DESIGN_CAPACITY_UAH, end)
            }
        }
    }

    if (export == null) {
        Column(
            Modifier.safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.mode_investigation), style = MaterialTheme.typography.headlineMedium)
            if (loading) {
                Text(stringResource(R.string.loading))
            } else {
                Text(stringResource(R.string.investigate_empty))
                error?.let { Banner(stringResource(R.string.open_failed, it), warning = true) }
                Button(onClick = openExport, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_open_export))
                }
            }
        }
        return
    }

    TabbedScaffold(
        analysed = analysed,
        firstTab = R.string.tab_latest,
        actions = { TextButton(onClick = openExport) { Text(stringResource(R.string.action_open_export)) } },
        banner = {
            error?.let { Banner(stringResource(R.string.open_failed, it), warning = true) }
            val device = export.contents.device
            val lines = buildList {
                add(stringResource(R.string.export_file, export.fileName))
                if (device != null) {
                    add(
                        stringResource(
                            R.string.export_device, device.model, device.androidVersion, formatTime(device.exportedAtMs),
                        ),
                    )
                    add(stringResource(R.string.design_capacity, (device.designCapacityUah / 1000).toInt()))
                } else {
                    add(stringResource(R.string.capacity_assumed, (DEFAULT_DESIGN_CAPACITY_UAH / 1000).toInt()))
                }
            }
            Banner(lines.joinToString("\n"))
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabbedScaffold(
    analysed: Analysed?,
    firstTab: Int,
    actions: @Composable () -> Unit,
    banner: @Composable (Analysed) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = { actions() })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(firstTab)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.tab_history)) })
            }
            when {
                analysed == null -> Text(stringResource(R.string.loading), Modifier.padding(16.dp))
                tab == 0 -> NowScreen(analysed) { banner(analysed) }
                else -> HistoryScreen(analysed)
            }
        }
    }
}

private fun hasEverRecorded(context: Context) =
    context.getSharedPreferences("recorder", Context.MODE_PRIVATE).contains("recording")

private fun analyse(samples: List<Sample>, markers: List<Marker>, designCapacityUah: Long, endMs: Long): Analysed =
    Analysed(
        samples = samples,
        markers = markers,
        designCapacityUah = designCapacityUah,
        endMs = endMs,
        coulombLevels = coulombCountedLevels(samples, markers, endMs, designCapacityUah),
        voltageLevels = voltageLevels(samples),
        learnedFullUah = learnedFullChargeUah(samples),
        drops = suddenDrops(samples, markers, endMs),
        gaps = recordingGaps(markers, endMs),
        daily = dailyDrain(samples, markers, endMs, ZoneId.systemDefault()),
    )
