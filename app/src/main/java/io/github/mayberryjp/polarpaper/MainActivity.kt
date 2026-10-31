package io.github.mayberryjp.polarpaper

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val engine get() = (application as PolarApplication).engine
    private var pendingAction: (() -> Unit)? = null
    private var pendingExport: File? = null
    private val bluetoothEnable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true) {
            pendingAction?.invoke()
        } else engine.showError("Bluetooth must be enabled to connect to an H10.")
        pendingAction = null
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (requiredPermissions().all { permission -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }) {
            checkBluetooth()
        } else {
            pendingAction = null
            engine.showError("Bluetooth permissions are required. Grant Nearby devices (or Location on Android 11 and earlier) in app settings.")
        }
    }
    private val saveExport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingExport
        pendingExport = null
        if (uri != null && file != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(uri) ?: error("Unable to open destination")
                    output.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                android.widget.Toast.makeText(this@MainActivity, "Report saved", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { engine.showError("Report save failed: ${e.message}", e) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF006C67), secondary = Color(0xFF567571),
                background = Color(0xFFF5FAF8), surface = Color.White,
            )) {
                val state by engine.state.collectAsStateWithLifecycle()
                PolarScreen(state, engine,
                    onScan = { withBluetooth { engine.scanDevices() } },
                    onConnect = { id -> withBluetooth { engine.connect(id) } },
                    onRecord = {
                        withBluetooth {
                            try {
                                ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java))
                            } catch (e: Exception) { engine.showError("Unable to start recording service: ${e.message}", e) }
                        }
                    },
                    onExport = ::export,
                )
            }
        }
    }

    private fun requiredPermissions() = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun withBluetooth(action: () -> Unit) {
        pendingAction = action
        val request = requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) request += Manifest.permission.POST_NOTIFICATIONS
        if (request.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            permissions.launch(request.toTypedArray())
        } else checkBluetooth()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun checkBluetooth() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        when {
            adapter == null -> {
                pendingAction = null
                engine.showError("This phone does not support Bluetooth LE.")
            }
            !adapter.isEnabled -> bluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            Build.VERSION.SDK_INT <= 30 && !getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER) -> {
                pendingAction = null
                engine.showError("Enable Location services for BLE scanning on Android 11 or earlier. Location is not stored.")
            }
            else -> {
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            }
        }
    }

    private fun export(summary: SessionSummary, share: Boolean) {
        lifecycleScope.launch {
            try {
                val file = engine.store.export(summary, File(cacheDir, "reports"))
                if (share) {
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.reports", file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("Session report", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share ECG session"))
                } else {
                    pendingExport = file
                    saveExport.launch(file.name)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { engine.showError("Report export failed: ${e.message}", e) }
        }
    }
}

@Composable
fun PolarScreen(
    state: LiveState, engine: PolarEngine, onScan: () -> Unit, onConnect: (String) -> Unit,
    onRecord: () -> Unit, onExport: (SessionSummary, Boolean) -> Unit,
) {
    var deviceId by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var exportChoice by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("POLAR PAPER", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text("Single-lead ECG & RR-derived HRV", style = MaterialTheme.typography.titleMedium)
            Text("Wellness / research only. Not a medical diagnostic device.", style = MaterialTheme.typography.bodySmall)
            state.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(it)
                        TextButton(onClick = engine::clearError) { Text("Dismiss") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0; engine.showReport(null) }, label = { Text("Live ECG") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Sessions (${state.sessions.size})") })
            }
            if (state.report != null) {
                val report = state.report
                Text("Session report", style = MaterialTheme.typography.headlineSmall)
                Text("${dateText(report.startedMs)}  |  ${durationText(report.durationMs)}")
                Text("${report.status}  |  ${report.ecgSamples} ECG samples")
                MetricCards(report.metrics, "Entire session")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportChoice = false }) { Text("Save ZIP") }
                    OutlinedButton(onClick = { exportChoice = true }) { Text("Share ZIP") }
                }
                Row {
                    TextButton(onClick = { engine.showReport(null); tab = 1 }) { Text("Back to sessions") }
                    TextButton(onClick = { confirmDelete = true }) { Text("Delete session") }
                }
                Text(reportText(report), style = MaterialTheme.typography.bodySmall)
            } else if (tab == 1) {
                Text("Stored only on this phone. Uninstalling deletes sessions. Export important recordings.", style = MaterialTheme.typography.bodySmall)
                if (state.sessions.isEmpty()) Text("No completed sessions yet.")
                state.sessions.forEach { session ->
                    OutlinedCard(onClick = { engine.showReport(session) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(dateText(session.startedMs), style = MaterialTheme.typography.titleMedium)
                            Text("${durationText(session.durationMs)}  |  ${session.status}")
                            Text("HR ${session.metrics.meanHr.display()} bpm  |  RMSSD ${session.metrics.rmssdMs.display()} ms")
                        }
                    }
                }
            } else {
                Text(state.status, style = MaterialTheme.typography.titleMedium)
                if (state.deviceId == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = if (state.scanning) engine::stopScan else onScan) {
                            Text(if (state.scanning) "Stop scan" else "Find H10")
                        }
                    }
                    Text("Wear the strap with wet electrodes. Close other apps using the H10. Scan lasts 15 seconds.", style = MaterialTheme.typography.bodySmall)
                    state.devices.forEach { device ->
                        OutlinedButton(onClick = { onConnect(device.deviceId) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${device.name}  |  ${device.rssi} dBm")
                        }
                    }
                    OutlinedTextField(deviceId, onValueChange = { deviceId = it.uppercase().trim() },
                        label = { Text("Or enter printed Polar device ID") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { onConnect(deviceId) }, enabled = deviceId.matches(Regex("[0-9A-F]{6,8}"))) {
                        Text("Connect by ID")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("ID ${state.deviceId}")
                        Text("Battery ${state.battery?.let { "$it%" } ?: "--"}")
                        TextButton(onClick = engine::disconnect, enabled = !state.busy) { Text("Disconnect") }
                    }
                }
                Text("${state.hr ?: "--"} bpm", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
                Text(when {
                    state.contact == false -> "Poor skin contact - adjust/wet the strap"
                    !state.signalFresh -> "Waiting for fresh ECG and heart rate"
                    else -> "Signal receiving  |  ${state.sampleRate} Hz"
                })
                EcgPaper(if (state.signalFresh) state.ecg else emptyList())
                MetricCards(state.rolling, "Live trailing 60 seconds")
                Text("RMSSD = HRV; SDNN = sample RR standard deviation; pNN50 = adjacent RR changes >50 ms. Bounds-screened RR estimates, not validated NN. Aim for 5 minutes at rest.", style = MaterialTheme.typography.bodySmall)
                if (state.recording) {
                    Text("RECORDING  ${durationText(state.elapsedMs)}", style = MaterialTheme.typography.titleLarge)
                    Button(onClick = engine::finishSession, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Finish & save report")
                    }
                    MetricCards(state.total, "Session so far")
                    Text("Recording continues with screen off. Use the notification to stop.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = onRecord,
                        enabled = state.ready && state.signalFresh && state.contact != false && !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "Saving..." else "Start session") }
                }
            }
        }
    }
    if (exportChoice != null && state.report != null) {
        AlertDialog(onDismissRequest = { exportChoice = null }, title = { Text("Export health data?") },
            text = { Text("The ZIP contains ECG, RR intervals, heart rate, device ID, and a report. It is not encrypted. Share only with trusted recipients.") },
            confirmButton = { TextButton(onClick = { onExport(state.report, exportChoice == true); exportChoice = null }) { Text("Export") } },
            dismissButton = { TextButton(onClick = { exportChoice = null }) { Text("Cancel") } })
    }
    if (confirmDelete && state.report != null) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Permanently delete this session?") },
            text = { Text("All saved ECG and metrics for this session will be removed from this phone.") },
            confirmButton = { TextButton(onClick = { engine.deleteSession(state.report.id); confirmDelete = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

@Composable
fun MetricCards(metrics: Metrics, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val values = listOf(
            "HRV RMSSD" to "${metrics.rmssdMs.display()} ms",
            "SDNN" to "${metrics.sdnnMs.display()} ms",
            "pNN50" to "${metrics.pnn50Percent.display()} %",
            "Min HR" to "${metrics.minHr ?: "--"} bpm",
            "Max HR" to "${metrics.maxHr ?: "--"} bpm",
            "Mean HR" to "${metrics.meanHr.display()} bpm",
        )
        values.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) ->
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(label, style = MaterialTheme.typography.labelLarge)
                            Text(value, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
        Text("RR accepted: ${metrics.rrCount}  |  rejected: ${metrics.rejectedRr}  |  pairs: ${metrics.pairCount}",
            style = MaterialTheme.typography.bodySmall)
    }
}

fun dateText(ms: Long): String = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
