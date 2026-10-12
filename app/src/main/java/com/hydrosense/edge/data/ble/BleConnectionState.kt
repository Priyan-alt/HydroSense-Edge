package com.hydrosense.edge.data.ble

enum class BleConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    BLUETOOTH_DISABLED("Bluetooth Disabled"),
    PERMISSIONS_MISSING("Permissions Missing"),
    SCANNING("Scanning for HydroSense Edge..."),
    CONNECTING("Connecting..."),
    CONNECTED("Connected (Discovering Services...)"),
    SERVICES_DISCOVERED("Services Discovered"),
    SUBSCRIBING("Subscribing to Telemetry..."),
    READY("Active & Streaming"),
    RECONNECTING("Reconnecting..."),
    FAILED("Connection Failed")
}
