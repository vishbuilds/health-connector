package com.vishaal.healthconnector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.permission.PermissionController
import com.vishaal.healthconnector.HealthConnectManager
import com.vishaal.healthconnector.RecordTypes

private val GRANTED_COLOR = Color(0xFF2E7D32)
private val NOT_GRANTED_COLOR = Color(0xFFC62828)

/**
 * Lets the user grant every Health Connect read permission this app needs, and shows a
 * granted/denied indicator per record type. Once every required permission is granted,
 * invokes [onAllPermissionsGranted] (the caller enqueues the sync workers at that point).
 */
@Composable
fun PermissionsScreen(onAllPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val requiredPermissions = remember { HealthConnectManager.requiredPermissions() }
    var grantedPermissions by remember { mutableStateOf(setOf<String>()) }

    val requestPermissions = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        grantedPermissions = granted
        if (granted.containsAll(requiredPermissions)) {
            onAllPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        val granted = HealthConnectManager.getGrantedPermissions(context)
        grantedPermissions = granted
        if (granted.containsAll(requiredPermissions)) {
            onAllPermissionsGranted()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Health Connect permissions")
        Text(
            text = "Health Connector needs read access to every Health Connect data type " +
                "below to sync it to your backend. Data never leaves your phone except to " +
                "the backend URL you configure in Settings.",
        )
        Button(
            onClick = { requestPermissions.launch(requiredPermissions) },
            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
        ) {
            Text("Grant permissions")
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(RecordTypes.ALL) { info ->
                val permission = HealthPermission.getReadPermission(info.kClass)
                val isGranted = permission in grantedPermissions
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(text = "${info.wireName}  (${info.category})")
                    Text(
                        text = if (isGranted) "Granted" else "Not granted",
                        color = if (isGranted) GRANTED_COLOR else NOT_GRANTED_COLOR,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
