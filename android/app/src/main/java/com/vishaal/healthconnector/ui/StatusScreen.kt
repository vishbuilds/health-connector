package com.vishaal.healthconnector.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.HealthDataCategory
import com.vishaal.healthconnector.RecordTypes
import com.vishaal.healthconnector.data.ChangesTokenStore
import com.vishaal.healthconnector.sync.WorkScheduler
import com.vishaal.healthconnector.ui.theme.AppButton
import com.vishaal.healthconnector.ui.theme.AppCard
import com.vishaal.healthconnector.ui.theme.AppText
import com.vishaal.healthconnector.ui.theme.HealthTheme
import com.vishaal.healthconnector.ui.theme.ProgressBar
import com.vishaal.healthconnector.ui.theme.StatusPill
import com.vishaal.healthconnector.ui.theme.StatusTone
import com.vishaal.healthconnector.ui.theme.TextButton
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LAST_SYNC_FORMATTER = DateTimeFormatter.ofPattern("MMM d, h:mm a")
    .withZone(ZoneId.systemDefault())

/**
 * Shows sync freshness and backfill completion. Raw record-level state is available on demand,
 * but the default view stays action-first.
 */
@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val tokenStore = remember { ChangesTokenStore(context) }
    val wireNames = remember { RecordTypes.ALL.map { it.wireName } }
    var showDetails by remember { mutableStateOf(false) }

    val lastSyncMillis by tokenStore.lastSyncTimeMillisFlow().collectAsState(initial = null)
    val backfillStatus by tokenStore.backfillStatusFlow(wireNames).collectAsState(initial = emptyMap())

    val completed = backfillStatus.values.count { it }
    val total = wireNames.size
    val progress = if (total == 0) 0f else completed / total.toFloat()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = lastSyncMillis.syncFreshnessLabel(),
                        tone = lastSyncMillis.syncTone(),
                    )
                    StatusPill(
                        text = "$completed/$total backfilled",
                        tone = if (completed == total && total > 0) StatusTone.GOOD else StatusTone.INFO,
                    )
                }
                AppText(text = "Sync status", style = HealthTheme.type.subtitle)
                AppText(
                    text = lastSyncMillis?.let { "Last sync ${LAST_SYNC_FORMATTER.format(Instant.ofEpochMilli(it))}" }
                        ?: "No successful sync yet.",
                    color = HealthTheme.colors.muted,
                )
                ProgressBar(progress = progress)
                AppButton(
                    text = "Sync now",
                    onClick = { WorkScheduler.enqueueExpeditedSyncNow(context) },
                    modifier = Modifier.fillMaxWidth(),
                    secondary = true,
                )
            }
        }

        TextButton(
            text = if (showDetails) "Hide sync details" else "Show sync details",
            onClick = { showDetails = !showDetails },
        )

        if (showDetails) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                HealthDataCategory.entries.forEach { category ->
                    val typesInCategory = RecordTypes.ALL.filter { it.category == category }
                    if (typesInCategory.isEmpty()) return@forEach

                    item(key = "header_${category.name}") {
                        AppText(
                            text = category.name.readableCategory(),
                            style = HealthTheme.type.subtitle,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                    items(items = typesInCategory, key = { it.wireName }) { info ->
                        val completedType = backfillStatus[info.wireName] ?: false
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppText(
                                    text = info.wireName,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                StatusPill(
                                    text = if (completedType) "Done" else "Pending",
                                    tone = if (completedType) StatusTone.GOOD else StatusTone.NEUTRAL,
                                )
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
        }
    }
}

private fun Long?.syncFreshnessLabel(): String {
    if (this == null) return "Never synced"
    val elapsed = Duration.between(Instant.ofEpochMilli(this), Instant.now()).abs()
    return when {
        elapsed.toMinutes() < 15 -> "Fresh"
        elapsed.toHours() < 2 -> "${elapsed.toMinutes()}m old"
        elapsed.toDays() < 1 -> "${elapsed.toHours()}h old"
        else -> "${elapsed.toDays()}d old"
    }
}

private fun Long?.syncTone(): StatusTone {
    if (this == null) return StatusTone.WARN
    val elapsed = Duration.between(Instant.ofEpochMilli(this), Instant.now()).abs()
    return when {
        elapsed.toHours() < 2 -> StatusTone.GOOD
        elapsed.toDays() < 1 -> StatusTone.INFO
        else -> StatusTone.WARN
    }
}

private fun String.readableCategory(): String =
    lowercase().split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
