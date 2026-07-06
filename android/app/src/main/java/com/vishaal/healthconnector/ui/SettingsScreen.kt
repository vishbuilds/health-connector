package com.vishaal.healthconnector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.ui.theme.AppButton
import com.vishaal.healthconnector.ui.theme.AppCard
import com.vishaal.healthconnector.ui.theme.AppText
import com.vishaal.healthconnector.ui.theme.AppTextField
import com.vishaal.healthconnector.ui.theme.HealthTheme
import com.vishaal.healthconnector.ui.theme.StatusPill
import com.vishaal.healthconnector.ui.theme.StatusTone

/**
 * Backend URL + bearer token configuration, persisted via encrypted shared preferences.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }

    var backendUrl by remember { mutableStateOf(settingsStore.backendUrl ?: "") }
    var bearerToken by remember { mutableStateOf(settingsStore.bearerToken ?: "") }
    var justSaved by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(
                    text = if (settingsStore.isConfigured()) "Backend ready" else "Backend needed",
                    tone = if (settingsStore.isConfigured()) StatusTone.GOOD else StatusTone.WARN,
                )
                AppText(text = "Backend", style = HealthTheme.type.subtitle)
                AppText(
                    text = "Scores come from your private server. These values stay encrypted on this phone.",
                    color = HealthTheme.colors.muted,
                )
                AppText(
                    text = "Device ID: ${settingsStore.deviceId}",
                    style = HealthTheme.type.small,
                    color = HealthTheme.colors.muted,
                )
            }
        }

        AppTextField(
            value = backendUrl,
            onValueChange = {
                backendUrl = it
                justSaved = false
            },
            label = "Backend URL",
            modifier = Modifier.fillMaxWidth(),
        )

        AppTextField(
            value = bearerToken,
            onValueChange = {
                bearerToken = it
                justSaved = false
            },
            label = "Bearer token",
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        AppButton(
            text = "Save backend",
            onClick = {
                settingsStore.backendUrl = backendUrl.trim()
                settingsStore.bearerToken = bearerToken.trim()
                justSaved = true
            },
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(),
        )

        if (justSaved) {
            StatusPill(text = "Saved", tone = StatusTone.GOOD)
        }
    }
}
