package com.vishaal.healthconnector.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.HealthDataCategory
import com.vishaal.healthconnector.RecordTypes
import com.vishaal.healthconnector.data.ChangesTokenStore
import com.vishaal.healthconnector.sync.WorkScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LAST_SYNC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

/**
 * Shows last successful sync time and, per record type, whether the initial backfill has
 * completed yet. The "Sync now" button enqueues an expedited one-time [SyncWorker] run
 * (via [WorkScheduler.enqueueExpeditedSyncNow]) using the exact same incremental-sync logic
 * as the periodic background job.
 */
@Composable
fun StatusScreen() {
    val context = LocalContext.current
    val tokenStore = remember { ChangesTokenStore(context) }
    val wireNames = remember { RecordTypes.ALL.map { it.wireName } }

    val lastSyncMillis by tokenStore.lastSyncTimeMillisFlow().collectAsState(initial = null)
    val backfillStatus by tokenStore.backfillStatusFlow(wireNames).collectAsState(initial = emptyMap())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Sync status", fontWeight = FontWeight.Bold)

        val lastSyncText = lastSyncMillis?.let { millis ->
            LAST_SYNC_FORMATTER.format(Instant.ofEpochMilli(millis))
        } ?: "Never"
        Text(text = "Last sync: $lastSyncText")

        Button(
            onClick = { WorkScheduler.enqueueExpeditedSyncNow(context) },
            modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
        ) {
            Text("Sync now")
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            HealthDataCategory.entries.forEach { category ->
                val typesInCategory = RecordTypes.ALL.filter { it.category == category }
                if (typesInCategory.isEmpty()) return@forEach

                item(key = "header_${category.name}") {
                    Text(
                        text = category.name.replace('_', ' '),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                }
                items(items = typesInCategory, key = { it.wireName }) { info ->
                    val completed = backfillStatus[info.wireName] ?: false
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(text = info.wireName)
                        Text(text = if (completed) "Backfill complete" else "Backfill pending")
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
