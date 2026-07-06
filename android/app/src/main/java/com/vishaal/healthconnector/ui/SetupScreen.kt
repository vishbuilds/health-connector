package com.vishaal.healthconnector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.ui.theme.AppText
import com.vishaal.healthconnector.ui.theme.HealthTheme
import com.vishaal.healthconnector.ui.theme.IconButton
import com.vishaal.healthconnector.ui.theme.AppIconKind
import com.vishaal.healthconnector.ui.theme.SegmentedControl
import com.vishaal.healthconnector.ui.theme.ThemeMode

private enum class SetupTab(val label: String) {
    STATUS("Status"),
    PERMISSIONS("Access"),
    SETTINGS("Backend"),
    APPEARANCE("Look"),
}

@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onAllPermissionsGranted: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                icon = AppIconKind.BACK,
                onClick = onBack,
                contentDescription = "Back",
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                AppText(text = "Setup", style = HealthTheme.type.title)
                AppText(
                    text = "Controls and details",
                    style = HealthTheme.type.small,
                    color = HealthTheme.colors.muted,
                )
            }
        }

        SegmentedControl(
            values = SetupTab.entries.map { it.label },
            selectedIndex = selectedTab,
            onSelected = { selectedTab = it },
            modifier = Modifier.fillMaxWidth(),
        )

        when (SetupTab.entries[selectedTab]) {
            SetupTab.STATUS -> StatusScreen()
            SetupTab.PERMISSIONS -> PermissionsScreen(onAllPermissionsGranted = onAllPermissionsGranted)
            SetupTab.SETTINGS -> SettingsScreen()
            SetupTab.APPEARANCE -> AppearanceScreen()
        }
    }
}

@Composable
private fun AppearanceScreen() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val storedThemeMode by SettingsStore.themeModeFlow.collectAsState()
    val selectedThemeMode = ThemeMode.fromStored(storedThemeMode)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText(text = "Appearance", style = HealthTheme.type.subtitle)
        AppText(
            text = "Choose the app brightness. Scores and status colors stay the same.",
            color = HealthTheme.colors.muted,
        )
        SegmentedControl(
            values = ThemeMode.entries.map { it.label() },
            selectedIndex = ThemeMode.entries.indexOf(selectedThemeMode),
            onSelected = { settingsStore.themeMode = ThemeMode.entries[it].name },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun ThemeMode.label(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
