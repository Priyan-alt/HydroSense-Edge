package com.hydrosense.edge.data.repository

import com.hydrosense.edge.data.ble.BleConnectionState
import com.hydrosense.edge.data.ble.BleManager
import com.hydrosense.edge.data.ble.ParseResult
import com.hydrosense.edge.data.db.HydrationDao
import com.hydrosense.edge.data.db.HydrationRecordEntity
import com.hydrosense.edge.data.model.BleLogEntry
import com.hydrosense.edge.data.model.CalibrationInfo
import com.hydrosense.edge.data.model.DiscoveredBleDevice
import com.hydrosense.edge.data.model.HydrationReading
import com.hydrosense.edge.data.model.HydrationStatus
import com.hydrosense.edge.data.model.MeasurementUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HydrationRepository(
    private val bleManager: BleManager,
    private val hydrationDao: HydrationDao,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    // Current sensor telemetry (Null until real packet received - No Fake Data!)
    private val _latestReading = MutableStateFlow<HydrationReading?>(null)
    val latestReading: StateFlow<HydrationReading?> = _latestReading.asStateFlow()

    // Recent readings buffer for live graphs (up to 50 points)
    private val _liveTelemetryHistory = MutableStateFlow<List<HydrationReading>>(emptyList())
    val liveTelemetryHistory: StateFlow<List<HydrationReading>> = _liveTelemetryHistory.asStateFlow()

    // Calibration state
    private val _calibrationInfo = MutableStateFlow(CalibrationInfo())
    val calibrationInfo: StateFlow<CalibrationInfo> = _calibrationInfo.asStateFlow()

    // On-demand "Measure Now" state machine
    private val _measurementUiState = MutableStateFlow<MeasurementUiState>(MeasurementUiState.Idle)
    val measurementUiState: StateFlow<MeasurementUiState> = _measurementUiState.asStateFlow()

    private var activeMeasurementJob: Job? = null
    private var lastRequestTimestamp: Long = 0L

    // Bluetooth status
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = bleManager.discoveredDevices
    val connectedDevice: StateFlow<DiscoveredBleDevice?> = bleManager.connectedDevice
    val logEntries: SharedFlow<BleLogEntry> = bleManager.logEntries
    val errorMessage: StateFlow<String?> = bleManager.errorMessage

    init {
        // Collect incoming BLE parse results
        externalScope.launch {
            bleManager.parseResults.collect { result ->
                when (result) {
                    is ParseResult.MeasureStart -> {
                        if (_measurementUiState.value is MeasurementUiState.Measuring) {
                            _measurementUiState.value = MeasurementUiState.Measuring(
                                percent = 0,
                                secondsRemaining = result.duration,
                                statusMessage = "Capturing 15s PPG window..."
                            )
                        }
                    }

                    is ParseResult.MeasureProgress -> {
                        if (_measurementUiState.value is MeasurementUiState.Measuring) {
                            val msg = when {
                                result.status.contains("HOLD", ignoreCase = true) -> "Hold still: wrist tilt detected"
                                result.status.contains("WEAR", ignoreCase = true) -> "Wear band snugly on wrist"
                                result.status.contains("STABIL", ignoreCase = true) -> "Stabilizing PPG sensor..."
                                else -> "Sampling optical PPG waveform..."
                            }
                            _measurementUiState.value = MeasurementUiState.Measuring(
                                percent = result.percent,
                                secondsRemaining = result.secondsRemaining,
                                statusMessage = msg
                            )
                        }
                    }

                    is ParseResult.MeasureError -> {
                        activeMeasurementJob?.cancel()
                        _measurementUiState.value = MeasurementUiState.Failed(result.reason)
                    }

                    is ParseResult.Reading -> {
                        val reading = result.reading
                        _latestReading.value = reading

                        // Update live graph buffer
                        val list = _liveTelemetryHistory.value.toMutableList()
                        list.add(reading)
                        if (list.size > 50) list.removeAt(0)
                        _liveTelemetryHistory.value = list

                        // If reading is accepted or has a valid status/hi, save to persistent Room database
                        if (reading.accepted || reading.hiSmooth != null || reading.status != HydrationStatus.UNKNOWN) {
                            hydrationDao.insertRecord(HydrationRecordEntity.fromReading(reading))
                        }

                        // Evaluate Measure Now request response
                        if (_measurementUiState.value is MeasurementUiState.Measuring) {
                            activeMeasurementJob?.cancel()
                            if (reading.accepted) {
                                _measurementUiState.value = MeasurementUiState.Success(reading)
                            } else {
                                val failReason = when (reading.status) {
                                    HydrationStatus.MOTION -> "Measurement rejected: wrist motion detected. Keep wrist still and retry."
                                    HydrationStatus.WEAR_BAND -> "Measurement rejected: band contact lost. Fasten snugly against skin."
                                    HydrationStatus.UNRELIABLE -> "Measurement rejected: optical signal quality low (SQI < 50%)."
                                    else -> "Measurement rejected: ${reading.rawStatusText.ifBlank { "Waveform check failed" }}"
                                }
                                _measurementUiState.value = MeasurementUiState.Failed(failReason)
                            }
                        }
                    }

                    is ParseResult.Calibration -> {
                        _calibrationInfo.value = result.info
                    }

                    is ParseResult.CalibrationComplete -> {
                        _calibrationInfo.value = result.info
                    }

                    is ParseResult.DeviceStatus -> {
                        // Status logs
                    }

                    else -> Unit
                }
            }
        }

        // Monitor connection drops during active measurement
        externalScope.launch {
            bleManager.connectionState.collect { state ->
                if (_measurementUiState.value is MeasurementUiState.Measuring) {
                    if (state == BleConnectionState.DISCONNECTED || state == BleConnectionState.FAILED) {
                        activeMeasurementJob?.cancel()
                        _measurementUiState.value = MeasurementUiState.Failed(
                            "Bluetooth connection lost during measurement cycle."
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // MEASURE NOW ON-DEMAND PROTOCOL
    // =========================================================================

    fun startMeasureNow() {
        // Prevent overlapping requests
        if (_measurementUiState.value is MeasurementUiState.Measuring) return

        // Guard 1: Connection verification
        if (bleManager.connectionState.value != BleConnectionState.READY) {
            _measurementUiState.value = MeasurementUiState.Failed(
                "HydroSense Edge is not connected. Connect via BLE first."
            )
            return
        }

        // Guard 2: Baseline Calibration verification
        if (!_calibrationInfo.value.isValid) {
            _measurementUiState.value = MeasurementUiState.Failed(
                "Personal baseline not calibrated. Please calibrate first while well-hydrated."
            )
            return
        }

        // Set measuring state
        _measurementUiState.value = MeasurementUiState.Measuring(
            percent = 0,
            secondsRemaining = 15,
            statusMessage = "Requesting measurement from ESP32..."
        )
        lastRequestTimestamp = System.currentTimeMillis()

        // Send 'm' command to ESP32 over BLE
        val sent = bleManager.sendCommand("m")
        if (!sent) {
            _measurementUiState.value = MeasurementUiState.Failed(
                "Failed to transmit measurement command to ESP32."
            )
            return
        }

        // Guard 3: 25-Second Timeout Guard (15s window + 5s settle + 5s buffer)
        activeMeasurementJob?.cancel()
        activeMeasurementJob = externalScope.launch {
            delay(25000L)
            if (_measurementUiState.value is MeasurementUiState.Measuring) {
                _measurementUiState.value = MeasurementUiState.Failed(
                    "Measurement timed out (no response received). Verify sensor is worn snugly and arm remains steady."
                )
            }
        }
    }

    fun resetMeasurementState() {
        activeMeasurementJob?.cancel()
        _measurementUiState.value = MeasurementUiState.Idle
    }

    // =========================================================================
    // DATABASE ACCESS
    // =========================================================================

    fun getAllRecords(): Flow<List<HydrationReading>> {
        return hydrationDao.getAllRecords().map { entities ->
            entities.map { it.toReading() }
        }
    }

    fun getRecordsByStatus(status: String): Flow<List<HydrationReading>> {
        return hydrationDao.getRecordsByStatus(status).map { entities ->
            entities.map { it.toReading() }
        }
    }

    suspend fun clearHistory() {
        hydrationDao.clearAll()
    }

    suspend fun deleteRecord(id: Long) {
        hydrationDao.deleteById(id)
    }

    // =========================================================================
    // BLE & HARDWARE ACTIONS
    // =========================================================================

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()
    fun connectDevice(address: String) = bleManager.connectDevice(address)
    fun disconnect() = bleManager.disconnect()
    fun dismissError() = bleManager.dismissError()

    // ESP32 Commands
    fun sendCalibrateCommand() = bleManager.sendCommand("c")
    fun sendClearReferenceCommand() = bleManager.sendCommand("x")
    fun sendStatusCommand() = bleManager.sendCommand("s")
    fun sendStreamCommand() = bleManager.sendCommand("p")
}
