package com.vishaal.healthconnector.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.RecordTypes
import com.vishaal.healthconnector.data.ChangesTokenStore
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.ui.theme.AppButton
import com.vishaal.healthconnector.ui.theme.AppCard
import com.vishaal.healthconnector.ui.theme.AppIcon
import com.vishaal.healthconnector.ui.theme.AppIconKind
import com.vishaal.healthconnector.ui.theme.AppText
import com.vishaal.healthconnector.ui.theme.HealthTheme
import com.vishaal.healthconnector.ui.theme.IconButton
import com.vishaal.healthconnector.ui.theme.StatusPill
import com.vishaal.healthconnector.ui.theme.StatusTone
import java.time.Duration
import java.time.Instant

@Composable
fun MenuScreen(
    onBack: () -> Unit,
    onOpenSetup: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val tokenStore = remember { ChangesTokenStore(context) }
    val wireNames = remember { RecordTypes.ALL.map { it.wireName } }

    val lastSyncMillis by tokenStore.lastSyncTimeMillisFlow().collectAsState(initial = null)
    val backfillStatus by tokenStore.backfillStatusFlow(wireNames).collectAsState(initial = emptyMap())

    val configured = settingsStore.isConfigured()
    val completed = backfillStatus.values.count { it }
    val total = wireNames.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                icon = AppIconKind.BACK,
                onClick = onBack,
                ghost = true,
                contentDescription = "Back to today",
            )
            AppText(
                text = "Menu",
                style = HealthTheme.type.display,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        AppText(
            text = "Connection controls and setup live here.",
            style = HealthTheme.type.body,
            color = HealthTheme.colors.muted,
        )

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = if (configured) "Backend ready" else "Backend needed",
                        tone = if (configured) StatusTone.GOOD else StatusTone.WARN,
                    )
                    StatusPill(
                        text = lastSyncMillis.lastSyncLabel(),
                        tone = if (lastSyncMillis == null) StatusTone.NEUTRAL else StatusTone.INFO,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                AppText(text = "Sync", style = HealthTheme.type.title)
                AppText(
                    text = "$completed of $total data types backfilled",
                    color = HealthTheme.colors.muted,
                )
                AppButton(
                    text = "Sync now",
                    onClick = onSyncNow,
                    modifier = Modifier.fillMaxWidth(),
                    secondary = true,
                )
            }
        }

        MenuRow(
            title = "Setup",
            body = "Permissions, backend, appearance, and detailed sync status.",
            status = if (configured) "Ready" else "Needs action",
            tone = if (configured) StatusTone.GOOD else StatusTone.WARN,
            onClick = onOpenSetup,
        )
    }
}

@Composable
private fun MenuRow(
    title: String,
    body: String,
    status: String,
    tone: StatusTone,
    onClick: () -> Unit,
) {
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText(text = title, style = HealthTheme.type.subtitle)
                    StatusPill(text = status, tone = tone)
                }
                AppText(
                    text = body,
                    color = HealthTheme.colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppIcon(
                icon = AppIconKind.CHEVRON,
                tint = HealthTheme.colors.muted,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

private fun Long?.lastSyncLabel(): String {
    if (this == null) return "Never synced"
    val elapsed = Duration.between(Instant.ofEpochMilli(this), Instant.now()).abs()
    return when {
        elapsed.toMinutes() < 1 -> "Synced now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()}h ago"
        else -> "${elapsed.toDays()}d ago"
    }
}
