package com.vishaal.healthconnector.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.data.SettingsStore

/**
 * Backend URL + bearer token configuration, persisted via [SettingsStore]
 * (EncryptedSharedPreferences). Also displays the generated-once device id so the user can
 * correlate this install with what shows up on the backend.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }

    var backendUrl by remember { mutableStateOf(settingsStore.backendUrl ?: "") }
    var bearerToken by remember { mutableStateOf(settingsStore.bearerToken ?: "") }
    var justSaved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Backend configuration")
        Text(text = "Device ID: ${settingsStore.deviceId}")

        OutlinedTextField(
            value = backendUrl,
            onValueChange = {
                backendUrl = it
                justSaved = false
            },
            label = { Text("Backend URL (e.g. https://example.com)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        OutlinedTextField(
            value = bearerToken,
            onValueChange = {
                bearerToken = it
                justSaved = false
            },
            label = { Text("Bearer token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        Button(
            onClick = {
                settingsStore.backendUrl = backendUrl.trim()
                settingsStore.bearerToken = bearerToken.trim()
                justSaved = true
            },
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
        ) {
            Text("Save")
        }

        if (justSaved) {
            Text(text = "Saved.", modifier = Modifier.padding(top = 8.dp))
        }
    }
}
