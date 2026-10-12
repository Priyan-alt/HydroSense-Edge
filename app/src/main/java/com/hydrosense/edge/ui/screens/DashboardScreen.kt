package com.hydrosense.edge.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydrosense.edge.R
import com.hydrosense.edge.data.ble.BleConnectionState
import com.hydrosense.edge.ui.components.BleStatusBar
import com.hydrosense.edge.ui.components.CalibrationCard
import com.hydrosense.edge.ui.components.ErrorBanner
import com.hydrosense.edge.ui.components.HydrationHeroCard
import com.hydrosense.edge.ui.components.MeasureNowCard
import com.hydrosense.edge.ui.components.MetricTile
import com.hydrosense.edge.ui.components.SignalQualityMeter
import com.hydrosense.edge.ui.theme.CyanPrimary
import com.hydrosense.edge.ui.theme.DehydratedColor
import com.hydrosense.edge.ui.theme.MetricIR
import com.hydrosense.edge.ui.theme.MetricMotion
import com.hydrosense.edge.ui.theme.MetricPPG
import com.hydrosense.edge.ui.theme.MetricSQI
import com.hydrosense.edge.ui.theme.NavyBorder
import com.hydrosense.edge.ui.theme.NavyCard
import com.hydrosense.edge.ui.theme.NavyCardElevated
import com.hydrosense.edge.ui.theme.TextMuted
import com.hydrosense.edge.ui.theme.TextPrimary
import com.hydrosense.edge.ui.theme.TextSecondary
import com.hydrosense.edge.ui.viewmodel.HydrationViewModel
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: HydrationViewModel,
    onNavigateToDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reading by viewModel.latestReading.collectAsState()
    val calibrationInfo by viewModel.calibrationInfo.collectAsState()
    val measurementUiState by viewModel.measurementUiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedDevice by viewModel.connectedDevice.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var selectedMetricInfo by remember { mutableStateOf<Pair<String, String>?>(null) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Branded Header with Official Logo
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NavyCard)
                    .border(1.5.dp, CyanPrimary.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(3.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_hydrosense_logo),
                    contentDescription = "HydroSense Edge Logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(11.dp)),
                    contentScale = ContentScale.Fit
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "HydroSense Edge",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp
                )
                Text(
                    text = "YOUR HYDRATION INSIGHT IN REAL TIME",
                    color = CyanPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
        }

        // BLE Connection Banner
        BleStatusBar(
            connectionState = connectionState,
            connectedDeviceName = connectedDevice?.name,
            onConnectClick = {
                if (connectionState == BleConnectionState.DISCONNECTED ||
                    connectionState == BleConnectionState.FAILED
                ) {
                    onNavigateToDevice()
                } else {
                    viewModel.startScan()
                }
            },
            onDisconnectClick = { viewModel.disconnect() }
        )

        // Error Banner if present
        errorMessage?.let { error ->
            ErrorBanner(
                errorMessage = error,
                onDismiss = { viewModel.dismissError() }
            )
        }

        // On-Demand "Measure Now" Action Card
        MeasureNowCard(
            state = measurementUiState,
            isDeviceConnected = connectionState == BleConnectionState.READY,
            isCalibrated = calibrationInfo.isValid,
            onMeasureClick = { viewModel.measureNow() },
            onResetClick = { viewModel.resetMeasurementState() }
        )

        // Hydration Hero Card (Main Gauge)
        HydrationHeroCard(reading = reading)

        // Baseline Calibration Card
        CalibrationCard(
            calibrationInfo = calibrationInfo,
            onCalibrateClick = { viewModel.calibrate() },
            onClearClick = { viewModel.clearReference() }
        )

        // Signal Quality & Motion Meter
        SignalQualityMeter(
            sqi = reading?.sqi,
            contactFrac = reading?.contactFrac,
            motion = reading?.motion ?: "STABLE"
        )

        // Core Metrics Grid Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PHYSIOLOGICAL SENSOR TELEMETRY",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Tap card for info",
                color = TextMuted,
                fontSize = 10.sp
            )
        }

        // Row 1: Perfusion Index & Signal Quality
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                title = "Perfusion Index",
                value = if (reading?.piComb != null) String.format(Locale.US, "%.2f", reading?.piComb) else "--",
                unit = "%",
                subtitle = if (reading?.refPI != null) "Ref: ${String.format(Locale.US, "%.2f%%", reading?.refPI)}" else "Optical AC/DC Ratio",
                accentColor = MetricPPG,
                icon = Icons.Default.ShowChart,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        selectedMetricInfo = Pair(
                            "Perfusion Index (PI)",
                            "Perfusion Index indicates the ratio of pulsatile blood flow to non-pulsatile static tissue absorption. In hydration monitoring, a relative drop in PI compared to your baseline indicates fluid loss."
                        )
                    }
            )

            MetricTile(
                title = "Signal Quality",
                value = if (reading?.sqi != null) "${(reading!!.sqi!! * 100).toInt()}" else "--",
                unit = "%",
                subtitle = if (reading?.accepted == true) "Window Accepted" else "Evaluating waveform",
                accentColor = MetricSQI,
                icon = Icons.Default.Favorite,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        selectedMetricInfo = Pair(
                            "Signal Quality Index (SQI)",
                            "Calculated from contact stability, beat peak morphology, and motion stillness. High SQI (> 75%) guarantees reliable optical index calculations."
                        )
                    }
            )
        }

        // Row 2: MAX30102 IR DC Level & IMU Motion
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                title = "MAX30102 IR DC",
                value = if (reading?.dcIr != null) String.format(Locale.US, "%.0f", reading?.dcIr) else "--",
                subtitle = if (reading?.irShift != null) "Shift: ${String.format(Locale.US, "%+.0f%%", reading?.irShift)}" else "Contact Level",
                accentColor = MetricIR,
                icon = Icons.Default.LightMode,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        selectedMetricInfo = Pair(
                            "MAX30102 IR Baseline",
                            "Measures photodiode baseline infrared absorbance. A large shift (>25%) from calibration indicates the band needs adjustment to match calibration tightness."
                        )
                    }
            )

            MetricTile(
                title = "IMU Motion",
                value = reading?.motion ?: "STABLE",
                subtitle = if (reading?.sdA != null) "SD: ${String.format(Locale.US, "%.3fg", reading?.sdA)}" else "MPU6050 Stability",
                accentColor = MetricMotion,
                icon = Icons.Default.DirectionsRun,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        selectedMetricInfo = Pair(
                            "MPU6050 IMU Motion",
                            "Monitors accelerometer and gyroscope variance. Windows with excessive motion (SD > 0.05g or > 10°/s) are rejected to prevent movement artifacts."
                        )
                    }
            )
        }

        // Quick ESP32 Commands Bar
        if (connectionState == BleConnectionState.READY) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(NavyCard)
                    .border(1.dp, NavyBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Quick Controls:",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.calibrate() },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanPrimary)
                        ) {
                            Text("Calib ('c')", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { viewModel.requestStatus() },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                        ) {
                            Text("Status ('s')", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Disclaimer
        Text(
            text = "Experimental system: Hydration Index (HI) is an optical metric derived from PPG perfusion index compared to personal baseline. Not intended for medical diagnosis.",
            color = TextMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
    }

    // Info Dialog
    selectedMetricInfo?.let { (title, description) ->
        AlertDialog(
            onDismissRequest = { selectedMetricInfo = null },
            title = {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = description,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedMetricInfo = null }) {
                    Text("OK", color = CyanPrimary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = NavyCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
