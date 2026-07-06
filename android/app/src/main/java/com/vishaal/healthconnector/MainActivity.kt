package com.vishaal.healthconnector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.core.view.WindowCompat
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.logic.handleLogPromptIntent
import com.vishaal.healthconnector.sync.WorkScheduler
import com.vishaal.healthconnector.ui.HomeScreen
import com.vishaal.healthconnector.ui.MenuScreen
import com.vishaal.healthconnector.ui.SetupScreen
import com.vishaal.healthconnector.ui.theme.AppSurface
import com.vishaal.healthconnector.ui.theme.HealthConnectorTheme
import com.vishaal.healthconnector.ui.theme.ThemeMode

private object Routes {
    const val HOME = "home"
    const val MENU = "menu"
    const val SETUP = "setup"
}

/**
 * Single launcher activity. Home is the sole primary surface; Menu (and Setup within it) is reached
 * from the top-right menu button on Home and dismissed with its own back arrow — there is no bottom
 * tab bar, so the daily view fills the screen.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = SettingsStore(this)
        if (settingsStore.isConfigured()) {
            WorkScheduler.enqueueDailyLogReminders(this)
            maybeRequestNotificationPermission()
        }
        setContent {
            val storedThemeMode by SettingsStore.themeModeFlow.collectAsState()
            val themeMode = ThemeMode.fromStored(storedThemeMode)
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            SideEffect {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            HealthConnectorTheme(themeMode = themeMode) {
                AppSurface {
                    HealthConnectorApp()
                }
            }
        }
        handleLogPromptIntent(this, intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLogPromptIntent(this, intent)
    }

    fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 2001
    }
}

@Composable
private fun HealthConnectorApp() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        composable(Routes.HOME) {
            HomeScreen(onOpenMenu = { navController.navigateSingleTop(Routes.MENU) })
        }
        composable(Routes.MENU) {
            MenuScreen(
                onBack = { navController.popBackStack() },
                onOpenSetup = { navController.navigateSingleTop(Routes.SETUP) },
                onSyncNow = { WorkScheduler.enqueueExpeditedSyncNow(context) },
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                onBack = { navController.popBackStack() },
                onAllPermissionsGranted = {
                    WorkScheduler.enqueueAll(context)
                    (context as? MainActivity)?.maybeRequestNotificationPermission()
                },
            )
        }
    }
}

private fun androidx.navigation.NavController.navigateSingleTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
