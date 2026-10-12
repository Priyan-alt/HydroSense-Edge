package com.hydrosense.edge.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HydrationStatus(val displayName: String, val description: String) {
    HYDRATED("HYDRATED", "Hydration level is optimal"),
    BORDERLINE("BORDERLINE", "Low water reserve detected, consider fluids"),
    DEHYDRATED("DEHYDRATED", "Significant optical index drop, drink water"),
    MEASURING("MEASURING", "Analyzing PPG waveform windows..."),
    WEAR_BAND("WEAR BAND", "Sensor contact lost - wear band snugly"),
    MOTION("KEEP STILL", "Excessive motion detected - stabilize arm"),
    UNRELIABLE("UNRELIABLE", "Signal quality below threshold"),
    UNKNOWN("--", "Waiting for sensor stream...");

    companion object {
        fun fromString(str: String?): HydrationStatus {
            if (str == null) return UNKNOWN
            return when (str.trim().uppercase(Locale.ROOT)) {
                "HYDRATED" -> HYDRATED
                "BORDERLINE" -> BORDERLINE
                "DEHYDRATED" -> DEHYDRATED
                "MEASURING" -> MEASURING
                "WEAR BAND", "WEAR_BAND", "WEAR BAND SNUGLY" -> WEAR_BAND
                "KEEP STILL", "MOTION", "MOVING" -> MOTION
                "UNRELIABLE" -> UNRELIABLE
                else -> UNKNOWN
            }
        }
    }
}

data class HydrationReading(
    val id: Long = 0,
    val readingNumber: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val hi: Float? = null,
    val hiSmooth: Float? = null,
    val status: HydrationStatus = HydrationStatus.UNKNOWN,
    val rawStatusText: String = "",
    val isPreliminary: Boolean = false,
    val piComb: Float? = null,
    val piRed: Float? = null,
    val piIr: Float? = null,
    val hr: Float? = null,
    val rmssd: Float? = null,
    val sqi: Float? = null,
    val motion: String = "STABLE",
    val dcIr: Float? = null,
    val dcRed: Float? = null,
    val irShift: Float? = null,
    val refPI: Float? = null,
    val refHR: Float? = null,
    val refIR: Float? = null,
    val refCV: Float? = null,
    val contactFrac: Float? = null,
    val accepted: Boolean = false,
    val width: Float? = null,
    val rise: Float? = null,
    val area: Float? = null,
    val ampCV: Float? = null,
    val sdA: Float? = null,
    val sdG: Float? = null,
    val nValid: Int = 0,
    val requestId: Long? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

    val displayScore: String
        get() = when {
            hiSmooth != null -> String.format(Locale.US, "%.1f", hiSmooth)
            hi != null -> String.format(Locale.US, "%.1f", hi)
            else -> "--"
        }
}

data class CalibrationInfo(
    val isCalibrating: Boolean = false,
    val progress: Int = 0,
    val total: Int = 8,
    val lastPI: Float? = null,
    val lastSQI: Float? = null,
    val refPI: Float? = null,
    val refHR: Float? = null,
    val refIR: Float? = null,
    val scatter: Float? = null,
    val isValid: Boolean = false
) {
    val progressFraction: Float
        get() = if (total > 0) (progress.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
}

enum class LogDirection {
    IN, OUT, SYSTEM, ERROR
}

data class BleLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val direction: LogDirection,
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

data class DiscoveredBleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val isHydroSense: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

sealed class MeasurementUiState {
    object Idle : MeasurementUiState()
    data class Measuring(
        val percent: Int = 0,
        val secondsRemaining: Int = 15,
        val statusMessage: String = "Starting measurement cycle..."
    ) : MeasurementUiState()
    data class Success(
        val reading: HydrationReading
    ) : MeasurementUiState()
    data class Failed(
        val reason: String,
        val canRetry: Boolean = true
    ) : MeasurementUiState()
}

