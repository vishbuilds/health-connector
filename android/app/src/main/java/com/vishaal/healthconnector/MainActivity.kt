package com.vishaal.healthconnector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vishaal.healthconnector.sync.WorkScheduler
import com.vishaal.healthconnector.ui.PermissionsScreen
import com.vishaal.healthconnector.ui.SettingsScreen
import com.vishaal.healthconnector.ui.StatusScreen

private sealed class Destination(val route: String, val label: String) {
    object Permissions : Destination("permissions", "Permissions")
    object Status : Destination("status", "Status")
    object Settings : Destination("settings", "Settings")

    companion object {
        val all = listOf(Permissions, Status, Settings)
    }
}

/**
 * Single launcher activity. Hosts a 3-tab Compose navigation graph:
 * Permissions -> Status -> Settings. When [PermissionsScreen] reports that every required
 * Health Connect permission is granted, this enqueues both sync workers
 * ([WorkScheduler.enqueueAll]) via [WorkScheduler.enqueueUniqueWork] semantics so re-entering
 * the permissions screen never double-enqueues them.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
                                Destination.Permissions -> Icons.Filled.Lock
                                Destination.Status -> Icons.Filled.CheckCircle
                                Destination.Settings -> Icons.Filled.Settings
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
            startDestination = Destination.Permissions.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Permissions.route) {
                PermissionsScreen(
                    onAllPermissionsGranted = {
                        WorkScheduler.enqueueAll(context)
                    },
                )
            }
            composable(Destination.Status.route) {
                StatusScreen()
            }
            composable(Destination.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
