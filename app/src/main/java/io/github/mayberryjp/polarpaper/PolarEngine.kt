package io.github.mayberryjp.polarpaper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.PolarBleDisconnectInfo
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarSensorSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PolarApplication : Application() {
    val engine by lazy { PolarEngine(this) }
}

data class LiveState(
    val status: String = "Connect your Polar H10",
    val devices: List<PolarDeviceInfo> = emptyList(),
    val scanning: Boolean = false,
    val deviceId: String? = null,
    val ready: Boolean = false,
    val battery: Int? = null,
    val contact: Boolean? = null,
    val hr: Int? = null,
    val sampleRate: Int = 130,
    val ecg: List<EcgPoint> = emptyList(),
    val signalFresh: Boolean = false,
    val rolling: Metrics = Metrics(),
    val total: Metrics = Metrics(),
    val recording: Boolean = false,
    val busy: Boolean = false,
    val elapsedMs: Long = 0,
    val sessions: List<SessionSummary> = emptyList(),
    val report: SessionSummary? = null,
    val error: String? = null,
)

@android.annotation.SuppressLint("MissingPermission")
class PolarEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(LiveState())
    val state = mutable.asStateFlow()
    val store = SessionStore(context)
    private var api: PolarBleApi? = null
    private var scan: Job? = null
    private var scanTimeout: Job? = null
    private var hrJob: Job? = null
    private var ecgJob: Job? = null
    private var connectionTimeout: Job? = null
    private val sessionLock = Mutex()
    private var sessionId: Long? = null
    private var sessionStart = 0L
    private var accumulator = MetricAccumulator()
    private val rrWindow = ArrayDeque<RrPoint>()
    private val hrWindow = ArrayDeque<HrPoint>()
    private val ecgWindow = ArrayDeque<EcgPoint>()
    private var lastHr = 0L
    private var lastEcg = 0L
    private var hrActive = false
    private var ecgActive = false
    private var pendingRrBreak = true
    private var foregroundVisible = true

    init {
        scope.launch {
            attempt("Unable to open session history") {
                sessionLock.withLock {
                    store.recover()
                    refreshHistory()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(100)
                if (!foregroundVisible && !state.value.recording && !state.value.busy) {
                    if (state.value.deviceId != null || state.value.scanning) clearConnection()
                    delay(900)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                while (rrWindow.isNotEmpty() && rrWindow.first().elapsedMs < now - 60_000) rrWindow.removeFirst()
                while (hrWindow.isNotEmpty() && hrWindow.first().elapsedMs < now - 60_000) hrWindow.removeFirst()
                val fresh = lastEcg > 0 && now - lastEcg < 3000 && lastHr > 0 && now - lastHr < 5000
                mutable.update { current ->
                    current.copy(
                        ecg = ecgWindow.toList(), signalFresh = fresh,
                        hr = current.hr.takeIf { now - lastHr < 5000 && current.contact != false },
                        rolling = windowMetrics(rrWindow, hrWindow, now),
                        total = accumulator.snapshot(),
                        elapsedMs = if (current.recording) now - sessionStart else current.elapsedMs,
                    )
                }
                if (state.value.recording && (now - lastEcg > 10_000 || now - lastHr > 10_000)) {
                    fail("Signal stopped for over 10 seconds. Session interrupted.")
                }
            }
        }
    }

    private fun sdk(): PolarBleApi {
        api?.let { return it }
        return PolarBleApiDefaultImpl.defaultImplementation(context, setOf(
            PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
            PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
        )).also { newApi ->
            api = newApi
            newApi.setAutomaticReconnection(false)
            newApi.setApiCallback(object : PolarBleApiCallback() {
                // Required in SDK 8.5 even though device-info and thermometer features are disabled.
                override fun disInformationReceived(identifier: String, disInfo: DisInfo) {}
                override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) {}

                override fun blePowerStateChanged(powered: Boolean) {
                    if (!powered) scope.launch { fail("Bluetooth is off. Enable it and reconnect.") }
                }

                override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
                    scope.launch { mutable.update { it.copy(status = "Connecting to ${polarDeviceInfo.deviceId}") } }
                }

                override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                    scope.launch {
                        connectionTimeout?.cancel()
                        mutable.update { it.copy(deviceId = polarDeviceInfo.deviceId, status = "Preparing ECG and RR streams") }
                        connectionTimeout = scope.launch {
                            delay(30_000)
                            if (!state.value.ready) fail("Sensor streams did not become ready. Reconnect the H10.")
                        }
                    }
                }

                override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo, info: PolarBleDisconnectInfo) {
                    scope.launch {
                        if (state.value.deviceId == polarDeviceInfo.deviceId) {
                            fail("H10 disconnected: ${info.reason}. Recovery: ${info.recoveryAction}.")
                        }
                    }
                }

                override fun batteryLevelReceived(identifier: String, level: Int) {
                    scope.launch { mutable.update { it.copy(battery = level) } }
                }

                override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                    scope.launch {
                        if (identifier != state.value.deviceId) return@launch
                        when (feature) {
                            PolarBleApi.PolarBleSdkFeature.FEATURE_HR -> startHr(identifier)
                            PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING -> startEcg(identifier)
                            else -> Unit
                        }
                    }
                }
            })
        }
    }

    fun scanDevices() {
        scan?.cancel()
        mutable.update { it.copy(scanning = true, devices = emptyList(), error = null, status = "Searching for H10 sensors") }
        scan = scope.launch {
            attempt("Bluetooth scan failed") {
                sdk().searchForDevice().collect { device ->
                    if (device.name.contains("H10", ignoreCase = true)) mutable.update { current ->
                        current.copy(devices = (current.devices.filterNot { it.deviceId == device.deviceId } + device).sortedByDescending { it.rssi })
                    }
                }
            }
            mutable.update { it.copy(scanning = false) }
        }
        scanTimeout?.cancel()
        scanTimeout = scope.launch {
            delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        scan?.cancel()
        scan = null
        mutable.update { it.copy(scanning = false) }
    }

    fun connect(id: String) {
        if (state.value.deviceId != null || state.value.busy) return
        stopScan()
        mutable.update { it.copy(deviceId = id, status = "Connecting to $id", error = null, report = null) }
        scope.launch {
            attempt("Connection failed") { sdk().connectToDevice(id) }
            if (state.value.error != null) {
                mutable.update { it.copy(deviceId = null) }
            } else {
                connectionTimeout = scope.launch {
                    delay(30_000)
                    if (!state.value.ready) fail("Connection timed out. Wet the strap electrodes and reconnect.")
                }
            }
        }
    }

    private fun startHr(id: String) {
        if (hrJob?.isActive == true) return
        hrJob = scope.launch {
            try {
                sdk().startHrStreaming(id).collect { packet ->
                    if (packet.samples.isEmpty()) return@collect
                    val now = SystemClock.elapsedRealtime()
                    val gap = lastHr == 0L || now - lastHr > 5000
                    lastHr = now
                    hrActive = true
                    updateReady()
                    sessionLock.withLock {
                        val hrs = mutableListOf<HrPoint>()
                        val rrs = mutableListOf<RrPoint>()
                        if (gap) pendingRrBreak = true
                        packet.samples.forEach { sample ->
                            val contact = !sample.contactStatusSupported || sample.contactStatus
                            val hp = HrPoint(now, sample.hr, contact)
                            hrs += hp
                            hrWindow += hp
                            mutable.update { it.copy(contact = contact, hr = sample.hr.takeIf { contact && it in 1..240 }) }
                            if (!sample.rrAvailable || sample.rrsMs.isEmpty()) {
                                pendingRrBreak = true
                            } else {
                                var beatTime = now - sample.rrsMs.sumOf { it.toLong() }
                                sample.rrsMs.forEach { rr ->
                                    beatTime += rr
                                    val rp = RrPoint(beatTime, rr, contact, pendingRrBreak)
                                    pendingRrBreak = false
                                    rrWindow += rp
                                    rrs += rp
                                }
                            }
                        }
                        sessionId?.let { session ->
                            store.saveHr(
                                session, now - sessionStart,
                                hrs.map { it.copy(elapsedMs = (it.elapsedMs - sessionStart).coerceAtLeast(0)) },
                                rrs.map { it.copy(elapsedMs = (it.elapsedMs - sessionStart).coerceAtLeast(0)) },
                            )
                            hrs.forEach { accumulator.addHr(it.bpm, it.contact) }
                            rrs.forEach { accumulator.addRr(it.rrMs, it.contact, it.breakBefore) }
                        }
                    }
                }
                fail("Heart rate stream ended unexpectedly.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("HR/RR capture failed: ${e.message}", e) }
        }
    }

    private fun startEcg(id: String) {
        if (ecgJob?.isActive == true) return
        ecgJob = scope.launch {
            try {
                val settings = sdk().requestStreamSettings(id, PolarBleApi.PolarDeviceDataType.ECG).maxSettings()
                val rate = settings.settings[PolarSensorSetting.SettingType.SAMPLE_RATE]?.single()
                    ?: error("H10 did not report an ECG sample rate")
                mutable.update { it.copy(sampleRate = rate) }
                sdk().startEcgStreaming(id, settings).collect { data ->
                    val samples = data.samples.map {
                        val sample = it as? EcgSample ?: error("Unsupported ECG sample type")
                        EcgPoint(sample.timeStamp, sample.voltage)
                    }
                    if (samples.isNotEmpty()) {
                        lastEcg = SystemClock.elapsedRealtime()
                        ecgActive = true
                        updateReady()
                        samples.forEach { ecgWindow += it }
                        val cutoff = samples.last().sensorNs - 8_000_000_000L
                        while (ecgWindow.isNotEmpty() && ecgWindow.first().sensorNs < cutoff) ecgWindow.removeFirst()
                        sessionLock.withLock {
                            sessionId?.let { store.saveEcg(it, SystemClock.elapsedRealtime() - sessionStart, samples) }
                        }
                    }
                }
                fail("ECG stream ended unexpectedly.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("ECG capture failed: ${e.message}", e) }
        }
    }

    private fun updateReady() {
        mutable.update { it.copy(ready = hrActive && ecgActive, status = if (hrActive && ecgActive) "H10 connected - live ECG" else it.status) }
    }

    suspend fun beginSession(): Boolean {
        mutable.update { it.copy(busy = true, error = null) }
        var started = false
        attempt("Unable to start recording") {
            sessionLock.withLock {
                check(state.value.ready && state.value.signalFresh && state.value.contact != false) { "Wait for fresh ECG and skin contact before recording" }
                check(sessionId == null) { "A session is already recording" }
                sessionId = store.begin(state.value.deviceId ?: error("No H10 connected"), state.value.sampleRate)
                sessionStart = SystemClock.elapsedRealtime()
                accumulator = MetricAccumulator()
                pendingRrBreak = true
                mutable.update { it.copy(recording = true, elapsedMs = 0, total = Metrics(), report = null) }
                started = true
            }
        }
        mutable.update { it.copy(busy = false) }
        return started
    }

    fun requestSession() {
        if (state.value.busy || state.value.recording) return
        mutable.update { it.copy(busy = true) }
        try {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        } catch (e: Exception) {
            mutable.update { it.copy(busy = false) }
            showError("Unable to start recording service: ${e.message}", e)
        }
    }

    fun setForegroundVisible(visible: Boolean) { foregroundVisible = visible }

    fun finishSession() { scope.launch { endSession("Completed") } }

    private suspend fun endSession(status: String) {
        mutable.update { it.copy(busy = true) }
        attempt("Unable to finalize session; saved samples remain in history after restart") {
            sessionLock.withLock {
                sessionId?.let {
                    val duration = SystemClock.elapsedRealtime() - sessionStart
                    store.finish(it, duration, status)
                    sessionId = null
                    mutable.update { current -> current.copy(recording = false, elapsedMs = duration) }
                    refreshHistory()
                    mutable.update { current -> current.copy(report = current.sessions.firstOrNull { s -> s.id == it }) }
                }
            }
        }
        // A storage error must stop capture; the journal recovers unfinished sessions next launch.
        sessionId = null
        mutable.update { it.copy(recording = false, busy = false) }
        if (!foregroundVisible) clearConnection()
        context.stopService(Intent(context, RecordingService::class.java))
    }

    fun disconnect() {
        scope.launch {
            endSession("Interrupted: disconnected by user")
            clearConnection()
        }
    }

    private suspend fun fail(message: String, cause: Exception? = null) {
        Log.e("PolarPaper", message, cause)
        mutable.update { it.copy(error = message, ready = false, status = "Disconnected - reconnect to retry") }
        if (state.value.recording) endSession("Interrupted: $message")
        clearConnection()
    }

    private fun clearConnection() {
        connectionTimeout?.cancel()
        hrJob?.cancel()
        ecgJob?.cancel()
        hrJob = null
        ecgJob = null
        stopScan()
        val id = state.value.deviceId
        mutable.update { it.copy(deviceId = null, ready = false, hr = null, contact = null, battery = null, status = "Connect your Polar H10") }
        hrActive = false
        ecgActive = false
        lastHr = 0
        lastEcg = 0
        pendingRrBreak = true
        rrWindow.clear()
        hrWindow.clear()
        ecgWindow.clear()
        if (id != null) {
            try { api?.disconnectFromDevice(id) }
            catch (e: Exception) {
                Log.e("PolarPaper", "Disconnect failed", e)
                mutable.update { it.copy(error = "Bluetooth disconnect failed: ${e.message}") }
            }
        }
    }

    fun showReport(report: SessionSummary?) { mutable.update { it.copy(report = report) } }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun showError(message: String, e: Exception? = null) {
        Log.e("PolarPaper", message, e)
        mutable.update { it.copy(error = message) }
    }
    fun deleteSession(id: Long) {
        scope.launch {
            attempt("Unable to delete session") {
                store.delete(id)
                refreshHistory()
                mutable.update { it.copy(report = null) }
                val cached = java.io.File(java.io.File(context.cacheDir, "reports"), "polar-session-$id.zip")
                check(!cached.exists() || cached.delete()) { "Session deleted, but cached export could not be removed" }
            }
        }
    }
    private suspend fun refreshHistory() {
        val sessions = store.listSessions()
        mutable.update { it.copy(sessions = sessions) }
    }
    private suspend fun attempt(message: String, block: suspend () -> Unit) {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { showError("$message: ${e.message}", e) }
    }
}
