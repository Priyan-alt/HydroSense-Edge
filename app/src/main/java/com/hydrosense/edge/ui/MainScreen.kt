package com.hydrosense.edge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydrosense.edge.ui.screens.DashboardScreen
import com.hydrosense.edge.ui.screens.DeviceDebugScreen
import com.hydrosense.edge.ui.screens.HistoryScreen
import com.hydrosense.edge.ui.screens.LiveSensorScreen
import com.hydrosense.edge.ui.theme.CyanPrimary
import com.hydrosense.edge.ui.theme.NavyBackground
import com.hydrosense.edge.ui.theme.NavyBorder
import com.hydrosense.edge.ui.theme.NavySurface
import com.hydrosense.edge.ui.theme.TextMuted
import com.hydrosense.edge.ui.viewmodel.HydrationViewModel

data class NavItem(
    val title: String,
    val icon: ImageVector
)

@Composable
fun MainScreen(
    viewModel: HydrationViewModel
) {
    var selectedIndex by remember { mutableIntStateOf(0) }

    val navItems = listOf(
        NavItem("Dashboard", Icons.Default.Dashboard),
        NavItem("Live Sensor", Icons.Default.ShowChart),
        NavItem("History", Icons.Default.History),
        NavItem("Device & BLE", Icons.Default.Bluetooth)
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = NavyBackground,
        bottomBar = {
            NavigationBar(
                containerColor = NavySurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .border(1.dp, NavyBorder, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                tonalElevation = 8.dp
            ) {
                navItems.forEachIndexed { index, item ->
                    val isSelected = selectedIndex == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedIndex = index },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = item.title,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanPrimary,
                            selectedTextColor = CyanPrimary,
                            indicatorColor = CyanPrimary.copy(alpha = 0.15f),
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(NavyBackground)
        ) {
            when (selectedIndex) {
                0 -> DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToDevice = { selectedIndex = 3 }
                )
                1 -> LiveSensorScreen(viewModel = viewModel)
                2 -> HistoryScreen(viewModel = viewModel)
                3 -> DeviceDebugScreen(viewModel = viewModel)
            }
        }
    }
}
