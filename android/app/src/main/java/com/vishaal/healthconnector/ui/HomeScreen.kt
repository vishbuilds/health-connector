package com.vishaal.healthconnector.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vishaal.healthconnector.R
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.logic.LogTarget
import com.vishaal.healthconnector.logic.LogTargets
import com.vishaal.healthconnector.logic.copyLogPrompt
import com.vishaal.healthconnector.logic.openClaude
import com.vishaal.healthconnector.network.HomeApi
import com.vishaal.healthconnector.network.HomeLever
import com.vishaal.healthconnector.network.HomeRecoveryMetric
import com.vishaal.healthconnector.network.HomeRecoveryScore
import com.vishaal.healthconnector.network.HomeResult
import com.vishaal.healthconnector.network.HomeSummary
import com.vishaal.healthconnector.network.HomeWeight
import com.vishaal.healthconnector.network.ScorePoint
import com.vishaal.healthconnector.ui.theme.AppButton
import com.vishaal.healthconnector.ui.theme.AppCard
import com.vishaal.healthconnector.ui.theme.AppIcon
import com.vishaal.healthconnector.ui.theme.AppIconKind
import com.vishaal.healthconnector.ui.theme.AppText
import com.vishaal.healthconnector.ui.theme.HealthTheme
import com.vishaal.healthconnector.ui.theme.IconButton
import com.vishaal.healthconnector.ui.theme.ProgressBar
import com.vishaal.healthconnector.ui.theme.SkeletonBox
import com.vishaal.healthconnector.ui.theme.Sparkline
import com.vishaal.healthconnector.ui.theme.TextButton
import androidx.compose.ui.tooling.preview.Preview
import com.vishaal.healthconnector.network.HomeDayStatus
import com.vishaal.healthconnector.network.WeightPoint
import com.vishaal.healthconnector.ui.theme.AppSurface
import com.vishaal.healthconnector.ui.theme.HealthConnectorTheme
import com.vishaal.healthconnector.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

/** How many days back the day-swipe pager reaches (matches the server's 30-day history window). */
private const val DAYS_WINDOW = 30

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE, MMM d")
private val AXIS_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d")

private sealed class HomeUiState {
    object Loading : HomeUiState()
    object Empty : HomeUiState()
    data class Error(val message: String) : HomeUiState()
    data class Loaded(val summary: HomeSummary) : HomeUiState()
}

@Composable
fun HomeScreen(onOpenMenu: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val today = remember { LocalDate.now() }
    // Per-day cache + per-day reload counter, hoisted so paging away and back keeps loaded data.
    val cache = remember { mutableStateMapOf<String, HomeUiState>() }
    val reloadKeys = remember { mutableStateMapOf<String, Int>() }
    var notice by remember { mutableStateOf<String?>(null) }

    // Today is the rightmost page; swiping right reveals older days.
    val pagerState = rememberPagerState(initialPage = DAYS_WINDOW - 1, pageCount = { DAYS_WINDOW })
    fun dateForPage(page: Int): LocalDate = today.minusDays((DAYS_WINDOW - 1 - page).toLong())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HealthTheme.colors.background)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        val currentDate = dateForPage(pagerState.currentPage)
        HomeTopBar(
            date = currentDate,
            today = today,
            canGoOlder = pagerState.currentPage > 0,
            canGoNewer = pagerState.currentPage < DAYS_WINDOW - 1,
            onOlder = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
            onNewer = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            onOpenMenu = onOpenMenu,
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 1,
        ) { page ->
            val date = dateForPage(page)
            val key = date.toString()
            val isToday = date == today

            LaunchedEffect(key, reloadKeys[key]) {
                if (cache[key] is HomeUiState.Loaded) return@LaunchedEffect
                cache[key] = HomeUiState.Loading
                cache[key] = fetchDay(context, date, isToday)
            }

            AnimatedContent(
                targetState = cache[key] ?: HomeUiState.Loading,
                label = "home-day-$key",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    HomeUiState.Loading -> HomeSkeleton()
                    HomeUiState.Empty -> HomeEmpty(
                        onOpenMenu = onOpenMenu,
                        onRetry = { reloadKeys[key] = (reloadKeys[key] ?: 0) + 1 },
                    )
                    is HomeUiState.Error -> HomeError(
                        message = current.message,
                        onRetry = { reloadKeys[key] = (reloadKeys[key] ?: 0) + 1 },
                    )
                    is HomeUiState.Loaded -> HomeLoaded(
                        summary = current.summary,
                        notice = notice.takeIf { isToday },
                        onLogWithClaude = { prompt ->
                            copyLogPrompt(context, prompt)
                            openClaude(context, prompt)
                            notice = "Prompt copied. Paste it into Claude when it opens."
                        },
                    )
                }
            }
        }
    }
}

private suspend fun fetchDay(
    context: android.content.Context,
    date: LocalDate,
    isToday: Boolean,
): HomeUiState {
    val dateParam = if (isToday) null else date.toString()
    return when (val result = HomeApi(SettingsStore(context)).fetchHome(dateParam)) {
        is HomeResult.Success -> HomeUiState.Loaded(result.summary)
        is HomeResult.HttpError -> HomeUiState.Error("Home request failed (${result.httpCode}).")
        is HomeResult.NetworkError -> HomeUiState.Error(result.cause.userFacingMessage())
        HomeResult.NotConfigured -> HomeUiState.Empty
    }
}

@Composable
private fun HomeTopBar(
    date: LocalDate,
    today: LocalDate,
    canGoOlder: Boolean,
    canGoNewer: Boolean,
    onOlder: () -> Unit,
    onNewer: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ghost variant: the day-switch arrows recede so the date + content lead.
        IconButton(
            icon = AppIconKind.BACK,
            onClick = onOlder,
            enabled = canGoOlder,
            ghost = true,
            contentDescription = "Previous day",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    icon = AppIconKind.CALENDAR,
                    tint = HealthTheme.colors.muted,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp),
                )
                AppText(
                    text = dayLabel(date, today),
                    style = HealthTheme.type.title,
                    color = HealthTheme.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppText(
                text = if (date == today) "Paced to the time of day" else "Full-day recap",
                style = HealthTheme.type.small,
                color = HealthTheme.colors.muted,
                maxLines = 1,
            )
        }
        IconButton(
            icon = AppIconKind.CHEVRON,
            onClick = onNewer,
            enabled = canGoNewer,
            ghost = true,
            contentDescription = "Next day",
        )
        IconButton(
            icon = AppIconKind.MENU,
            onClick = onOpenMenu,
            modifier = Modifier.padding(start = 6.dp),
            contentDescription = "Open menu",
        )
    }
}

@Composable
private fun HomeLoaded(
    summary: HomeSummary,
    notice: String? = null,
    onLogWithClaude: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        WeightHeroCard(weight = summary.weight, objective = summary.objective)

        // Four supporting levers as a light 2x2 grid — neutral cards, accent only on icon + progress.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            summary.levers.chunked(2).forEach { rowLevers ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowLevers.forEach { lever ->
                        LeverTile(lever = lever, modifier = Modifier.weight(1f))
                    }
                    if (rowLevers.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        val logTarget = LogTargets.forSummary(summary)
        HomeActionCard(logTarget = logTarget, onLogWithClaude = onLogWithClaude)

        if (notice != null) {
            AppCard(background = HealthTheme.colors.blueSoft) {
                AppText(text = notice, style = HealthTheme.type.body, color = HealthTheme.colors.ink)
            }
        }

        RecoveryScoreCard(recovery = summary.recovery)

        InsightList(insights = summary.insights.take(3))

        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** The dominant card: the bodyweight trend — the actual fat-loss scoreboard. */
@Composable
private fun WeightHeroCard(
    weight: HomeWeight,
    objective: String,
) {
    val statusColor = colorForStatus(weight.status)
    AppCard(modifier = Modifier.fillMaxWidth(), background = HealthTheme.colors.surface) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(text = objective, style = HealthTheme.type.subtitle, modifier = Modifier.weight(1f))
                // Status by accent color, not a badge.
                AppText(text = weight.label, style = HealthTheme.type.label, color = statusColor, maxLines = 1)
            }

            if (weight.current == null) {
                AppText(
                    text = weight.detail.ifBlank { "Log a weigh-in to start your trend." },
                    style = HealthTheme.type.body,
                    color = HealthTheme.colors.muted,
                )
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    AppText(text = fmt1(weight.current), style = HealthTheme.type.display)
                    AppText(
                        text = " ${weight.unit}",
                        style = HealthTheme.type.subtitle,
                        color = HealthTheme.colors.muted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (weight.bodyFatPct != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            AppText(text = "${fmt1(weight.bodyFatPct)}%", style = HealthTheme.type.subtitle)
                            AppText(text = "body fat", style = HealthTheme.type.small, color = HealthTheme.colors.muted)
                        }
                    }
                }

                TrendSparklineWithAxis(
                    values = weight.series.map { it.value.toFloat() },
                    dates = weight.series.map { it.date },
                    color = statusColor,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(
                        icon = trendIcon(weight.changePerWeek),
                        tint = statusColor,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(18.dp),
                    )
                    AppText(
                        text = changeText(weight.changePerWeek),
                        style = HealthTheme.type.label,
                        color = HealthTheme.colors.ink,
                    )
                    if (weight.toGo != null && weight.toGo > 0) {
                        AppText(
                            text = "  ·  ${fmt1(weight.toGo)} ${weight.unit} to goal",
                            style = HealthTheme.type.small,
                            color = HealthTheme.colors.muted,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** A compact supporting-lever tile: value, thin progress, goal context. Neutral card; accent = status. */
@Composable
private fun LeverTile(
    lever: HomeLever,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = lever.score.coerceIn(0, 100) / 100f,
        animationSpec = tween(durationMillis = 500),
        label = "${lever.key}-score",
    )
    val statusColor = colorForStatus(lever.status)
    val (big, sub) = leverDisplay(lever)

    AppCard(
        modifier = modifier.aspectRatio(1.12f),
        background = HealthTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    icon = iconForLever(lever.key),
                    tint = statusColor,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(18.dp),
                )
                AppText(
                    text = lever.title,
                    style = HealthTheme.type.subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(
                    text = big,
                    style = HealthTheme.type.title.copy(fontWeight = FontWeight.Black),
                    maxLines = 1,
                )
                AppText(text = sub, style = HealthTheme.type.small, color = HealthTheme.colors.muted, maxLines = 1)
            }

            ProgressBar(progress = animatedProgress, color = statusColor)
        }
    }
}

@Composable
private fun RecoveryScoreCard(recovery: HomeRecoveryScore) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = colorForStatus(recovery.status)

    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        background = HealthTheme.colors.surface,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    icon = AppIconKind.HEART,
                    tint = statusColor,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(20.dp),
                )
                AppText(text = "Recovery score", style = HealthTheme.type.subtitle, modifier = Modifier.weight(1f))
                AppText(text = recovery.label, style = HealthTheme.type.label, color = statusColor, maxLines = 1)
                AppIcon(
                    icon = AppIconKind.CHEVRON,
                    tint = HealthTheme.colors.muted,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(18.dp),
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                AppText(text = recovery.score.toString(), style = HealthTheme.type.display)
                AppText(
                    text = " / 100",
                    style = HealthTheme.type.subtitle,
                    color = HealthTheme.colors.muted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                AppText(
                    text = "Readiness · Sleep · Stress",
                    style = HealthTheme.type.small,
                    color = HealthTheme.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            TrendSparklineWithAxis(
                values = recovery.series.map { it.value.coerceIn(0, 100).toFloat() },
                dates = recovery.series.map { it.date },
                color = statusColor,
            )

            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    recovery.metrics.forEach { metric ->
                        RecoveryMetricRow(metric = metric)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendSparklineWithAxis(
    values: List<Float>,
    dates: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Sparkline(
            values = values,
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        )
        MinimalXAxis(
            start = dates.firstOrNull()?.let(::axisDateLabel),
            end = dates.lastOrNull()?.let(::axisDateLabel),
        )
    }
}

@Composable
private fun MinimalXAxis(
    start: String?,
    end: String?,
) {
    if (start == null && end == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppText(
            text = start.orEmpty(),
            style = HealthTheme.type.small,
            color = HealthTheme.colors.muted,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(HealthTheme.colors.border),
        )
        AppText(
            text = end.orEmpty(),
            style = HealthTheme.type.small,
            color = HealthTheme.colors.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

private fun axisDateLabel(date: String): String =
    runCatching { LocalDate.parse(date).format(AXIS_DATE_FORMAT) }.getOrDefault(date)

@Composable
private fun RecoveryMetricRow(metric: HomeRecoveryMetric) {
    val color = colorForMetric(metric)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(
            icon = iconForMetric(metric.key),
            tint = color,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(text = metric.title, style = HealthTheme.type.label, modifier = Modifier.weight(1f))
                AppText(text = metricValue(metric), style = HealthTheme.type.label, color = HealthTheme.colors.ink)
            }
            Sparkline(
                values = metric.series.map { it.value.toFloat() },
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(
                    progress = metric.score.coerceIn(0, 100) / 100f,
                    color = color,
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                )
                AppText(
                    text = metric.label,
                    style = HealthTheme.type.small,
                    color = colorForStatus(metric.status),
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    logTarget: LogTarget?,
    onLogWithClaude: (String) -> Unit,
) {
    val statusColor = if (logTarget == null) HealthTheme.colors.primary else HealthTheme.colors.yellow
    val title = logTarget?.heading ?: "Home action"
    val detail = logTarget?.reason ?: "No manual logs needed right now."
    val icon = when (logTarget?.key) {
        "weight" -> null
        "food" -> AppIconKind.PROTEIN
        null -> AppIconKind.CHECK
        else -> AppIconKind.TREND
    }

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        background = HealthTheme.colors.surface,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    AppIcon(
                        icon = icon,
                        tint = statusColor,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(20.dp),
                    )
                }
                AppText(text = title, style = HealthTheme.type.subtitle, modifier = Modifier.weight(1f))
                AppText(
                    text = if (logTarget == null) "Clear" else "Pending",
                    style = HealthTheme.type.label,
                    color = statusColor,
                    maxLines = 1,
                )
            }

            AppText(text = detail, style = HealthTheme.type.body, color = HealthTheme.colors.ink)

            if (logTarget != null) {
                AppButton(
                    text = when (logTarget.key) {
                        "weight" -> "Log weight"
                        "food" -> "Log food"
                        else -> "Log"
                    },
                    onClick = { onLogWithClaude(logTarget.prompt) },
                    leadingIconRes = R.drawable.ic_claude,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun InsightList(insights: List<String>) {
    AppCard(modifier = Modifier.fillMaxWidth(), background = HealthTheme.colors.surfaceStrong) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(
                    icon = AppIconKind.INSIGHT,
                    tint = HealthTheme.colors.yellow,
                    modifier = Modifier.size(20.dp),
                )
                AppText(text = "Notes", style = HealthTheme.type.subtitle)
            }
            if (insights.isEmpty()) {
                AppText(text = "No notes yet. Sync again after new data lands.", color = HealthTheme.colors.muted)
            } else {
                insights.forEach { insight ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        AppIcon(
                            icon = AppIconKind.TREND,
                            tint = HealthTheme.colors.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(18.dp),
                        )
                        AppText(text = insight, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Skeleton that mirrors the loaded layout (weight hero, 2x2 lever grid, recovery score). */
@Composable
private fun HomeSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SkeletonBox(modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(20.dp))
                SkeletonBox(modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(34.dp))
                SkeletonBox(modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(2) {
                        AppCard(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1.12f),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                SkeletonBox(modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(18.dp))
                                SkeletonBox(modifier = Modifier
                                    .fillMaxWidth(0.5f)
                                    .height(24.dp))
                                SkeletonBox(modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp))
                            }
                        }
                    }
                }
            }
        }

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBox(modifier = Modifier.size(36.dp), shape = CircleShape)
                    Column(
                        modifier = Modifier.padding(start = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SkeletonBox(modifier = Modifier
                            .width(140.dp)
                            .height(18.dp))
                        SkeletonBox(modifier = Modifier
                            .width(80.dp)
                            .height(12.dp))
                    }
                }
                SkeletonBox(modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp), shape = RoundedCornerShape(14.dp))
            }
        }
    }
}

@Composable
private fun HomeEmpty(
    onOpenMenu: () -> Unit,
    onRetry: () -> Unit,
) {
    CenterMessage(
        title = "Setup is waiting",
        body = "Add your backend URL and token from Menu, then sync Health Connect.",
        primaryText = "Open menu",
        onPrimary = onOpenMenu,
        secondaryText = "Retry",
        onSecondary = onRetry,
    )
}

@Composable
private fun HomeError(
    message: String,
    onRetry: () -> Unit,
) {
    CenterMessage(
        title = "Scores unavailable",
        body = message,
        primaryText = "Retry",
        onPrimary = onRetry,
    )
}

@Composable
private fun CenterMessage(
    title: String,
    body: String,
    primaryText: String,
    onPrimary: () -> Unit,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .padding(top = 120.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppText(
                    text = title,
                    style = HealthTheme.type.title,
                    textAlign = TextAlign.Center,
                )
                AppText(
                    text = body,
                    color = HealthTheme.colors.muted,
                    textAlign = TextAlign.Center,
                )
                AppButton(text = primaryText, onClick = onPrimary, modifier = Modifier.fillMaxWidth())
                if (secondaryText != null && onSecondary != null) {
                    TextButton(text = secondaryText, onClick = onSecondary)
                }
            }
        }
    }
}

// --- Presentation helpers ----------------------------------------------------

/** Big value + goal-context sub-line for each lever tile, derived from the structured fields. */
private fun leverDisplay(lever: HomeLever): Pair<String, String> {
    val v = lever.value.roundToInt()
    val g = lever.goal?.roundToInt()
    return when (lever.key) {
        "protein" -> "${v}g" to (g?.let { "Goal $it g" } ?: "protein")
        "steps" -> grouped(v) to (g?.let { "Goal ${grouped(it)}" } ?: "steps")
        "training" -> "$v" to (g?.let { "Goal $it · this week" } ?: "this week")
        "energy_balance" -> signedKcal(v) to (g?.let { "Target ${signedKcal(it)} kcal" } ?: "Needs weight")
        "energy" -> grouped(v) to (g?.let { "Goal ${grouped(it)} kcal" } ?: "kcal")
        else -> "$v" to (g?.let { "Goal $it" } ?: "")
    }
}

private fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)

private fun signedKcal(n: Int): String = when {
    n < 0 -> "−${grouped(kotlin.math.abs(n))}"
    n > 0 -> "+${grouped(n)}"
    else -> "0"
}

private fun fmt1(d: Double): String = String.format(Locale.US, "%.1f", d)

/** kg/week change, formatted with an explicit sign; "Trend forming" when there's no fit yet. */
private fun changeText(changePerWeek: Double?): String {
    if (changePerWeek == null) return "Trend forming"
    val sign = if (changePerWeek <= 0) "−" else "+"
    return "$sign${fmt1(kotlin.math.abs(changePerWeek))} kg/wk"
}

private fun trendIcon(changePerWeek: Double?): AppIconKind = when {
    changePerWeek == null -> AppIconKind.TREND_FLAT
    changePerWeek <= -0.05 -> AppIconKind.TREND_DOWN
    changePerWeek >= 0.05 -> AppIconKind.TREND_UP
    else -> AppIconKind.TREND_FLAT
}

private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> date.format(DAY_FORMAT)
}

private fun iconForLever(key: String): AppIconKind = when (key) {
    "protein" -> AppIconKind.PROTEIN
    "training" -> AppIconKind.TRAINING
    "steps" -> AppIconKind.STEPS
    "energy_balance" -> AppIconKind.FLAME
    "energy" -> AppIconKind.FLAME
    else -> AppIconKind.TREND
}

private fun metricValue(metric: HomeRecoveryMetric): String = when (metric.unit) {
    "h" -> "${fmt1(metric.value)}h"
    "score" -> metric.score.toString()
    else -> if (metric.unit.isBlank()) fmt1(metric.value) else "${fmt1(metric.value)} ${metric.unit}"
}

private fun iconForMetric(key: String): AppIconKind = when (key) {
    "readiness" -> AppIconKind.HEART
    "sleep" -> AppIconKind.SLEEP
    "stress" -> AppIconKind.FLAME
    else -> AppIconKind.TREND
}

@Composable
private fun colorForMetric(metric: HomeRecoveryMetric): Color = when (metric.key) {
    "readiness" -> HealthTheme.colors.blue
    "sleep" -> HealthTheme.colors.primary
    "stress" -> HealthTheme.colors.yellow
    else -> colorForStatus(metric.status)
}

@Composable
private fun colorForStatus(status: String): Color = when (status) {
    "optimal" -> HealthTheme.colors.primary
    "good" -> HealthTheme.colors.blue
    "fair" -> HealthTheme.colors.yellow
    else -> HealthTheme.colors.red
}

private fun IOException.userFacingMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Network request failed."

// --- Compose previews --------------------------------------------------------
// Fake, self-contained data so the whole Home surface renders in Android Studio's Preview pane
// (no network, no device, no build). Edit a composable above and these re-render live. Covers the
// states worth eyeballing: on-track, stalled, trending up, no weigh-in yet — in light and dark.

/** A gently descending, slightly wavy weight series so the sparkline looks like real weigh-ins. */
private fun sampleSeries(from: Double, to: Double, n: Int = 16): List<WeightPoint> =
    (0 until n).map { i ->
        val t = i.toDouble() / (n - 1)
        val wave = sin(i * 0.9) * 0.15 // small day-to-day noise
        WeightPoint(date = "2026-06-%02d".format(i + 1), value = (from + (to - from) * t + wave))
    }

private fun sampleWeight(
    current: Double?,
    changePerWeek: Double?,
    score: Int,
    label: String,
    status: String,
    toGo: Double? = null,
): HomeWeight = HomeWeight(
    score = score,
    label = label,
    status = status,
    current = current,
    unit = "kg",
    target = 78.0,
    toGo = toGo,
    changePerWeek = changePerWeek,
    bodyFatPct = if (current == null) null else 18.2,
    series = if (current == null) emptyList() else sampleSeries(84.6, current),
    detail = if (current == null) "Log a weigh-in to start tracking" else "",
    action = "Fat loss is trending at a healthy pace. Hold this rhythm.",
)

private fun sampleLever(
    key: String,
    title: String,
    score: Int,
    label: String,
    status: String,
    value: Double,
    goal: Double?,
    unit: String?,
    period: String = "day",
): HomeLever = HomeLever(
    key = key,
    title = title,
    score = score,
    label = label,
    status = status,
    value = value,
    goal = goal,
    unit = unit,
    period = period,
    detail = "",
    action = "$title needs the next clear action.",
)

private fun sampleLevers(training: Int = 2): List<HomeLever> = listOf(
    sampleLever("protein", "Protein", 88, "On track", "optimal", 132.0, 150.0, "g"),
    sampleLever(
        "training", "Training",
        score = training * 25, label = "Halfway", status = if (training >= 3) "good" else "fair",
        value = training.toDouble(), goal = 4.0, unit = "sessions", period = "week",
    ),
    sampleLever("steps", "Steps", 74, "On pace", "good", 7420.0, 10000.0, null),
    sampleLever("energy_balance", "Calorie balance", 88, "On target", "optimal", -320.0, -440.0, "kcal"),
)

private fun sampleScoreSeries(from: Int, to: Int, n: Int = 14): List<ScorePoint> =
    (0 until n).map { i ->
        val t = i.toDouble() / (n - 1)
        val wave = (sin(i * 0.8) * 4).roundToInt()
        ScorePoint(date = "2026-06-%02d".format(i + 18), value = (from + ((to - from) * t)).roundToInt() + wave)
    }

private fun sampleRecoveryMetric(
    key: String,
    title: String,
    score: Int,
    label: String,
    status: String,
    value: Double,
    unit: String,
): HomeRecoveryMetric = HomeRecoveryMetric(
    key = key,
    title = title,
    score = score,
    label = label,
    status = status,
    value = value,
    unit = unit,
    series = sampleScoreSeries(score - 8, score),
    detail = "",
)

private fun sampleRecovery(score: Int = 82): HomeRecoveryScore = HomeRecoveryScore(
    score = score,
    label = "Steady",
    status = "good",
    series = sampleScoreSeries(76, score),
    metrics = listOf(
        sampleRecoveryMetric("readiness", "Readiness", 84, "Recovered", "good", 84.0, "score"),
        sampleRecoveryMetric("sleep", "Sleep", 78, "Enough", "good", 7.4, "h"),
        sampleRecoveryMetric("stress", "Stress", 83, "Balanced", "good", 83.0, "score"),
    ),
    detail = "Readiness, sleep, and stress balance over 14 days.",
)

private fun sampleSummary(weight: HomeWeight, levers: List<HomeLever>, insights: List<String>): HomeSummary =
    HomeSummary(
        date = "2026-07-06",
        isToday = true,
        objective = "Fat loss",
        overall = HomeDayStatus(score = 82, label = "On track", status = "optimal"),
        recovery = sampleRecovery(),
        weight = weight,
        levers = levers,
        insights = insights,
    )

@Composable
private fun PreviewShell(themeMode: ThemeMode, content: @Composable () -> Unit) {
    HealthConnectorTheme(themeMode = themeMode) {
        AppSurface {
            Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) { content() }
        }
    }
}

@Preview(name = "Home — on track", showBackground = true, heightDp = 1120, widthDp = 400)
@Composable
private fun HomeOnTrackPreview() {
    PreviewShell(ThemeMode.LIGHT) {
        HomeLoaded(
            summary = sampleSummary(
                weight = sampleWeight(82.3, -0.4, 100, "On track", "optimal", toGo = 4.3),
                levers = sampleLevers(training = 3),
                insights = listOf(
                    "On pace — losing 0.4 kg/wk, 4.3 kg to goal",
                    "18g protein to go — key to holding muscle",
                    "1 more session to hit 4 this week",
                ),
            ),
        )
    }
}

@Preview(name = "Home — stalled", showBackground = true, heightDp = 1120, widthDp = 400)
@Composable
private fun HomeStalledPreview() {
    PreviewShell(ThemeMode.LIGHT) {
        HomeLoaded(
            summary = sampleSummary(
                weight = sampleWeight(81.9, -0.02, 62, "Stalled", "fair", toGo = 3.9),
                levers = sampleLevers(training = 1),
                insights = listOf(
                    "Weight has stalled — trim ~200 kcal or add a daily walk",
                    "3 more sessions to hit 4 this week",
                ),
            ),
        )
    }
}

@Preview(name = "Home — dark", showBackground = true, heightDp = 1120, widthDp = 400)
@Composable
private fun HomeDarkPreview() {
    PreviewShell(ThemeMode.DARK) {
        HomeLoaded(
            summary = sampleSummary(
                weight = sampleWeight(82.3, -0.4, 100, "On track", "optimal", toGo = 4.3),
                levers = sampleLevers(training = 3),
                insights = listOf("On pace — losing 0.4 kg/wk, 4.3 kg to goal"),
            ),
        )
    }
}

@Preview(name = "Weight hero — no data", showBackground = true, widthDp = 400)
@Composable
private fun WeightHeroNoDataPreview() {
    PreviewShell(ThemeMode.LIGHT) {
        WeightHeroCard(
            weight = sampleWeight(null, null, 0, "No data", "attention"),
            objective = "Fat loss",
        )
    }
}

@Preview(name = "Skeleton", showBackground = true, heightDp = 700, widthDp = 400)
@Composable
private fun HomeSkeletonPreview() {
    PreviewShell(ThemeMode.LIGHT) { HomeSkeleton() }
}
