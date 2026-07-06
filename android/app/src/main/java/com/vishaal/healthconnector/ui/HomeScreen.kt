package com.vishaal.healthconnector.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.network.HomeApi
import com.vishaal.healthconnector.network.HomeMetric
import com.vishaal.healthconnector.network.HomeResult
import com.vishaal.healthconnector.network.HomeSummary
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToInt

private const val LOG_PROMPT = "Log the following for me: "
private const val CLAUDE_HOME_URL = "https://claude.ai"

private sealed class HomeUiState {
    object Loading : HomeUiState()
    object Empty : HomeUiState()
    data class Error(val message: String) : HomeUiState()
    data class Loaded(val summary: HomeSummary) : HomeUiState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }

    LaunchedEffect(reloadKey) {
        state = HomeUiState.Loading
        state = when (val result = HomeApi(SettingsStore(context)).fetchHome()) {
            is HomeResult.Success -> HomeUiState.Loaded(result.summary)
            is HomeResult.HttpError -> HomeUiState.Error("Home request failed (${result.httpCode}).")
            is HomeResult.NetworkError -> HomeUiState.Error(result.cause.userFacingMessage())
            HomeResult.NotConfigured -> HomeUiState.Empty
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Today") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        AnimatedContent(
            targetState = state,
            label = "home-state",
            modifier = Modifier.padding(innerPadding),
        ) { current ->
            when (current) {
                HomeUiState.Loading -> HomeLoading()
                HomeUiState.Empty -> HomeEmpty(onRetry = { reloadKey++ })
                is HomeUiState.Error -> HomeError(
                    message = current.message,
                    onRetry = { reloadKey++ },
                )
                is HomeUiState.Loaded -> HomeLoaded(
                    summary = current.summary,
                    onLogWithClaude = {
                        clipboard.setText(AnnotatedString(LOG_PROMPT))
                        openClaude(context, LOG_PROMPT)
                        scope.launch {
                            snackbarHostState.showSnackbar("Prompt copied - paste into Claude")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeLoaded(
    summary: HomeSummary,
    onLogWithClaude: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricRing(
                    label = "Steps",
                    metric = summary.steps,
                    valueText = summary.steps.value.formatWhole(),
                    goalText = summary.steps.goal.formatWhole(),
                    modifier = Modifier.weight(1f),
                )
                MetricRing(
                    label = "Sleep",
                    metric = summary.sleep,
                    valueText = summary.sleep.value.formatMinutes(),
                    goalText = summary.sleep.goal.formatMinutes(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricRing(
                    label = "Hydration",
                    metric = summary.hydration,
                    valueText = "${summary.hydration.value.formatOneDecimal()}L",
                    goalText = "${summary.hydration.goal.formatOneDecimal()}L",
                    modifier = Modifier.weight(1f),
                )
                MetricRing(
                    label = "Active cal",
                    metric = summary.activeCalories,
                    valueText = summary.activeCalories.value.formatWhole(),
                    goalText = summary.activeCalories.goal.formatWhole(),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        InsightsCard(insights = summary.insights)

        Button(
            onClick = onLogWithClaude,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Log with Claude")
        }
    }
}

@Composable
private fun MetricRing(
    label: String,
    metric: HomeMetric,
    valueText: String,
    goalText: String,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = metric.progress,
        animationSpec = tween(durationMillis = 650),
        label = "$label-progress",
    )

    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clipToBounds(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(82.dp),
                    strokeWidth = 8.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Text(
                        text = "of $goalText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = "${(metric.progress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsightsCard(insights: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Insights",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (insights.isEmpty()) {
                Text(
                    text = "No insights yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                insights.forEach { insight ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(18.dp),
                        )
                        Text(
                            text = insight,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("Loading today...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun HomeEmpty(onRetry: () -> Unit) {
    CenterMessage(
        title = "Connect your backend",
        body = "Set backend URL and token in Setup.",
        actionText = "Retry",
        onAction = onRetry,
    )
}

@Composable
private fun HomeError(
    message: String,
    onRetry: () -> Unit,
) {
    CenterMessage(
        title = "Home unavailable",
        body = message,
        actionText = "Retry",
        onAction = onRetry,
    )
}

@Composable
private fun CenterMessage(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onAction) {
                Text(actionText)
            }
        }
    }
}

private fun openClaude(
    context: android.content.Context,
    prompt: String,
) {
    val deepLink = "claude://claude.ai/new?q=" + Uri.encode(prompt)
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CLAUDE_HOME_URL)))
        } catch (_: ActivityNotFoundException) {
            // Clipboard fallback already makes the action useful.
        }
    }
}

private fun IOException.userFacingMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Network request failed."

private fun Double.formatWhole(): String = roundToInt().toString()

private fun Double.formatOneDecimal(): String {
    val rounded = (this * 10).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.roundToInt().toString() else rounded.toString()
}

private fun Double.formatMinutes(): String {
    val minutes = roundToInt()
    val hours = minutes / 60
    val remaining = minutes % 60
    return when {
        hours <= 0 -> "${remaining}m"
        remaining == 0 -> "${hours}h"
        else -> "${hours}h ${remaining}m"
    }
}
