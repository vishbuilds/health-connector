package com.vishaal.healthconnector.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import com.vishaal.healthconnector.HealthConnectManager
import com.vishaal.healthconnector.HealthDataCategory
import com.vishaal.healthconnector.RecordTypes
import com.vishaal.healthconnector.ui.theme.AppButton
import com.vishaal.healthconnector.ui.theme.AppCard
import com.vishaal.healthconnector.ui.theme.AppText
import com.vishaal.healthconnector.ui.theme.HealthTheme
import com.vishaal.healthconnector.ui.theme.ProgressBar
import com.vishaal.healthconnector.ui.theme.StatusPill
import com.vishaal.healthconnector.ui.theme.StatusTone
import com.vishaal.healthconnector.ui.theme.TextButton

/**
 * Lets the user grant Health Connect permissions. Read permissions are required for sync; write
 * permissions are optional and only affect Claude-created records.
 */
@Composable
fun PermissionsScreen(onAllPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val requiredPermissions = remember { HealthConnectManager.requiredPermissions() }
    val readPermissions = remember { HealthConnectManager.readPermissions() }
    val writablePermissions = remember { HealthConnectManager.writePermissions() }
    var grantedPermissions by remember { mutableStateOf(setOf<String>()) }
    var showDetails by remember { mutableStateOf(false) }

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

    val readGranted = readPermissions.count { it in grantedPermissions }
    val readTotal = readPermissions.size
    val writeGranted = writablePermissions.count { it in grantedPermissions }
    val writeTotal = writablePermissions.size
    val readProgress = if (readTotal == 0) 0f else readGranted / readTotal.toFloat()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = if (readGranted == readTotal) "Read ready" else "Read access needed",
                        tone = if (readGranted == readTotal) StatusTone.GOOD else StatusTone.WARN,
                    )
                    StatusPill(text = "$writeGranted/$writeTotal writes", tone = StatusTone.INFO)
                }
                AppText(text = "Health Connect access", style = HealthTheme.type.subtitle)
                AppText(
                    text = "Read access powers scores and sync. Write access only affects Claude logging.",
                    color = HealthTheme.colors.muted,
                )
                ProgressBar(progress = readProgress)
                AppButton(
                    text = if (readGranted == readTotal) "Review permissions" else "Grant permissions",
                    onClick = { requestPermissions.launch(requiredPermissions) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        CategorySummary(grantedPermissions = grantedPermissions)

        TextButton(
            text = if (showDetails) "Hide record details" else "Show record details",
            onClick = { showDetails = !showDetails },
        )

        if (showDetails) {
            PermissionDetails(
                grantedPermissions = grantedPermissions,
                writablePermissions = writablePermissions,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CategorySummary(grantedPermissions: Set<String>) {
    AppCard(modifier = Modifier.fillMaxWidth(), background = HealthTheme.colors.surfaceStrong) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HealthDataCategory.entries.forEach { category ->
                val records = RecordTypes.ALL.filter { it.category == category }
                val granted = records.count {
                    HealthPermission.getReadPermission(it.kClass) in grantedPermissions
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        text = category.name.readableCategory(),
                        style = HealthTheme.type.label,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(
                        text = "$granted/${records.size}",
                        tone = if (granted == records.size) StatusTone.GOOD else StatusTone.WARN,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDetails(
    grantedPermissions: Set<String>,
    writablePermissions: Set<String>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(RecordTypes.ALL) { info ->
            val readGranted = HealthPermission.getReadPermission(info.kClass) in grantedPermissions
            val canWrite = HealthPermission.getWritePermission(info.kClass) in writablePermissions
            val writeGranted =
                canWrite && HealthPermission.getWritePermission(info.kClass) in grantedPermissions
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        text = info.wireName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(
                        text = if (readGranted) "Read" else "Missing",
                        tone = if (readGranted) StatusTone.GOOD else StatusTone.BAD,
                    )
                    if (canWrite) {
                        StatusPill(
                            text = if (writeGranted) "Write" else "No write",
                            tone = if (writeGranted) StatusTone.INFO else StatusTone.NEUTRAL,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HealthTheme.colors.border),
                )
            }
        }
    }
}

private fun String.readableCategory(): String =
    lowercase().split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
