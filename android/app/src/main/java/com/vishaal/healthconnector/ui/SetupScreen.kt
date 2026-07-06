package com.vishaal.healthconnector.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.ui.theme.ThemeMode

private enum class SetupTab(val label: String) {
    PERMISSIONS("Permissions"),
    STATUS("Status"),
    SETTINGS("Settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onAllPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val storedThemeMode by SettingsStore.themeModeFlow.collectAsState()
    val selectedThemeMode = ThemeMode.fromStored(storedThemeMode)
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = selectedThemeMode == mode,
                        onClick = { settingsStore.themeMode = mode.name },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                    ) {
                        Text(mode.label())
                    }
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            SetupTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(tab.label) },
                )
            }
        }

        when (SetupTab.entries[selectedTab]) {
            SetupTab.PERMISSIONS -> PermissionsScreen(onAllPermissionsGranted = onAllPermissionsGranted)
            SetupTab.STATUS -> StatusScreen()
            SetupTab.SETTINGS -> SettingsScreen()
        }
    }
}

private fun ThemeMode.label(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
