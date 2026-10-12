package com.hydrosense.edge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydrosense.edge.data.model.HydrationStatus
import com.hydrosense.edge.ui.components.MetricTile
import com.hydrosense.edge.ui.components.StatusBadge
import com.hydrosense.edge.ui.components.WaveformChart
import com.hydrosense.edge.ui.theme.CyanPrimary
import com.hydrosense.edge.ui.theme.MetricIR
import com.hydrosense.edge.ui.theme.MetricMotion
import com.hydrosense.edge.ui.theme.MetricPPG
import com.hydrosense.edge.ui.theme.MetricSQI
import com.hydrosense.edge.ui.theme.NavyBorder
import com.hydrosense.edge.ui.theme.NavyCard
import com.hydrosense.edge.ui.theme.TextMuted
import com.hydrosense.edge.ui.theme.TextPrimary
import com.hydrosense.edge.ui.theme.TextSecondary
import com.hydrosense.edge.ui.viewmodel.HydrationViewModel
import java.util.Locale

@Composable
fun LiveSensorScreen(
    viewModel: HydrationViewModel,
    modifier: Modifier = Modifier
) {
    val reading by viewModel.latestReading.collectAsState()
    val liveHistory by viewModel.liveTelemetryHistory.collectAsState()

    val piHistory = liveHistory.mapNotNull { it.piComb }
    val hiHistory = liveHistory.mapNotNull { it.hiSmooth ?: it.hi }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Live Sensor Telemetry",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Real-Time 15-Second Window Analysis",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            StatusBadge(status = reading?.status ?: HydrationStatus.UNKNOWN)
        }

        // Live Telemetry Summary Cards: Row 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                title = "Hydration Index",
                value = reading?.displayScore ?: "--",
                unit = if (reading?.isPreliminary == true) "?" else "",
                subtitle = "Median of 5 windows",
                accentColor = CyanPrimary,
                icon = Icons.Default.WaterDrop,
                modifier = Modifier.weight(1f)
            )

            MetricTile(
                title = "Perfusion Index (PI)",
                value = if (reading?.piComb != null) String.format(Locale.US, "%.2f", reading?.piComb) else "--",
                unit = "%",
                subtitle = "AC / DC Optical Ratio",
                accentColor = MetricPPG,
                icon = Icons.Default.ShowChart,
                modifier = Modifier.weight(1f)
            )
        }

        // Live Telemetry Summary Cards: Row 2
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                title = "MAX30102 IR DC",
                value = if (reading?.dcIr != null) String.format(Locale.US, "%.0f", reading?.dcIr) else "--",
                subtitle = if (reading?.irShift != null) "Shift: ${String.format(Locale.US, "%+.0f%%", reading?.irShift)}" else "Photodiode IR Level",
                accentColor = MetricIR,
                icon = Icons.Default.LightMode,
                modifier = Modifier.weight(1f)
            )

            MetricTile(
                title = "MAX30102 RED DC",
                value = if (reading?.dcRed != null) String.format(Locale.US, "%.0f", reading?.dcRed) else "--",
                subtitle = "Photodiode RED Level",
                accentColor = MetricIR,
                icon = Icons.Default.LightMode,
                modifier = Modifier.weight(1f)
            )
        }

        // Live Telemetry Summary Cards: Row 3
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                title = "Signal Quality (SQI)",
                value = if (reading?.sqi != null) "${(reading!!.sqi!! * 100).toInt()}" else "--",
                unit = "%",
                subtitle = "Waveform Quality",
                accentColor = MetricSQI,
                icon = Icons.Default.Favorite,
                modifier = Modifier.weight(1f)
            )

            MetricTile(
                title = "MPU6050 Motion",
                value = reading?.motion ?: "STABLE",
                subtitle = if (reading?.sdA != null) "SD: ${String.format(Locale.US, "%.3fg", reading?.sdA)}" else "Accel/Gyro Stability",
                accentColor = MetricMotion,
                icon = Icons.Default.DirectionsRun,
                modifier = Modifier.weight(1f)
            )
        }

        // Continuous Trend Charts
        Text(
            text = "CONTINUOUS OPTICAL TREND CHARTS",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )

        // Graph 1: Perfusion Index Trend
        WaveformChart(
            title = "Perfusion Index (PI %) Window Stream",
            values = piHistory,
            unit = "%",
            lineColor = CyanPrimary
        )

        // Graph 2: Hydration Index Trend
        WaveformChart(
            title = "Hydration Index (HI) Stream",
            values = hiHistory,
            unit = "HI",
            lineColor = MetricPPG,
            minY = 50f,
            maxY = 110f
        )

        // PPG Waveform Morphology & Beat Features
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NavyCard)
                .border(1.dp, NavyBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Text(
                    text = "PPG PULSE MORPHOLOGY PARAMETERS",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Pulse Width", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.width != null) String.format(Locale.US, "%.0f ms", reading?.width) else "--",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column {
                        Text("Rise Time", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.rise != null) String.format(Locale.US, "%.0f ms", reading?.rise) else "--",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column {
                        Text("Pulse Area", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.area != null) String.format(Locale.US, "%.1f", reading?.area) else "--",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Amp CV", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.ampCV != null) String.format(Locale.US, "%.3f", reading?.ampCV) else "--",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column {
                        Text("RMSSD", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.rmssd != null) String.format(Locale.US, "%.1f ms", reading?.rmssd) else "--",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column {
                        Text("Valid Beats", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading != null) "${reading!!.nValid}" else "--",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // IMU Motion Details Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NavyCard)
                .border(1.dp, NavyBorder, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MPU6050 INERTIAL MOTION (IMU)",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = reading?.motion ?: "STABLE",
                        color = if (reading?.motion.equals("STABLE", true)) CyanPrimary else MetricMotion,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Accel SD (g)", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.sdA != null) String.format(Locale.US, "%.4f g", reading?.sdA) else "--",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Limit: < 0.050 g", color = TextMuted, fontSize = 10.sp)
                    }

                    Column {
                        Text("Gyro SD (°/s)", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.sdG != null) String.format(Locale.US, "%.2f °/s", reading?.sdG) else "--",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Limit: < 10.0 °/s", color = TextMuted, fontSize = 10.sp)
                    }

                    Column {
                        Text("Contact Frac", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = if (reading?.contactFrac != null) "${(reading!!.contactFrac!! * 100).toInt()}%" else "--",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Limit: > 98%", color = TextMuted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
