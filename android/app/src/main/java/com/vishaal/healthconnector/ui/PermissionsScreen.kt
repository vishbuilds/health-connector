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
import androidx.health.connect.client.PermissionController
import com.vishaal.healthconnector.HealthConnectManager
import com.vishaal.healthconnector.RecordTypes

private val GRANTED_COLOR = Color(0xFF2E7D32)
private val NOT_GRANTED_COLOR = Color(0xFFC62828)

/**
 * Lets the user grant Health Connect permissions and shows a granted/denied indicator per record
 * type. It requests both READ permissions (required to sync) and WRITE permissions for the small
 * writable subset (optional — used only for records Claude writes back). Sync starts as soon as
 * the *read* permissions are granted (via [onAllPermissionsGranted]); declining a write permission
 * only disables writing that type, it doesn't block sync.
 */
@Composable
fun PermissionsScreen(onAllPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val requiredPermissions = remember { HealthConnectManager.requiredPermissions() }
    val readPermissions = remember { HealthConnectManager.readPermissions() }
    val writablePermissions = remember { HealthConnectManager.writePermissions() }
    var grantedPermissions by remember { mutableStateOf(setOf<String>()) }

    val requestPermissions = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        grantedPermissions = granted
        if (granted.containsAll(readPermissions)) {
            onAllPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        val granted = HealthConnectManager.getGrantedPermissions(context)
        grantedPermissions = granted
        if (granted.containsAll(readPermissions)) {
            onAllPermissionsGranted()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Health Connect permissions")
        Text(
            text = "Health Connector needs read access to every Health Connect data type below " +
                "to sync it to your backend. It also requests write access to a small subset " +
                "(shown as WRITE) so Claude can log new entries; declining those is fine and only " +
                "disables writing that type. Data never leaves your phone except to the backend " +
                "URL you configure in Settings.",
        )
        Button(
            onClick = { requestPermissions.launch(requiredPermissions) },
            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
        ) {
            Text("Grant permissions")
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(RecordTypes.ALL) { info ->
                val readGranted = HealthPermission.getReadPermission(info.kClass) in grantedPermissions
                val canWrite = HealthPermission.getWritePermission(info.kClass) in writablePermissions
                val writeGranted =
                    canWrite && HealthPermission.getWritePermission(info.kClass) in grantedPermissions
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(text = "${info.wireName}  (${info.category})")
                    Text(
                        text = "READ: " + if (readGranted) "Granted" else "Not granted",
                        color = if (readGranted) GRANTED_COLOR else NOT_GRANTED_COLOR,
                    )
                    if (canWrite) {
                        Text(
                            text = "WRITE: " + if (writeGranted) "Granted" else "Not granted",
                            color = if (writeGranted) GRANTED_COLOR else NOT_GRANTED_COLOR,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
