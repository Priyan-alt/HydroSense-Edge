package com.hydrosense.edge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydrosense.edge.data.ble.BleConnectionState
import com.hydrosense.edge.data.model.CalibrationInfo
import com.hydrosense.edge.data.model.HydrationReading
import com.hydrosense.edge.data.model.HydrationStatus
import com.hydrosense.edge.data.model.MeasurementUiState
import com.hydrosense.edge.ui.theme.BorderlineBadgeBg
import com.hydrosense.edge.ui.theme.BorderlineColor
import com.hydrosense.edge.ui.theme.CyanGlow
import com.hydrosense.edge.ui.theme.CyanPrimary
import com.hydrosense.edge.ui.theme.CyanSecondary
import com.hydrosense.edge.ui.theme.DehydratedBadgeBg
import com.hydrosense.edge.ui.theme.DehydratedColor
import com.hydrosense.edge.ui.theme.HydratedBadgeBg
import com.hydrosense.edge.ui.theme.HydratedColor
import com.hydrosense.edge.ui.theme.MeasuringBadgeBg
import com.hydrosense.edge.ui.theme.MeasuringColor
import com.hydrosense.edge.ui.theme.NavyBorder
import com.hydrosense.edge.ui.theme.NavyCard
import com.hydrosense.edge.ui.theme.NavyCardElevated
import com.hydrosense.edge.ui.theme.TextMuted
import com.hydrosense.edge.ui.theme.TextPrimary
import com.hydrosense.edge.ui.theme.TextSecondary
import java.util.Locale

// =========================================================================
// STATUS BADGE
// =========================================================================

@Composable
fun StatusBadge(
    status: HydrationStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, text) = when (status) {
        HydrationStatus.HYDRATED -> Triple(HydratedBadgeBg, HydratedColor, "HYDRATED")
        HydrationStatus.BORDERLINE -> Triple(BorderlineBadgeBg, BorderlineColor, "BORDERLINE")
        HydrationStatus.DEHYDRATED -> Triple(DehydratedBadgeBg, DehydratedColor, "DEHYDRATED")
        HydrationStatus.MEASURING -> Triple(MeasuringBadgeBg, MeasuringColor, "MEASURING")
        HydrationStatus.WEAR_BAND -> Triple(DehydratedBadgeBg, DehydratedColor, "WEAR BAND")
        HydrationStatus.MOTION -> Triple(BorderlineBadgeBg, BorderlineColor, "KEEP STILL")
        HydrationStatus.UNRELIABLE -> Triple(BorderlineBadgeBg, BorderlineColor, "UNRELIABLE")
        HydrationStatus.UNKNOWN -> Triple(NavyCardElevated, TextSecondary, "--")
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

// =========================================================================
// HYDRATION HERO CARD
// =========================================================================

@Composable
fun HydrationHeroCard(
    reading: HydrationReading?,
    modifier: Modifier = Modifier
) {
    val status = reading?.status ?: HydrationStatus.UNKNOWN
    val statusColor by animateColorAsState(
        targetValue = when (status) {
            HydrationStatus.HYDRATED -> HydratedColor
            HydrationStatus.BORDERLINE -> BorderlineColor
            HydrationStatus.DEHYDRATED -> DehydratedColor
            HydrationStatus.MEASURING -> MeasuringColor
            else -> CyanPrimary
        },
        label = "heroStatusColor"
    )

    val hiScore = reading?.hiSmooth ?: reading?.hi
    val animatedProgress by animateFloatAsState(
        targetValue = if (hiScore != null) (hiScore / 120f).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(600),
        label = "heroProgress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(NavyCardElevated, NavyCard)
                )
            )
            .border(1.5.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "HYDRATION INDEX (HI)",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (reading?.isPreliminary == true) "Preliminary Estimate (?)" else "PPG Optical Ratio",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                StatusBadge(status = status)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Score display
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (hiScore != null) String.format(Locale.US, "%.1f", hiScore) else "--",
                    color = TextPrimary,
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 54.sp
                )
                if (reading?.isPreliminary == true) {
                    Text(
                        text = "?",
                        color = BorderlineColor,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = "/ 100 Baseline",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (reading?.hi != null && reading.hiSmooth != null) {
                        Text(
                            text = "Window: ${String.format(Locale.US, "%.1f", reading.hi)}",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = statusColor,
                trackColor = NavyBorder
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Threshold benchmarks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DEHYDRATED (<70)",
                    color = DehydratedColor.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "BORDERLINE (70-90)",
                    color = BorderlineColor.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "HYDRATED (≥90)",
                    color = HydratedColor.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (reading?.rawStatusText?.isNotBlank() == true && reading.rawStatusText != "--") {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Sensor Note: ${reading.rawStatusText}",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// =========================================================================
// MEASURE NOW ON-DEMAND CARD
// =========================================================================

@Composable
fun MeasureNowCard(
    state: MeasurementUiState,
    isDeviceConnected: Boolean,
    isCalibrated: Boolean,
    onMeasureClick: () -> Unit,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(NavyCardElevated, NavyCard)
                )
            )
            .border(
                1.5.dp,
                when (state) {
                    is MeasurementUiState.Measuring -> CyanPrimary.copy(alpha = 0.8f)
                    is MeasurementUiState.Success -> HydratedColor.copy(alpha = 0.8f)
                    is MeasurementUiState.Failed -> DehydratedColor.copy(alpha = 0.8f)
                    else -> CyanPrimary.copy(alpha = 0.35f)
                },
                RoundedCornerShape(20.dp)
            )
            .padding(20.dp)
    ) {
        when (state) {
            is MeasurementUiState.Idle -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ON-DEMAND MEASUREMENT",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Fresh 15-second PPG hydration reading",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyanPrimary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "READY",
                                color = CyanPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onMeasureClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        enabled = isDeviceConnected && isCalibrated,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            disabledContainerColor = NavyBorder
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Measure Now",
                            tint = if (isDeviceConnected && isCalibrated) NavyCard else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                !isDeviceConnected -> "Connect BLE to Measure"
                                !isCalibrated -> "Calibrate Baseline First"
                                else -> "MEASURE NOW"
                            },
                            color = if (isDeviceConnected && isCalibrated) NavyCard else TextMuted,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    if (!isCalibrated && isDeviceConnected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A baseline calibration while well-hydrated is required before taking measurements.",
                            color = BorderlineColor,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            is MeasurementUiState.Measuring -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "MEASURING IN PROGRESS",
                                color = CyanPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = state.statusMessage,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = CyanPrimary,
                            strokeWidth = 2.5.dp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    LinearProgressIndicator(
                        progress = { (state.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = CyanPrimary,
                        trackColor = NavyBorder
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Window: ${state.percent}%",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${state.secondsRemaining}s remaining",
                            color = CyanPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(NavyBorder.copy(alpha = 0.5f))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "Keep wrist resting flat and stay still. The ESP32-S3 is recording 1,500 samples across dual PPG wavelengths and checking IMU stability.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            is MeasurementUiState.Success -> {
                val r = state.reading
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = HydratedColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "FRESH READING ACCEPTED",
                                color = HydratedColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                        StatusBadge(status = r.status)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = "Hydration Index",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                            Text(
                                text = r.displayScore,
                                color = TextPrimary,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "PI: " + if (r.piComb != null) String.format(Locale.US, "%.2f%%", r.piComb) else "--",
                                color = CyanPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "HR: " + if (r.hr != null) "${r.hr.toInt()} BPM" else "--",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "SQI: " + if (r.sqi != null) "${(r.sqi * 100).toInt()}%" else "--",
                                color = if ((r.sqi ?: 0f) >= 0.7f) HydratedColor else BorderlineColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onMeasureClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = NavyCard, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Measure Again", color = NavyCard, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onResetClick,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Text("Done", fontSize = 12.sp)
                        }
                    }
                }
            }

            is MeasurementUiState.Failed -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = DehydratedColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "MEASUREMENT REJECTED",
                                color = DehydratedColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        TextButton(onClick = onResetClick) {
                            Text("Dismiss", color = TextMuted, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = state.reason,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = onMeasureClick,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = NavyCard, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry Measurement", color = NavyCard, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// =========================================================================
// METRIC TILE
// =========================================================================

@Composable
fun MetricTile(
    title: String,
    value: String,
    unit: String = "",
    subtitle: String = "",
    accentColor: Color = CyanPrimary,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(NavyCard)
            .border(1.dp, NavyBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title.uppercase(Locale.ROOT),
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

// =========================================================================
// SIGNAL QUALITY METER
// =========================================================================

@Composable
fun SignalQualityMeter(
    sqi: Float?,
    contactFrac: Float?,
    motion: String,
    modifier: Modifier = Modifier
) {
    val sqiVal = sqi ?: 0f
    val sqiPercent = (sqiVal * 100).toInt()
    val color = when {
        sqiVal >= 0.75f -> HydratedColor
        sqiVal >= 0.60f -> BorderlineColor
        else -> DehydratedColor
    }

    Box(
        modifier = modifier
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
                    text = "SIGNAL FIDELITY & MOTION",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = if (sqi != null) "$sqiPercent% SQI" else "--",
                    color = color,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { if (sqi != null) sqiVal.coerceIn(0f, 1f) else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = color,
                trackColor = NavyBorder
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Motion: $motion",
                    color = if (motion.equals("STABLE", true)) HydratedColor else BorderlineColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Contact: " + if (contactFrac != null) "${(contactFrac * 100).toInt()}%" else "--",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    text = "Quality: " + when {
                        sqi == null -> "--"
                        sqiVal >= 0.75f -> "Excellent"
                        sqiVal >= 0.60f -> "Fair"
                        else -> "Poor"
                    },
                    color = color,
                    fontSize = 12.sp
                )
            }
        }
    }
}

// =========================================================================
// CALIBRATION CARD
// =========================================================================

@Composable
fun CalibrationCard(
    calibrationInfo: CalibrationInfo,
    onCalibrateClick: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
                    text = "PERSONAL BASELINE CALIBRATION",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                if (calibrationInfo.isCalibrating) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(BorderlineBadgeBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "CALIBRATING ${calibrationInfo.progress}/${calibrationInfo.total}",
                            color = BorderlineColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (calibrationInfo.isValid) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(HydratedBadgeBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "BASELINE ACTIVE",
                            color = HydratedColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Text(
                        text = "NOT CALIBRATED",
                        color = DehydratedColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (calibrationInfo.isCalibrating) {
                LinearProgressIndicator(
                    progress = { calibrationInfo.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = BorderlineColor,
                    trackColor = NavyBorder
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Stay still & relaxed while hydrated. Window ${calibrationInfo.progress} of ${calibrationInfo.total} recorded.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            } else if (calibrationInfo.isValid) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Ref PI", color = TextMuted, fontSize = 11.sp)
                        Text(
                            String.format(Locale.US, "%.2f %%", calibrationInfo.refPI ?: 0f),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Column {
                        Text("Scatter (CV)", color = TextMuted, fontSize = 11.sp)
                        Text(
                            String.format(Locale.US, "%.1f %%", calibrationInfo.scatter ?: 0f),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Column {
                        Text("Ref HR", color = TextMuted, fontSize = 11.sp)
                        Text(
                            String.format(Locale.US, "%.0f BPM", calibrationInfo.refHR ?: 0f),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Column {
                        Text("Ref IR DC", color = TextMuted, fontSize = 11.sp)
                        Text(
                            String.format(Locale.US, "%.0f", calibrationInfo.refIR ?: 0f),
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                Text(
                    text = "Calibrate once while well-hydrated to establish your personal optical PPG baseline.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onCalibrateClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = if (calibrationInfo.isCalibrating) "Restart Calib ('c')" else "Calibrate Baseline ('c')",
                        color = NavyCard,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                OutlinedButton(
                    onClick = onClearClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DehydratedColor)
                ) {
                    Text("Clear ('x')", fontSize = 12.sp)
                }
            }
        }
    }
}

// =========================================================================
// BLE STATUS BAR
// =========================================================================

@Composable
fun BleStatusBar(
    connectionState: BleConnectionState,
    connectedDeviceName: String?,
    onConnectClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isReady = connectionState == BleConnectionState.READY
    val isConnecting = connectionState == BleConnectionState.CONNECTING ||
            connectionState == BleConnectionState.SCANNING ||
            connectionState == BleConnectionState.RECONNECTING

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard)
            .border(
                1.dp,
                if (isReady) CyanPrimary.copy(alpha = 0.5f) else NavyBorder,
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = when {
                        isReady -> Icons.Default.BluetoothConnected
                        connectionState == BleConnectionState.BLUETOOTH_DISABLED -> Icons.Default.BluetoothDisabled
                        else -> Icons.Default.Bluetooth
                    },
                    contentDescription = null,
                    tint = when {
                        isReady -> CyanPrimary
                        isConnecting -> BorderlineColor
                        else -> TextMuted
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = connectedDeviceName ?: "HydroSense Edge",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = connectionState.label,
                        color = when {
                            isReady -> CyanPrimary
                            isConnecting -> BorderlineColor
                            connectionState == BleConnectionState.FAILED -> DehydratedColor
                            else -> TextSecondary
                        },
                        fontSize = 11.sp
                    )
                }
            }

            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = BorderlineColor
                )
            } else if (isReady) {
                OutlinedButton(
                    onClick = onDisconnectClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DehydratedColor),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Disconnect", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = onConnectClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Connect", color = NavyCard, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// =========================================================================
// ERROR BANNER
// =========================================================================

@Composable
fun ErrorBanner(
    errorMessage: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DehydratedBadgeBg)
            .border(1.dp, DehydratedColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = DehydratedColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = errorMessage,
                    color = TextPrimary,
                    fontSize = 12.sp
                )
            }
            Text(
                text = "DISMISS",
                color = DehydratedColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(start = 8.dp)
            )
        }
    }
}

// =========================================================================
// CANVAS WAVEFORM / TREND CHART
// =========================================================================

@Composable
fun WaveformChart(
    title: String,
    values: List<Float>,
    unit: String = "",
    lineColor: Color = CyanPrimary,
    minY: Float? = null,
    maxY: Float? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
                    text = title.uppercase(Locale.ROOT),
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                val lastVal = values.lastOrNull()
                Text(
                    text = if (lastVal != null) String.format(Locale.US, "%.2f %s", lastVal, unit) else "--",
                    color = lineColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (values.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Collecting window telemetry...",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            } else {
                val dataMin = minY ?: (values.minOrNull() ?: 0f)
                val dataMax = maxY ?: (values.maxOrNull() ?: 1f)
                val range = if (dataMax - dataMin == 0f) 1f else (dataMax - dataMin)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                ) {
                    val w = size.width
                    val h = size.height

                    // Grid lines
                    for (i in 0..3) {
                        val y = h * (i / 3f)
                        drawLine(
                            color = NavyBorder.copy(alpha = 0.6f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }

                    val stepX = w / (values.size - 1)
                    val points = values.mapIndexed { index, v ->
                        val normalized = (v - dataMin) / range
                        val x = index * stepX
                        val y = h - (normalized * h)
                        Offset(x, y.coerceIn(0f, h))
                    }

                    // Fill Path
                    val fillPath = Path().apply {
                        moveTo(0f, h)
                        points.forEach { lineTo(it.x, it.y) }
                        lineTo(w, h)
                        close()
                    }
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            listOf(lineColor.copy(alpha = 0.35f), Color.Transparent),
                            startY = 0f,
                            endY = h
                        )
                    )

                    // Line Path
                    val strokePath = Path().apply {
                        points.forEachIndexed { i, pt ->
                            if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(
                        path = strokePath,
                        color = lineColor,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Draw point at latest measurement
                    points.lastOrNull()?.let { lastPoint ->
                        drawCircle(
                            color = NavyCard,
                            radius = 6.dp.toPx(),
                            center = lastPoint
                        )
                        drawCircle(
                            color = lineColor,
                            radius = 4.dp.toPx(),
                            center = lastPoint
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(Locale.US, "Min: %.2f", dataMin),
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${values.size} windows",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Text(
                        text = String.format(Locale.US, "Max: %.2f", dataMax),
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
