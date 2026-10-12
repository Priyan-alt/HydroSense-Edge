package com.hydrosense.edge.data.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.hydrosense.edge.data.model.BleLogEntry
import com.hydrosense.edge.data.model.DiscoveredBleDevice
import com.hydrosense.edge.data.model.LogDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(
    private val context: Context,
    private val packetParser: BlePacketParser = ModularBlePacketParser()
) {
    companion object {
        private const val TAG = "HydroSenseBle"
        const val TARGET_DEVICE_NAME = "HydroSense Edge"
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<DiscoveredBleDevice?>(null)
    val connectedDevice: StateFlow<DiscoveredBleDevice?> = _connectedDevice.asStateFlow()

    private val _parseResults = MutableSharedFlow<ParseResult>(extraBufferCapacity = 64)
    val parseResults: SharedFlow<ParseResult> = _parseResults.asSharedFlow()

    private val _logEntries = MutableSharedFlow<BleLogEntry>(extraBufferCapacity = 200)
    val logEntries: SharedFlow<BleLogEntry> = _logEntries.asSharedFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var autoReconnect: Boolean = true
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var lastTargetAddress: String? = null
    private var isScanning = false
    private var serviceDiscoveryInitiated = false

    val isBluetoothEnabled: Boolean
        get() = try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // =========================================================================
    // SCANNING
    // =========================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val scanRecord = result.scanRecord
            val name = scanRecord?.deviceName ?: device.name ?: "Unknown BLE Device"
            val address = device.address
            val rssi = result.rssi
            val isHydro = name.contains("HydroSense", ignoreCase = true) ||
                    name.equals(TARGET_DEVICE_NAME, ignoreCase = true) ||
                    scanRecord?.serviceUuids?.any { it.uuid.toString().equals(SERVICE_UUID.toString(), ignoreCase = true) } == true

            val current = _discoveredDevices.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == address }
            val item = DiscoveredBleDevice(
                name = name,
                address = address,
                rssi = rssi,
                isHydroSense = isHydro
            )

            if (existingIndex >= 0) {
                current[existingIndex] = item
            } else {
                if (isHydro) {
                    current.add(0, item) // Place target device on top
                } else {
                    current.add(item)
                }
            }
            _discoveredDevices.value = current
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _connectionState.value = BleConnectionState.FAILED
            val msg = "Scan failed with error code: $errorCode"
            log(LogDirection.ERROR, "Scan", msg)
            _errorMessage.value = msg
        }
    }

    fun startScan() {
        if (!hasRequiredPermissions()) {
            _connectionState.value = BleConnectionState.PERMISSIONS_MISSING
            _errorMessage.value = "Bluetooth permissions are required for scanning. Please grant permissions."
            log(LogDirection.ERROR, "Permissions", "Scan aborted: Missing BLE permissions")
            return
        }

        if (!isBluetoothEnabled) {
            _connectionState.value = BleConnectionState.BLUETOOTH_DISABLED
            _errorMessage.value = "Bluetooth is disabled. Please turn on Bluetooth."
            log(LogDirection.ERROR, "Bluetooth", "Cannot scan: Bluetooth is disabled")
            return
        }

        if (!isLocationServiceEnabled()) {
            _errorMessage.value = "Location services (GPS) must be enabled on your device for BLE scanning."
            log(LogDirection.SYSTEM, "Location", "Warning: Location services disabled, scan results may be limited")
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.FAILED
            _errorMessage.value = "BLE Scanner not available on this device"
            return
        }

        if (isScanning) return

        _discoveredDevices.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING
        isScanning = true
        _errorMessage.value = null
        log(LogDirection.SYSTEM, "Scan", "Started BLE scanning for '$TARGET_DEVICE_NAME'...")

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            // Non-filtered scan to ensure compatibility across diverse OEM Bluetooth chipsets
            scanner.startScan(null, settings, scanCallback)

            // Auto-stop scan after 15 seconds
            mainHandler.postDelayed({
                if (isScanning) {
                    stopScan()
                    if (_connectionState.value == BleConnectionState.SCANNING) {
                        _connectionState.value = BleConnectionState.DISCONNECTED
                        if (_discoveredDevices.value.none { it.isHydroSense }) {
                            _errorMessage.value = "ESP32 '$TARGET_DEVICE_NAME' not found nearby. Verify board power and range."
                        }
                    }
                }
            }, 15000)

        } catch (e: SecurityException) {
            isScanning = false
            _connectionState.value = BleConnectionState.PERMISSIONS_MISSING
            _errorMessage.value = "SecurityException starting scan: ${e.message}"
            log(LogDirection.ERROR, "Permissions", "SecurityException: ${e.message}")
        } catch (e: Exception) {
            isScanning = false
            _connectionState.value = BleConnectionState.FAILED
            _errorMessage.value = "Scan error: ${e.message}"
            log(LogDirection.ERROR, "Scan", "Exception starting scan: ${e.message}")
        }
    }

    fun stopScan() {
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            log(LogDirection.SYSTEM, "Scan", "Stopped BLE scanning")
        } catch (e: SecurityException) {
            log(LogDirection.ERROR, "Scan", "SecurityException stopping scan: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan", e)
        }
    }

    // =========================================================================
    // CONNECTION & GATT
    // =========================================================================

    fun connectDevice(address: String) {
        if (!hasRequiredPermissions()) {
            _connectionState.value = BleConnectionState.PERMISSIONS_MISSING
            _errorMessage.value = "Bluetooth permissions are required to connect."
            return
        }

        stopScan()
        reconnectJob?.cancel()
        lastTargetAddress = address
        serviceDiscoveryInitiated = false

        val device = try {
            bluetoothAdapter?.getRemoteDevice(address)
        } catch (e: Exception) {
            _errorMessage.value = "Invalid Bluetooth address: $address"
            return
        }

        if (device == null) {
            _errorMessage.value = "Device not found for address $address"
            return
        }

        val name = try { device.name ?: TARGET_DEVICE_NAME } catch (e: Exception) { TARGET_DEVICE_NAME }
        _connectedDevice.value = DiscoveredBleDevice(name, address, 0, true)
        _connectionState.value = BleConnectionState.CONNECTING
        _errorMessage.value = null
        log(LogDirection.SYSTEM, "GATT", "Connecting to $name ($address)...")

        closeGatt()

        try {
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            _connectionState.value = BleConnectionState.PERMISSIONS_MISSING
            _errorMessage.value = "SecurityException connecting to device: ${e.message}"
            log(LogDirection.ERROR, "Permissions", "SecurityException: ${e.message}")
        }
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        stopScan()

        log(LogDirection.SYSTEM, "GATT", "Disconnect requested by user")
        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            log(LogDirection.ERROR, "GATT", "SecurityException on disconnect: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting", e)
        }
        _connectionState.value = BleConnectionState.DISCONNECTED
        _connectedDevice.value = null
        telemetryCharacteristic = null
    }

    private fun closeGatt() {
        try {
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT", e)
        }
        bluetoothGatt = null
        telemetryCharacteristic = null
        serviceDiscoveryInitiated = false
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val statusHex = "0x" + Integer.toHexString(status)
            log(LogDirection.SYSTEM, "GATT", "Connection state change: status=$statusHex, newState=$newState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogDirection.ERROR, "GATT", "GATT connection failure with status $statusHex")
                handleDisconnection(isError = true, errorMessage = "Connection failed (status $statusHex)")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    serviceDiscoveryInitiated = false
                    _connectionState.value = BleConnectionState.CONNECTED
                    log(LogDirection.SYSTEM, "GATT", "Connected to GATT server! Negotiating MTU 512...")

                    // Request higher MTU for comprehensive JSON telemetry packets
                    scope.launch {
                        delay(300)
                        val requested = try {
                            gatt.requestMtu(512)
                        } catch (e: Exception) {
                            false
                        }
                        log(LogDirection.SYSTEM, "GATT", "requestMtu(512) call result: $requested")

                        // Timeout fallback: if onMtuChanged is not invoked within 1200ms, discover services directly
                        delay(1200)
                        if (!serviceDiscoveryInitiated && _connectionState.value == BleConnectionState.CONNECTED) {
                            log(LogDirection.SYSTEM, "GATT", "Proceeding with service discovery...")
                            serviceDiscoveryInitiated = true
                            try { gatt.discoverServices() } catch (e: Exception) { Log.e(TAG, "discoverServices error", e) }
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log(LogDirection.SYSTEM, "GATT", "Disconnected from GATT server")
                    handleDisconnection(isError = false, errorMessage = "Device disconnected")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log(LogDirection.SYSTEM, "GATT", "MTU negotiated: $mtu bytes (status: $status)")
            if (!serviceDiscoveryInitiated) {
                serviceDiscoveryInitiated = true
                _connectionState.value = BleConnectionState.SERVICES_DISCOVERED
                scope.launch {
                    delay(200)
                    try { gatt.discoverServices() } catch (e: Exception) { Log.e(TAG, "discoverServices error", e) }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log(LogDirection.ERROR, "GATT", "Service discovery failed with status $status")
                _errorMessage.value = "Service discovery failed ($status)"
                return
            }

            log(LogDirection.SYSTEM, "GATT", "Services discovered. Matching HydroSense UUIDs...")

            // Resilient case-insensitive service search
            val service = gatt.getService(SERVICE_UUID)
                ?: gatt.services.firstOrNull { it.uuid.toString().equals(SERVICE_UUID.toString(), ignoreCase = true) }

            if (service == null) {
                val availableServices = gatt.services.joinToString(", ") { it.uuid.toString() }
                val msg = "HydroSense Service ($SERVICE_UUID) not found! Peripheral has: $availableServices"
                log(LogDirection.ERROR, "GATT", msg)
                _errorMessage.value = msg
                _connectionState.value = BleConnectionState.FAILED
                return
            }

            // Resilient case-insensitive characteristic search
            val characteristic = service.getCharacteristic(CHAR_UUID)
                ?: service.characteristics.firstOrNull { it.uuid.toString().equals(CHAR_UUID.toString(), ignoreCase = true) }

            if (characteristic == null) {
                val availableChars = service.characteristics.joinToString(", ") { it.uuid.toString() }
                val msg = "HydroSense Characteristic ($CHAR_UUID) not found! Service has: $availableChars"
                log(LogDirection.ERROR, "GATT", msg)
                _errorMessage.value = msg
                _connectionState.value = BleConnectionState.FAILED
                return
            }

            telemetryCharacteristic = characteristic
            _connectionState.value = BleConnectionState.SUBSCRIBING
            log(LogDirection.SYSTEM, "GATT", "Characteristic found. Subscribing to notifications...")

            enableNotifications(gatt, characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            handleIncomingBytes(bytes)
        }

        // Android 13+ overload
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.READY
                _errorMessage.value = null
                log(LogDirection.SYSTEM, "GATT", "Notification CCCD enabled! Telemetry stream active.")
            } else {
                val msg = "Failed to write CCCD descriptor (status: $status)"
                log(LogDirection.ERROR, "GATT", msg)
                _errorMessage.value = msg
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log(LogDirection.OUT, "GATT", "Command write succeeded")
            } else {
                log(LogDirection.ERROR, "GATT", "Command write failed ($status)")
            }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        val notifySuccess = gatt.setCharacteristicNotification(char, true)
        log(LogDirection.SYSTEM, "GATT", "setCharacteristicNotification result: $notifySuccess")

        // Resilient CCCD descriptor lookup
        val descriptor = char.getDescriptor(CCCD_UUID)
            ?: char.descriptors.firstOrNull { it.uuid.toString().equals(CCCD_UUID.toString(), ignoreCase = true) }

        if (descriptor == null) {
            val msg = "CCCD (0x2902) descriptor not found on characteristic!"
            log(LogDirection.ERROR, "GATT", msg)
            _errorMessage.value = msg
            return
        }

        // Determine notification vs indication payload
        val descriptorValue = if ((char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else if ((char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeRes = gatt.writeDescriptor(descriptor, descriptorValue)
                log(LogDirection.SYSTEM, "GATT", "Tiramisu writeDescriptor returned status: $writeRes")
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = descriptorValue
                @Suppress("DEPRECATION")
                val writeRes = gatt.writeDescriptor(descriptor)
                log(LogDirection.SYSTEM, "GATT", "Legacy writeDescriptor returned: $writeRes")
            }
        } catch (e: Exception) {
            log(LogDirection.ERROR, "GATT", "Descriptor write exception: ${e.message}")
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        val rawText = String(bytes, StandardCharsets.UTF_8)
        if (rawText.isEmpty()) return

        log(LogDirection.IN, "BLE_RX", rawText)

        val parseResults = packetParser.parse(rawText)
        for (parseResult in parseResults) {
            if (parseResult is ParseResult.Error) {
                log(LogDirection.ERROR, "Parser", "Parse error: ${parseResult.errorMessage}")
            }
            scope.launch {
                _parseResults.emit(parseResult)
            }
        }
    }

    private fun handleDisconnection(isError: Boolean, errorMessage: String) {
        closeGatt()
        _connectedDevice.value = null

        if (autoReconnect && lastTargetAddress != null && reconnectAttempts < 5) {
            _connectionState.value = BleConnectionState.RECONNECTING
            reconnectAttempts++
            val delayMs = (reconnectAttempts * 2000L).coerceAtMost(10000L)
            log(LogDirection.SYSTEM, "Reconnect", "Reconnecting attempt $reconnectAttempts in ${delayMs / 1000}s...")

            reconnectJob = scope.launch {
                delay(delayMs)
                lastTargetAddress?.let { connectDevice(it) }
            }
        } else {
            _connectionState.value = if (isError) BleConnectionState.FAILED else BleConnectionState.DISCONNECTED
            _errorMessage.value = errorMessage
        }
    }

    // =========================================================================
    // COMMAND SENDER
    // =========================================================================

    fun sendCommand(cmd: String): Boolean {
        val gatt = bluetoothGatt
        val char = telemetryCharacteristic
        if (gatt == null || char == null) {
            log(LogDirection.ERROR, "Command", "Cannot send '$cmd': not connected")
            return false
        }

        log(LogDirection.OUT, "BLE_TX", "Sending command: '$cmd'")
        val bytes = cmd.toByteArray(StandardCharsets.UTF_8)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val res = gatt.writeCharacteristic(
                    char,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                res == 0 // BluetoothStatusCodes.SUCCESS is 0
            } else {
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: SecurityException) {
            log(LogDirection.ERROR, "Command", "SecurityException: ${e.message}")
            false
        } catch (e: Exception) {
            log(LogDirection.ERROR, "Command", "Write error: ${e.message}")
            false
        }
    }

    private fun log(dir: LogDirection, tag: String, message: String) {
        Log.d(TAG, "[$dir][$tag] $message")
        scope.launch {
            _logEntries.emit(BleLogEntry(direction = dir, tag = tag, message = message))
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }
}
