package com.hydrosense.edge.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hydrosense.edge.data.ble.BleConnectionState
import com.hydrosense.edge.data.model.BleLogEntry
import com.hydrosense.edge.data.model.CalibrationInfo
import com.hydrosense.edge.data.model.DiscoveredBleDevice
import com.hydrosense.edge.data.model.HydrationReading
import com.hydrosense.edge.data.model.MeasurementUiState
import com.hydrosense.edge.data.repository.HydrationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HydrationViewModel(
    private val repository: HydrationRepository
) : ViewModel() {

    val latestReading: StateFlow<HydrationReading?> = repository.latestReading
    val liveTelemetryHistory: StateFlow<List<HydrationReading>> = repository.liveTelemetryHistory
    val calibrationInfo: StateFlow<CalibrationInfo> = repository.calibrationInfo
    val measurementUiState: StateFlow<MeasurementUiState> = repository.measurementUiState
    val connectionState: StateFlow<BleConnectionState> = repository.connectionState
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = repository.discoveredDevices
    val connectedDevice: StateFlow<DiscoveredBleDevice?> = repository.connectedDevice
    val errorMessage: StateFlow<String?> = repository.errorMessage

    // Logs buffer for debug screen
    private val _logs = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val logs: StateFlow<List<BleLogEntry>> = _logs.asStateFlow()

    // History filter
    private val _historyFilter = MutableStateFlow("ALL")
    val historyFilter: StateFlow<String> = _historyFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyRecords: StateFlow<List<HydrationReading>> = _historyFilter.flatMapLatest { filter ->
        if (filter == "ALL") {
            repository.getAllRecords()
        } else {
            repository.getRecordsByStatus(filter)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Collect logs into UI buffer
        viewModelScope.launch {
            repository.logEntries.collect { entry ->
                val current = _logs.value.toMutableList()
                current.add(0, entry)
                if (current.size > 250) {
                    current.removeAt(current.lastIndex)
                }
                _logs.value = current
            }
        }
    }

    // BLE Controls
    fun startScan() = repository.startScan()
    fun stopScan() = repository.stopScan()
    fun connectDevice(address: String) = repository.connectDevice(address)
    fun disconnect() = repository.disconnect()
    fun dismissError() = repository.dismissError()

    // ESP32 Hardware Controls
    fun measureNow() = repository.startMeasureNow()
    fun resetMeasurementState() = repository.resetMeasurementState()
    fun calibrate() = repository.sendCalibrateCommand()
    fun clearReference() = repository.sendClearReferenceCommand()
    fun requestStatus() = repository.sendStatusCommand()
    fun toggleRawStream() = repository.sendStreamCommand()

    // History Controls
    fun setFilter(filter: String) {
        _historyFilter.value = filter
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun deleteRecord(id: Long) {
        viewModelScope.launch {
            repository.deleteRecord(id)
        }
    }

    fun clearDebugLogs() {
        _logs.value = emptyList()
    }
}

class HydrationViewModelFactory(
    private val repository: HydrationRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HydrationViewModel::class.java)) {
            return HydrationViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
