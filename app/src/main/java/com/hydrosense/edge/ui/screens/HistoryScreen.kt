package com.hydrosense.edge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydrosense.edge.data.model.HydrationReading
import com.hydrosense.edge.data.model.HydrationStatus
import com.hydrosense.edge.ui.components.StatusBadge
import com.hydrosense.edge.ui.theme.CyanPrimary
import com.hydrosense.edge.ui.theme.DehydratedColor
import com.hydrosense.edge.ui.theme.HydratedColor
import com.hydrosense.edge.ui.theme.MetricIR
import com.hydrosense.edge.ui.theme.MetricMotion
import com.hydrosense.edge.ui.theme.NavyBorder
import com.hydrosense.edge.ui.theme.NavyCard
import com.hydrosense.edge.ui.theme.NavyCardElevated
import com.hydrosense.edge.ui.theme.TextMuted
import com.hydrosense.edge.ui.theme.TextPrimary
import com.hydrosense.edge.ui.theme.TextSecondary
import com.hydrosense.edge.ui.viewmodel.HydrationViewModel
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HydrationViewModel,
    modifier: Modifier = Modifier
) {
    val records by viewModel.historyRecords.collectAsState()
    val activeFilter by viewModel.historyFilter.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var selectedReadingDetail by remember { mutableStateOf<HydrationReading?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Hydration History",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${records.size} Logged Window Measurements",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            if (records.isNotEmpty()) {
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DehydratedColor)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Clear",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All", fontSize = 11.sp)
                }
            }
        }

        // Filter Chips Row
        val filters = listOf("ALL", "HYDRATED", "BORDERLINE", "DEHYDRATED")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters) { filter ->
                val isSelected = activeFilter == filter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isSelected) CyanPrimary else NavyCard)
                        .border(1.dp, if (isSelected) CyanPrimary else NavyBorder, RoundedCornerShape(20.dp))
                        .clickable { viewModel.setFilter(filter) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = filter,
                        color = if (isSelected) NavyCard else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // Summary Statistics Bar
        if (records.isNotEmpty()) {
            val validScores = records.mapNotNull { it.hiSmooth ?: it.hi }
            val avgScore = if (validScores.isNotEmpty()) validScores.average() else 0.0
            val hydratedCount = records.count { it.status == HydrationStatus.HYDRATED }
            val percentHydrated = (hydratedCount.toDouble() / records.size * 100).toInt()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(NavyCard)
                    .border(1.dp, NavyBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Average HI", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = String.format(Locale.US, "%.1f", avgScore),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Optimal Ratio", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = "$percentHydrated %",
                            color = HydratedColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Logged Records", color = TextMuted, fontSize = 11.sp)
                        Text(
                            text = "${records.size}",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // List of records
        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(NavyCard)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No hydration logs recorded yet",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Window measurements from your HydroSense band will be saved here automatically.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(records, key = { it.id }) { item ->
                    HistoryItemCard(
                        reading = item,
                        onClick = { selectedReadingDetail = item },
                        onDelete = { viewModel.deleteRecord(item.id) }
                    )
                }
            }
        }
    }

    // Detail Dialog when clicking a reading
    selectedReadingDetail?.let { r ->
        AlertDialog(
            onDismissRequest = { selectedReadingDetail = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Reading #${r.readingNumber}",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    StatusBadge(status = r.status)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Time: ${r.formattedDate}", color = TextMuted, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    DetailRow("Hydration Score", r.displayScore)
                    DetailRow("Perfusion Index", if (r.piComb != null) "${String.format(Locale.US, "%.2f", r.piComb)}%" else "--")
                    DetailRow("MAX30102 IR DC", if (r.dcIr != null) String.format(Locale.US, "%.0f", r.dcIr) else "--")
                    DetailRow("Signal Quality", if (r.sqi != null) "${(r.sqi * 100).toInt()}%" else "--")
                    DetailRow("IMU Motion", r.motion)
                    if (r.width != null) DetailRow("Pulse Width", "${String.format(Locale.US, "%.0f", r.width)} ms")
                    if (r.rise != null) DetailRow("Pulse Rise Time", "${String.format(Locale.US, "%.0f", r.rise)} ms")
                    if (r.area != null) DetailRow("Pulse Area", String.format(Locale.US, "%.1f", r.area))
                    if (r.ampCV != null) DetailRow("Amp CV", String.format(Locale.US, "%.3f", r.ampCV))
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedReadingDetail = null }) {
                    Text("Close", color = CyanPrimary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = NavyCardElevated,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All History?", color = TextPrimary) },
            text = {
                Text(
                    "This will permanently delete all saved hydration records from the local database.",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearHistory()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear All", color = DehydratedColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = NavyCardElevated
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = TextMuted, fontSize = 13.sp)
        Text(text = value, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
fun HistoryItemCard(
    reading: HydrationReading,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavyCard)
            .border(1.dp, NavyBorder, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = reading.formattedDate,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "HI Score: ${reading.displayScore}",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusBadge(status = reading.status)
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Telemetry chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Perfusion Index
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (reading.piComb != null) "PI: ${String.format(Locale.US, "%.2f%%", reading.piComb)}" else "PI: --",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // IR DC Level
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.LightMode,
                        contentDescription = null,
                        tint = MetricIR,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (reading.dcIr != null) "IR: ${String.format(Locale.US, "%.0f", reading.dcIr)}" else "IR: --",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // SQI
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = HydratedColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (reading.sqi != null) "SQI: ${(reading.sqi * 100).toInt()}%" else "SQI: --",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // Motion
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsRun,
                        contentDescription = null,
                        tint = MetricMotion,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = reading.motion,
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
