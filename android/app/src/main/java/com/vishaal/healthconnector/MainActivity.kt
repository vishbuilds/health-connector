package com.vishaal.healthconnector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.sync.WorkScheduler
import com.vishaal.healthconnector.ui.HomeScreen
import com.vishaal.healthconnector.ui.SetupScreen
import com.vishaal.healthconnector.ui.theme.HealthConnectorTheme
import com.vishaal.healthconnector.ui.theme.ThemeMode

private sealed class Destination(val route: String, val label: String) {
    object Home : Destination("home", "Home")
    object Setup : Destination("setup", "Setup")

    companion object {
        val all = listOf(Home, Setup)
    }
}

/**
 * Single launcher activity. Hosts Home plus Setup. Setup keeps the existing permissions/status/
 * settings surfaces together; when the permissions screen reports that every required Health
 * Connect permission is granted, this enqueues the sync workers through [WorkScheduler.enqueueAll].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore(this)
        setContent {
            val storedThemeMode by SettingsStore.themeModeFlow.collectAsState()
            HealthConnectorTheme(themeMode = ThemeMode.fromStored(storedThemeMode)) {
                Surface {
                    HealthConnectorApp()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HealthConnectorApp() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                Destination.all.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            val icon = when (destination) {
                                Destination.Home -> Icons.Filled.Home
                                Destination.Setup -> Icons.Filled.Settings
                            }
                            Icon(icon, contentDescription = destination.label)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Home.route) {
                HomeScreen()
            }
            composable(Destination.Setup.route) {
                SetupScreen(
                    onAllPermissionsGranted = {
                        WorkScheduler.enqueueAll(context)
                    },
                )
            }
        }
    }
}
