package com.vishaal.healthconnector.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vishaal.healthconnector.R
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.logic.LogTarget
import com.vishaal.healthconnector.logic.LogTargets
import com.vishaal.healthconnector.logic.copyLogPrompt
import com.vishaal.healthconnector.logic.openClaude
import com.vishaal.healthconnector.network.HomeApi
import com.vishaal.healthconnector.network.HomeLever
import com.vishaal.healthconnector.network.HomeLeverLineItem
import com.vishaal.healthconnector.network.HomeResult
import com.vishaal.healthconnector.network.HomeSummary
import com.vishaal.healthconnector.network.HomeWeight
import com.vishaal.healthconnector.ui.theme.AppButton
import com.vishaal.healthconnector.ui.theme.AppCard
import com.vishaal.healthconnector.ui.theme.AppIcon
import com.vishaal.healthconnector.ui.theme.AppIconKind
import com.vishaal.healthconnector.ui.theme.AppText
import com.vishaal.healthconnector.ui.theme.CelebrationEvent
import com.vishaal.healthconnector.ui.theme.CelebrationOverlay
import com.vishaal.healthconnector.ui.theme.HealthTheme
import com.vishaal.healthconnector.ui.theme.IconButton
import com.vishaal.healthconnector.ui.theme.LocalReducedMotion
import com.vishaal.healthconnector.ui.theme.Motion
import com.vishaal.healthconnector.ui.theme.ProgressBar
import com.vishaal.healthconnector.ui.theme.PullToRefresh
import com.vishaal.healthconnector.ui.theme.SkeletonBox
import com.vishaal.healthconnector.ui.theme.Sparkline
import com.vishaal.healthconnector.ui.theme.TextButton
import com.vishaal.healthconnector.ui.theme.animatedNumber
import com.vishaal.healthconnector.ui.theme.countUpNumber
import com.vishaal.healthconnector.ui.theme.enterOnce
import com.vishaal.healthconnector.ui.theme.pressScale
import androidx.compose.ui.tooling.preview.Preview
import com.vishaal.healthconnector.network.HomeDayStatus
import com.vishaal.healthconnector.network.WeightPoint
import com.vishaal.healthconnector.ui.theme.AppSurface
import com.vishaal.healthconnector.ui.theme.ambientBackground
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
    // The reload tick each day was last fetched at, so paging back to a loaded day never re-fetches
    // but an explicit reload/pull-to-refresh (which bumps the tick) always does.
    val handledTicks = remember { mutableStateMapOf<String, Int>() }
    val refreshingKeys = remember { mutableStateMapOf<String, Boolean>() }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedLeverKey by remember { mutableStateOf<String?>(null) }
    var celebration by remember { mutableStateOf<CelebrationEvent?>(null) }

    // Today is the rightmost page; swiping right reveals older days.
    val pagerState = rememberPagerState(initialPage = DAYS_WINDOW - 1, pageCount = { DAYS_WINDOW })
    fun dateForPage(page: Int): LocalDate = today.minusDays((DAYS_WINDOW - 1 - page).toLong())

    // Android system back: first collapse an open lever detail, then step back from an older day to
    // today — only falling through to the default (exit) once we're on today's daily view. Today is
    // the trailing (rightmost) page, so navigating "back" to it means scrolling to the last page.
    val todayPage = DAYS_WINDOW - 1
    BackHandler(enabled = selectedLeverKey != null) {
        selectedLeverKey = null
    }
    BackHandler(enabled = selectedLeverKey == null && pagerState.currentPage != todayPage) {
        scope.launch { pagerState.animateScrollToPage(todayPage) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .ambientBackground()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            HomeDayTabs(
                today = today,
                currentPage = pagerState.currentPage,
                pageCount = DAYS_WINDOW,
                dateForPage = { dateForPage(it) },
                onSelectPage = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
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
                    val tick = reloadKeys[key] ?: 0
                    val cachedLoaded = cache[key] as? HomeUiState.Loaded
                    // Already showing this day's data for this tick — nothing to do (paged back).
                    if (cachedLoaded != null && handledTicks[key] == tick) return@LaunchedEffect
                    // On a refresh we keep the current data on screen (the pull spinner carries the
                    // wait) instead of flashing a skeleton; a first/failed load shows the skeleton.
                    if (cachedLoaded == null) cache[key] = HomeUiState.Loading
                    val result = fetchDay(context, date, isToday)
                    if (cachedLoaded != null && result is HomeUiState.Loaded && isToday) {
                        improvementMessage(cachedLoaded.summary, result.summary)?.let {
                            celebration = CelebrationEvent(it)
                        }
                    }
                    cache[key] = result
                    handledTicks[key] = tick
                    refreshingKeys[key] = false
                }

                PullToRefresh(
                    isRefreshing = refreshingKeys[key] == true,
                    onRefresh = {
                        refreshingKeys[key] = true
                        reloadKeys[key] = (reloadKeys[key] ?: 0) + 1
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
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
                                selectedLeverKey = selectedLeverKey,
                                onSelectedLeverChange = { selectedLeverKey = it },
                                notice = notice.takeIf { isToday },
                                onOpenMenu = onOpenMenu,
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

        // A quiet, earned flourish when a refresh brings genuinely better news. One-shot, no streaks.
        CelebrationOverlay(
            event = celebration,
            onDone = { celebration = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Ranks a lever/day status so we can tell when a refresh has moved something into a better tier. */
private fun statusRank(status: String): Int = when (status) {
    "optimal" -> 3
    "good" -> 2
    "fair" -> 1
    else -> 0
}

/**
 * Returns a short congratulatory line when the refreshed data is genuinely better than what was on
 * screen — a lever crossing up into a strong tier, or a clear jump in the day score — and null
 * otherwise. This is the gate that keeps the celebration honest: no message, no flourish.
 */
private fun improvementMessage(old: HomeSummary, new: HomeSummary): String? {
    new.levers.forEach { lever ->
        val prev = old.levers.firstOrNull { it.key == lever.key } ?: return@forEach
        if (statusRank(lever.status) > statusRank(prev.status) && statusRank(lever.status) >= 2) {
            return "${lever.title} · ${lever.label}"
        }
    }
    if (new.overall.score >= old.overall.score + 3 && statusRank(new.overall.status) >= 2) {
        return "Day score up to ${new.overall.score}"
    }
    return null
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

/**
 * Day switcher as a horizontally scrollable tab strip. The selected day leads — full ink weight with a
 * short accent underline that slides between tabs — while the rest recede to muted, so the active day
 * is unmistakable without any chevrons. Tapping a tab animates the pager to that day, and the strip
 * keeps the selection in view whether it changed by tap or by swipe. Today sits at the trailing (right)
 * end, matching the pager where swiping right reveals older days.
 */
@Composable
private fun HomeDayTabs(
    today: LocalDate,
    currentPage: Int,
    pageCount: Int,
    dateForPage: (Int) -> LocalDate,
    onSelectPage: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    var initialized by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        // Half-viewport padding on each end lets even the first and last day scroll all the way to
        // the middle, so the active tab always settles dead-centre.
        val sidePadding = maxWidth / 2

        LaunchedEffect(currentPage) {
            // Instant on the very first pass (avoids a long scroll-across on open); animated after.
            centerTabOn(listState, currentPage, animate = initialized)
            initialized = true
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = sidePadding),
        ) {
            items(pageCount) { page ->
                DayTab(
                    label = dayLabel(dateForPage(page), today),
                    selected = page == currentPage,
                    onClick = { onSelectPage(page) },
                )
            }
        }
    }
}

/**
 * Scrolls [state] so the tab at [index] sits in the horizontal centre of the strip. Reads the item's
 * measured offset/size to land it precisely regardless of varying label widths; if the tab isn't laid
 * out yet it is first brought into view, then centred.
 */
private suspend fun centerTabOn(state: LazyListState, index: Int, animate: Boolean) {
    fun centeringDelta(): Float? {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
        return (item.offset + item.size / 2f) - viewportCenter
    }

    var delta = centeringDelta()
    if (delta == null) {
        // Not measured yet — jump near it so it becomes visible, then compute the exact offset.
        if (animate) state.animateScrollToItem(index) else state.scrollToItem(index)
        delta = centeringDelta() ?: return
    }
    if (animate) state.animateScrollBy(delta) else state.scrollBy(delta)
}

@Composable
private fun DayTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = HealthTheme.colors
    val contentColor by animateColorAsState(
        targetValue = if (selected) colors.ink else colors.muted,
        animationSpec = tween(durationMillis = Motion.Fast, easing = Motion.EaseOut),
        label = "day-tab-color",
    )
    val underlineWidth by animateDpAsState(
        targetValue = if (selected) 18.dp else 0.dp,
        animationSpec = Motion.standardSpring(),
        label = "day-tab-underline",
    )
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        AppText(
            text = label,
            style = HealthTheme.type.label,
            color = contentColor,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .height(2.5.dp)
                .width(underlineWidth)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.ink),
        )
    }
}

@Composable
private fun HomeLoaded(
    summary: HomeSummary,
    selectedLeverKey: String? = null,
    onSelectedLeverChange: (String?) -> Unit = {},
    notice: String? = null,
    onOpenMenu: () -> Unit = {},
    onLogWithClaude: (String) -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    val selectedLever = summary.levers.firstOrNull { it.key == selectedLeverKey }

    // Opening a lever pushes its detail in from the trailing edge; the back arrow reverses it — the
    // same "into a detail and back" spatial model as the top-level navigation.
    AnimatedContent(
        targetState = selectedLever,
        transitionSpec = {
            if (reduced) {
                fadeIn(tween(Motion.Fast)) togetherWith fadeOut(tween(Motion.Fast))
            } else {
                val dir = if (targetState != null) 1 else -1
                (slideInHorizontally(tween(Motion.Nav, easing = Motion.EaseDrawer)) { dir * it / 3 } +
                    fadeIn(tween(Motion.Nav))) togetherWith
                    (slideOutHorizontally(tween(Motion.Nav, easing = Motion.EaseDrawer)) { -dir * it / 6 } +
                        fadeOut(tween(Motion.Fast)))
            }
        },
        label = "lever-detail",
        modifier = Modifier.fillMaxSize(),
    ) { lever ->
        if (lever != null) {
            LeverDetailPage(lever = lever, onBack = { onSelectedLeverChange(null) })
        } else {
            HomeDailyContent(
                summary = summary,
                onSelectedLeverChange = onSelectedLeverChange,
                notice = notice,
                onOpenMenu = onOpenMenu,
                onLogWithClaude = onLogWithClaude,
            )
        }
    }
}

@Composable
private fun HomeDailyContent(
    summary: HomeSummary,
    onSelectedLeverChange: (String?) -> Unit,
    notice: String?,
    onOpenMenu: () -> Unit,
    onLogWithClaude: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        WeightHeroCard(
            weight = summary.weight,
            objective = summary.objective,
            modifier = Modifier.enterOnce(0),
        )

        // Supporting levers as a light grid — neutral cards, accent only on icon + progress. Each
        // tile lifts in a beat after the last so the grid cascades rather than snapping in at once.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            orderedLevers(summary.levers).chunked(2).forEachIndexed { rowIndex, rowLevers ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowLevers.forEachIndexed { colIndex, lever ->
                        LeverTile(
                            lever = lever,
                            selected = false,
                            onClick = { onSelectedLeverChange(lever.key) },
                            modifier = Modifier
                                .weight(1f)
                                .enterOnce(1 + rowIndex * 2 + colIndex),
                        )
                    }
                    if (rowLevers.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        val logTarget = LogTargets.forSummary(summary)
        HomeActionCard(
            logTarget = logTarget,
            onLogWithClaude = onLogWithClaude,
            modifier = Modifier.enterOnce(1 + summary.levers.size),
        )

        if (notice != null) {
            AppCard(
                modifier = Modifier.enterOnce(2 + summary.levers.size),
                background = HealthTheme.colors.blueSoft,
            ) {
                AppText(text = notice, style = HealthTheme.type.body, color = HealthTheme.colors.ink)
            }
        }

        HomeMenuFooter(onOpenMenu = onOpenMenu)

        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** The dominant card: the bodyweight trend — the actual fat-loss scoreboard. */
@Composable
private fun WeightHeroCard(
    weight: HomeWeight,
    objective: String,
    modifier: Modifier = Modifier,
) {
    val statusColor = colorForStatus(weight.status)
    AppCard(modifier = modifier.fillMaxWidth(), background = HealthTheme.colors.surface) {
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
                    // The hero figure eases to any new weigh-in rather than snapping (change-only —
                    // a bodyweight rolling up from zero would read as a gimmick).
                    val shownWeight by animatedNumber(weight.current.toFloat())
                    AppText(text = fmt1(shownWeight.toDouble()), style = HealthTheme.type.display)
                    AppText(
                        text = " ${weight.unit}",
                        style = HealthTheme.type.subtitle,
                        color = HealthTheme.colors.muted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (weight.bodyFatPct != null) {
                        val shownBodyFat by animatedNumber(weight.bodyFatPct.toFloat())
                        Column(horizontalAlignment = Alignment.End) {
                            AppText(text = "${fmt1(shownBodyFat.toDouble())}%", style = HealthTheme.type.subtitle)
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
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val effectiveScore = effectiveLeverScore(lever)
    val effectiveStatus = effectiveLeverStatus(lever)
    val animatedProgress by animateFloatAsState(
        targetValue = effectiveScore / 100f,
        animationSpec = tween(durationMillis = 500),
        label = "${lever.key}-score",
    )
    val statusColor = colorForStatus(effectiveStatus)
    // Tally values roll up as the tile arrives — the progress bar and figure fill together.
    val shownValue by countUpNumber(lever.value.toFloat())
    val big = formatLeverBig(lever, shownValue.roundToInt())
    val sub = leverSub(lever)
    val interaction = remember { MutableInteractionSource() }

    AppCard(
        modifier = modifier
            .aspectRatio(1.12f)
            .pressScale(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        background = HealthTheme.colors.surface,
        border = if (selected) statusColor else HealthTheme.colors.border,
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
                AppIcon(
                    icon = AppIconKind.CHEVRON,
                    tint = if (selected) statusColor else HealthTheme.colors.muted,
                    modifier = Modifier.size(16.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AppText(
                    text = big,
                    style = HealthTheme.type.title,
                    maxLines = 1,
                )
                AppText(text = sub, style = HealthTheme.type.small, color = HealthTheme.colors.muted, maxLines = 1)
            }

            ProgressBar(progress = animatedProgress, color = statusColor)
        }
    }
}

@Composable
private fun LeverDetailPage(
    lever: HomeLever,
    onBack: () -> Unit,
) {
    val effectiveScore = effectiveLeverScore(lever)
    val effectiveLabel = effectiveLeverLabel(lever)
    val effectiveStatus = effectiveLeverStatus(lever)
    val statusColor = colorForStatus(effectiveStatus)
    val lineItems = lever.lineItems.ifEmpty { fallbackLineItems(lever) }
    // Headline figure and score roll up as the detail pushes in, mirroring the tiles.
    val shownValue by countUpNumber(lever.value.toFloat())
    val big = formatLeverBig(lever, shownValue.roundToInt())
    val sub = leverSub(lever)
    val targetScore = forecastProgressScore(lever) ?: effectiveScore
    val progressStatus = forecastProgressStatus(lever) ?: effectiveStatus
    val progressColor = colorForStatus(progressStatus)
    val shownScore by countUpNumber(targetScore.toFloat())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                icon = AppIconKind.BACK,
                onClick = onBack,
                ghost = true,
                contentDescription = "Back to daily view",
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                AppText(text = lever.title, style = HealthTheme.type.title, maxLines = 1)
                AppText(
                    text = leverPeriodLabel(lever),
                    style = HealthTheme.type.small,
                    color = HealthTheme.colors.muted,
                    maxLines = 1,
                )
            }
            AppText(text = effectiveLabel, style = HealthTheme.type.label, color = statusColor, maxLines = 1)
        }

        AppCard(modifier = Modifier.fillMaxWidth(), background = HealthTheme.colors.surface) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(
                        icon = iconForLever(lever.key),
                        tint = statusColor,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(22.dp),
                    )
                    AppText(text = big, style = HealthTheme.type.display, modifier = Modifier.weight(1f))
                    AppText(
                        text = sub,
                        style = HealthTheme.type.small,
                        color = HealthTheme.colors.muted,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppText(
                        text = progressLabel(lever),
                        style = HealthTheme.type.label,
                        color = HealthTheme.colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    AppText(
                        text = progressValueLabel(lever, shownScore.roundToInt()),
                        style = HealthTheme.type.label,
                        color = progressColor,
                        maxLines = 1,
                    )
                }
                ProgressBar(
                    progress = shownScore / 100f,
                    color = progressColor,
                )
            }
        }

        if (lever.action.isNotBlank()) {
            AppCard(
                modifier = Modifier.fillMaxWidth(),
                background = HealthTheme.colors.surfaceStrong,
                border = HealthTheme.colors.border,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AppIcon(
                        icon = AppIconKind.INSIGHT,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp),
                    )
                    AppText(
                        text = lever.action,
                        style = HealthTheme.type.body,
                        color = HealthTheme.colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (lever.key == "energy_balance") {
            // Intake (food) keeps its by-meal grouping with timestamps; burns (active + basal) get
            // their own section with no time column — a basal estimate has no clock time to show.
            val (burned, intake) = lineItems.partition { calorieAmount(it.value) < 0 }
            LineItemsCard(items = intake)
            LineItemsCard(items = burned, showTime = false)
            CalorieTotalsCard(lineItems = lineItems)
        } else {
            LineItemsCard(items = lineItems)
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * A card of line items. By default it groups by time (with per-meal subtotals); [showTime] = false
 * renders a flat, time-less list — used for burns, where a basal estimate has no timestamp. An
 * optional [title] heads the section.
 */
@Composable
private fun LineItemsCard(
    items: List<HomeLeverLineItem>,
    title: String? = null,
    showTime: Boolean = true,
) {
    if (items.isEmpty()) return
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        background = HealthTheme.colors.surface,
        border = HealthTheme.colors.border,
    ) {
        val groups = if (showTime) {
            groupLineItemsByTime(items)
        } else {
            listOf(LineItemTimeGroup(time = null, items = items))
        }
        val showTimeColumn = showTime && groups.any { !it.time.isNullOrBlank() }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) {
                AppText(text = title, style = HealthTheme.type.subtitle)
            }
            groups.forEach { group ->
                LeverLineItemGroupRows(group = group, showTimeColumn = showTimeColumn)
            }
        }
    }
}

@Composable
private fun CalorieTotalsCard(lineItems: List<HomeLeverLineItem>) {
    val gained = lineItems.sumOf { item -> calorieAmount(item.value).coerceAtLeast(0) }
    val lost = lineItems.sumOf { item -> -calorieAmount(item.value).coerceAtMost(0) }
    val net = gained - lost

    // The totals tally up as the card arrives; colour tracks the final net, not the animated value.
    val shownGained by countUpNumber(gained.toFloat())
    val shownLost by countUpNumber(lost.toFloat())
    val shownNet by countUpNumber(net.toFloat())

    AppCard(
        modifier = Modifier.fillMaxWidth(),
        background = HealthTheme.colors.surfaceStrong,
        border = HealthTheme.colors.border,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppText(text = "Calorie totals", style = HealthTheme.type.subtitle)
            CalorieTotalRow(label = "Gained", value = grouped(shownGained.roundToInt()), color = HealthTheme.colors.ink)
            CalorieTotalRow(label = "Lost", value = grouped(shownLost.roundToInt()), color = HealthTheme.colors.ink)
            CalorieTotalRow(label = "Net", value = signedKcal(shownNet.roundToInt()), color = colorForBalance(net))
        }
    }
}

@Composable
private fun CalorieTotalRow(
    label: String,
    value: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppText(
            text = label,
            style = HealthTheme.type.body,
            color = HealthTheme.colors.muted,
            modifier = Modifier.weight(1f),
        )
        AppText(text = "$value kcal", style = HealthTheme.type.label, color = color, maxLines = 1)
    }
}

@Composable
private fun LeverLineItemGroupRows(
    group: LineItemTimeGroup,
    showTimeColumn: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (showTimeColumn) {
            AppText(
                text = group.time ?: "",
                style = HealthTheme.type.small,
                color = HealthTheme.colors.muted,
                modifier = Modifier.width(80.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.items.forEach { item ->
                LeverLineItemRow(item = item)
            }
            // A per-meal tally line — carries the calorie/protein sum and its rating icon
            // (cookie/star). Shown for every group, including single-item meals, so each food gets
            // its own verdict; only for figures we can parse (calories / protein) — steps-style
            // groups parse to nothing and get no subtotal.
            val totals = mealTotal(group.items)
            mealTotalLabel(totals)?.let { label ->
                MealTotalRow(text = label, total = totals, rating = mealRating(totals))
            }
        }
    }
}

/**
 * A subtle divider + right-aligned tally that closes out a time group's line items. When the meal
 * carries a [rating], a tappable verdict chip (star / scales / cookie) precedes the total and opens
 * [MealRatingDialog] to explain how the verdict was reached.
 */
@Composable
private fun MealTotalRow(text: String, total: MealTotal, rating: MealRating?) {
    var explain by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(HealthTheme.colors.border),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (rating != null) {
                RatingChip(rating = rating, onClick = { explain = true })
                Spacer(modifier = Modifier.width(8.dp))
            }
            AppText(
                text = text,
                style = HealthTheme.type.label,
                color = HealthTheme.colors.ink,
                maxLines = 1,
            )
        }
    }
    if (explain && rating != null) {
        MealRatingDialog(total = total, rating = rating, onDismiss = { explain = false })
    }
}

/** Visual identity for each meal verdict — icon, accent, soft chip fill, plain word, and band range. */
private data class RatingVisual(
    val icon: AppIconKind,
    val accent: Color,
    val soft: Color,
    val word: String,
    val band: String,
)

@Composable
private fun ratingVisual(rating: MealRating): RatingVisual {
    val c = HealthTheme.colors
    return when (rating) {
        // primaryDark (not primary) so the icon clears 3:1 against its soft-green chip in light mode.
        MealRating.GREAT -> RatingVisual(AppIconKind.STAR, c.primaryDark, c.primarySoft, "Protein-dense", "30% or more")
        MealRating.OK -> RatingVisual(AppIconKind.BALANCE, c.blue, c.blueSoft, "Balanced", "20–30%")
        MealRating.NAUGHTY -> RatingVisual(AppIconKind.COOKIE, c.red, c.redSoft, "Light on protein", "Under 20%")
    }
}

/** Tappable pill carrying a meal's verdict icon; opens the explanation dialog. */
@Composable
private fun RatingChip(rating: MealRating, onClick: () -> Unit) {
    val visual = ratingVisual(rating)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(visual.soft)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "Why this meal is rated ${visual.word}",
                onClick = onClick,
            )
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon = visual.icon, tint = visual.accent, modifier = Modifier.size(16.dp))
    }
}

/**
 * Explains a meal's protein verdict: the actual protein share of calories, the plain math behind it,
 * and where it lands across the three bands. Read-only; dismiss via the scrim or the button.
 */
@Composable
private fun MealRatingDialog(total: MealTotal, rating: MealRating, onDismiss: () -> Unit) {
    val visual = ratingVisual(rating)
    val proteinKcal = (total.proteinGrams * 4.0).roundToInt()
    val share = if (total.kcal > 0) (total.proteinGrams * 4.0 / total.kcal) else 0.0
    val sharePct = (share * 100).roundToInt()

    // A brief scale-and-fade in — state feedback, not decoration; skipped under reduced motion.
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(
        targetValue = if (shown || reduced) 1f else 0f,
        animationSpec = tween(Motion.Medium, easing = Motion.EaseOut),
        label = "ratingDialogIn",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    alpha = appear
                    val s = 0.94f + 0.06f * appear
                    scaleX = s
                    scaleY = s
                },
            background = HealthTheme.colors.surfaceStrong,
            border = HealthTheme.colors.border,
            padding = PaddingValues(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                // Header — verdict icon + word + the headline share.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier.size(46.dp).clip(CircleShape).background(visual.soft),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(icon = visual.icon, tint = visual.accent, modifier = Modifier.size(24.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        AppText(text = visual.word, style = HealthTheme.type.subtitle, color = HealthTheme.colors.ink)
                        AppText(
                            text = "$sharePct% of calories from protein",
                            style = HealthTheme.type.small,
                            color = HealthTheme.colors.muted,
                        )
                    }
                }

                // The plain-language math — actual figures, no false precision.
                AppText(
                    text = "This meal is ${grouped(total.kcal)} kcal with ${fmt1(total.proteinGrams)} g protein. " +
                        "Protein carries 4 kcal per gram, so that's $proteinKcal kcal — $sharePct% of the total. " +
                        "The more of a meal's energy that comes from protein, the better it supports a recomp.",
                    style = HealthTheme.type.body,
                    color = HealthTheme.colors.muted,
                )

                // Where this meal lands across the three bands (scale runs 0–40%).
                ProteinShareBar(share = share)

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RatingBandRow(MealRating.GREAT, active = rating == MealRating.GREAT)
                    RatingBandRow(MealRating.OK, active = rating == MealRating.OK)
                    RatingBandRow(MealRating.NAUGHTY, active = rating == MealRating.NAUGHTY)
                }

                AppButton(text = "Got it", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A three-zone track (low / balanced / high protein share) with a marker at [share]'s position.
 * The scale runs 0–40%, so the 20% and 30% thresholds fall exactly on the zone seams. Illustrative
 * of where the meal sits — the exact figure is stated in words above.
 */
@Composable
private fun ProteinShareBar(share: Double) {
    val c = HealthTheme.colors
    val trackHeight = 12.dp
    // Clamp a hair off each edge so the marker never clips at the extremes.
    val fraction = (share / 0.40).coerceIn(0.03, 0.97).toFloat()
    Box(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
        ) {
            Box(modifier = Modifier.weight(2f).height(trackHeight).background(c.redSoft))
            Box(modifier = Modifier.weight(1f).height(trackHeight).background(c.blueSoft))
            Box(modifier = Modifier.weight(1f).height(trackHeight).background(c.primarySoft))
        }
        // Marker — a slim ink pointer positioned via a fractional-width spacer.
        Box(modifier = Modifier.fillMaxWidth(fraction)) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.ink),
            )
        }
    }
}

/** One legend row for a band; [active] lights it (soft fill, full-ink text), the others recede. */
@Composable
private fun RatingBandRow(rating: MealRating, active: Boolean) {
    val visual = ratingVisual(rating)
    val c = HealthTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) visual.soft else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppIcon(
            icon = visual.icon,
            tint = if (active) visual.accent else c.muted,
            modifier = Modifier.size(16.dp),
        )
        AppText(
            text = visual.word,
            style = HealthTheme.type.label,
            color = if (active) c.ink else c.muted,
            modifier = Modifier.weight(1f),
        )
        AppText(
            text = visual.band,
            // ink (not accent) when active: accent-on-soft is only ~3:1, below the 4.5:1 text bar.
            style = HealthTheme.type.small,
            color = if (active) c.ink else c.muted,
        )
    }
}

@Composable
private fun LeverLineItemRow(
    item: HomeLeverLineItem,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AppText(
                text = item.label,
                style = HealthTheme.type.body,
                color = HealthTheme.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = item.value,
                style = HealthTheme.type.small,
                color = HealthTheme.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun HomeActionCard(
    logTarget: LogTarget?,
    onLogWithClaude: (String) -> Unit,
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth(),
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
private fun HomeMenuFooter(onOpenMenu: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            icon = AppIconKind.MENU,
            onClick = onOpenMenu,
            ghost = true,
            contentDescription = "Open menu",
        )
    }
}

/** Skeleton that mirrors the loaded layout (weight hero, three lever cards, action card). */
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
                        SkeletonLeverCard(modifier = Modifier.weight(1f))
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
private fun SkeletonLeverCard(modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier.aspectRatio(1.12f),
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

/**
 * Fixed display order for the supporting-lever grid, independent of the order the server returns:
 * calorie balance leads (the day's headline lever), then protein, training, steps. Any lever whose
 * key isn't listed keeps its original relative position after the known ones.
 */
private val LEVER_ORDER = listOf("energy_balance", "protein", "training", "steps")

private fun orderedLevers(levers: List<HomeLever>): List<HomeLever> =
    levers.sortedBy { lever ->
        LEVER_ORDER.indexOf(lever.key).let { if (it == -1) LEVER_ORDER.size + levers.indexOf(lever) else it }
    }

/** Big value + goal-context sub-line for each lever tile, derived from the structured fields. */
private fun leverDisplay(lever: HomeLever): Pair<String, String> =
    formatLeverBig(lever, lever.value.roundToInt()) to leverSub(lever)

/** Formats the lever's headline figure for an arbitrary [v], so a count-up can reuse the formatting. */
private fun formatLeverBig(lever: HomeLever, v: Int): String = when (lever.key) {
    "protein" -> "${v}g"
    "steps" -> grouped(v)
    "training" -> "$v"
    "energy_balance" -> signedKcal(v)
    "energy" -> grouped(v)
    else -> "$v"
}

/** The goal-context sub-line that sits under a lever's headline figure. */
private fun leverSub(lever: HomeLever): String {
    val g = lever.goal?.roundToInt()
    return when (lever.key) {
        "protein" -> g?.let { "Goal $it g" } ?: "protein"
        "steps" -> g?.let { "Goal ${grouped(it)}" } ?: "steps"
        "training" -> g?.let { "Goal $it · this week" } ?: "this week"
        "energy_balance" -> g?.let { "Target ${signedKcal(it)} kcal" } ?: "Needs weight"
        "energy" -> g?.let { "Goal ${grouped(it)} kcal" } ?: "kcal"
        else -> g?.let { "Goal $it" } ?: ""
    }
}

private fun fallbackLineItems(lever: HomeLever): List<HomeLeverLineItem> {
    val (big, sub) = leverDisplay(lever)
    return listOf(
        HomeLeverLineItem("Current", big),
        HomeLeverLineItem("Goal", sub),
    )
}

private data class LineItemTimeGroup(
    val time: String?,
    val items: List<HomeLeverLineItem>,
)

private fun groupLineItemsByTime(items: List<HomeLeverLineItem>): List<LineItemTimeGroup> {
    val groups = mutableListOf<LineItemTimeGroup>()
    val timeIndexes = mutableMapOf<String, Int>()

    items.forEach { item ->
        val time = item.time?.takeIf { it.isNotBlank() }
        if (time == null) {
            groups += LineItemTimeGroup(time = null, items = listOf(item))
        } else {
            val existingIndex = timeIndexes[time]
            if (existingIndex == null) {
                timeIndexes[time] = groups.size
                groups += LineItemTimeGroup(time = time, items = listOf(item))
            } else {
                val group = groups[existingIndex]
                groups[existingIndex] = group.copy(items = group.items + item)
            }
        }
    }

    return groups
}

private fun leverPeriodLabel(lever: HomeLever): String = when (lever.period) {
    "week" -> "This week"
    else -> "Daily view"
}

private fun grouped(n: Int): String = String.format(Locale.US, "%,d", n)

private fun signedKcal(n: Int): String = when {
    n < 0 -> "−${grouped(kotlin.math.abs(n))}"
    n > 0 -> "+${grouped(n)}"
    else -> "0"
}

private fun calorieAmount(value: String): Int {
    val match = Regex("""([+\-−])\s*([0-9][0-9,]*)\s*kcal""").find(value) ?: return 0
    val amount = match.groupValues[2].replace(",", "").toIntOrNull() ?: return 0
    return if (match.groupValues[1] == "+") amount else -amount
}

/** Running tally for a time group, tracking which figures were present so we only render real sums. */
private data class MealTotal(
    val kcal: Int,
    val proteinGrams: Double,
    val hasKcal: Boolean,
    val hasProtein: Boolean,
    val kcalSigned: Boolean,
)

// kcal accepts an optional sign (protein rows read "278 kcal", energy rows read "+278 kcal"); protein
// grams are a number immediately before a "g" token ("17.3g", "17.3 g protein").
private val KCAL_REGEX = Regex("""([+\-−]?)\s*([0-9][0-9,]*)\s*kcal""")
private val PROTEIN_GRAMS_REGEX = Regex("""([0-9]+(?:\.[0-9]+)?)\s*g\b""")

/** Parses and sums the calories and protein grams across a time group's line-item value strings. */
private fun mealTotal(items: List<HomeLeverLineItem>): MealTotal {
    var kcal = 0
    var proteinGrams = 0.0
    var hasKcal = false
    var hasProtein = false
    var kcalSigned = false
    items.forEach { item ->
        KCAL_REGEX.find(item.value)?.let { m ->
            val amount = m.groupValues[2].replace(",", "").toIntOrNull() ?: 0
            val sign = m.groupValues[1]
            kcal += if (sign == "-" || sign == "−") -amount else amount
            if (sign.isNotBlank()) kcalSigned = true
            hasKcal = true
        }
        PROTEIN_GRAMS_REGEX.find(item.value)?.let { m ->
            proteinGrams += m.groupValues[1].toDoubleOrNull() ?: 0.0
            hasProtein = true
        }
    }
    return MealTotal(kcal, proteinGrams, hasKcal, hasProtein, kcalSigned)
}

/**
 * How well a meal traded calories for protein. GREAT = protein-dense (star), NAUGHTY = a poor
 * trade-off (cookie), OK = balanced (scales). Judged on protein's share of the meal's calories
 * (protein is 4 kcal/g): ≥30% is great, <20% is naughty, in between is balanced.
 */
private enum class MealRating { GREAT, OK, NAUGHTY }

/** Minimum calories before we pass judgement — below this (black coffee, water, gum) isn't worth rating. */
private const val MEAL_JUDGE_MIN_KCAL = 50

private fun mealRating(total: MealTotal): MealRating? {
    if (!total.hasKcal || !total.hasProtein) return null
    if (total.kcal < MEAL_JUDGE_MIN_KCAL) return null
    val proteinShare = (total.proteinGrams * 4.0) / total.kcal
    return when {
        proteinShare >= 0.30 -> MealRating.GREAT
        proteinShare < 0.20 -> MealRating.NAUGHTY
        else -> MealRating.OK
    }
}

/** Formats a [MealTotal] as a sub-line ("+445 kcal · 25.4g protein"), or null when nothing summed. */
private fun mealTotalLabel(total: MealTotal): String? {
    val parts = mutableListOf<String>()
    if (total.hasKcal) {
        parts += if (total.kcalSigned) "${signedKcal(total.kcal)} kcal" else "${grouped(total.kcal)} kcal"
    }
    if (total.hasProtein && total.proteinGrams > 0.0) {
        parts += "${fmt1(total.proteinGrams)}g protein"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

@Composable
private fun colorForBalance(value: Int): Color = when {
    value < 0 -> HealthTheme.colors.primary
    value > 0 -> HealthTheme.colors.yellow
    else -> HealthTheme.colors.muted
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

private fun scoreStatus(score: Int): String = when {
    score >= 85 -> "optimal"
    score >= 70 -> "good"
    score >= 55 -> "fair"
    else -> "attention"
}

private fun normalizedEnergyScore(lever: HomeLever): Int? {
    if (lever.key != "energy_balance") return null
    val goal = lever.goal ?: return null
    val value = lever.value
    val score = when {
        goal < 0.0 -> (maxOf(0.0, -value) / maxOf(1.0, kotlin.math.abs(goal))) * 100.0
        goal > 0.0 -> (maxOf(0.0, value) / maxOf(1.0, kotlin.math.abs(goal))) * 100.0
        else -> 100.0 - (kotlin.math.abs(value - goal) / 125.0) * 30.0
    }
    return score.roundToInt().coerceIn(0, 100)
}

private fun effectiveLeverScore(lever: HomeLever): Int =
    normalizedEnergyScore(lever) ?: lever.score.coerceIn(0, 100)

private fun effectiveLeverStatus(lever: HomeLever): String =
    normalizedEnergyScore(lever)?.let(::scoreStatus) ?: lever.status

private fun effectiveLeverLabel(lever: HomeLever): String {
    if (lever.key != "energy_balance") return lever.label
    val goal = lever.goal ?: return lever.label
    val score = effectiveLeverScore(lever)
    return when {
        goal < 0.0 && lever.value <= goal -> "Deficit met"
        goal < 0.0 -> if (score >= 70) "Near target" else "Deficit short"
        goal > 0.0 && lever.value >= goal -> "Surplus met"
        goal > 0.0 -> if (score >= 70) "Near target" else "Surplus short"
        kotlin.math.abs(lever.value - goal) <= 125.0 -> "Balanced"
        lever.value < 0.0 -> "Deficit"
        else -> "Surplus"
    }
}

private fun forecastProgressScore(lever: HomeLever): Int? =
    if (lever.key == "energy_balance") lever.forecastScore?.coerceIn(0, 100) else null

private fun forecastProgressStatus(lever: HomeLever): String? =
    forecastProgressScore(lever)?.let { lever.forecastStatus ?: scoreStatus(it) }

private fun progressLabel(lever: HomeLever): String =
    if (forecastProgressScore(lever) != null) "Where you'll likely end the day" else "Progress"

private fun progressValueLabel(lever: HomeLever, shownScore: Int): String {
    val forecastValue = lever.forecastValue
    return if (forecastProgressScore(lever) != null && forecastValue != null) {
        "${signedKcal(forecastValue.roundToInt())} · $shownScore%"
    } else {
        "$shownScore%"
    }
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
    action = "",
    lineItems = sampleLeverLineItems(key),
)

private fun sampleLeverLineItems(key: String): List<HomeLeverLineItem> {
    return when (key) {
        "protein" -> listOf(
            HomeLeverLineItem("Protein porridge w/ milk", "17.3g · 278 kcal", "08:00"),
            HomeLeverLineItem("Small low-fat cappuccino", "6.4g · 60 kcal", "08:00"),
            HomeLeverLineItem("Banana", "1.7g · 107 kcal", "08:00"),
            HomeLeverLineItem("Chicken & rice bowl", "42.1g · 540 kcal", "13:15"),
            HomeLeverLineItem("Greek yoghurt", "15.0g · 130 kcal", "13:15"),
            HomeLeverLineItem("Chocolate muffin", "4.2g · 420 kcal", "15:30"),
            HomeLeverLineItem("Full-fat latte", "8.0g · 190 kcal", "15:30"),
        )
        "training" -> listOf(
            HomeLeverLineItem("Jul 05 · 1 Upper", "35 min · Hevy", "14:23"),
        )
        "steps" -> listOf(
            HomeLeverLineItem("Oura steps", "5,140 steps", "06:12"),
            HomeLeverLineItem("Phone steps", "2,280 steps", "07:45"),
        )
        "energy_balance" -> listOf(
            HomeLeverLineItem("Food · Protein porridge w/ milk", "+278 kcal · 17.3g protein", "08:00"),
            HomeLeverLineItem("Food · Small low-fat cappuccino", "+60 kcal · 6.4g protein", "08:00"),
            HomeLeverLineItem("Food · Banana", "+107 kcal · 1.7g protein", "08:00"),
            HomeLeverLineItem("Food · Chicken & rice bowl", "+540 kcal · 42.1g protein", "13:15"),
            HomeLeverLineItem("Food · Greek yoghurt", "+130 kcal · 15.0g protein", "13:15"),
            HomeLeverLineItem("Food · Chocolate muffin", "+420 kcal · 4.2g protein", "15:30"),
            HomeLeverLineItem("Food · Full-fat latte", "+190 kcal · 8.0g protein", "15:30"),
            HomeLeverLineItem("Burn · Oura active burn", "-120 kcal", "04:00"),
            HomeLeverLineItem("Workout · Upper", "-216 kcal", "09:42"),
            HomeLeverLineItem("Basal estimate", "-1,680 kcal"),
        )
        else -> emptyList()
    }
}

private fun sampleLevers(training: Int = 2): List<HomeLever> = listOf(
    sampleLever("energy_balance", "Calorie balance", 88, "On pace", "optimal", -320.0, -440.0, "kcal"),
    sampleLever("protein", "Protein", 88, "On track", "optimal", 132.0, 150.0, "g"),
    sampleLever(
        "training", "Training",
        score = training * 25, label = "Halfway", status = if (training >= 3) "good" else "fair",
        value = training.toDouble(), goal = 4.0, unit = "sessions", period = "week",
    ),
    sampleLever("steps", "Steps", 74, "In progress", "good", 7420.0, 10000.0, null),
)

private fun sampleSummary(weight: HomeWeight, levers: List<HomeLever>, insights: List<String>): HomeSummary =
    HomeSummary(
        date = "2026-07-06",
        isToday = true,
        objective = "Fat loss",
        overall = HomeDayStatus(score = 82, label = "On track", status = "optimal"),
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
internal fun HomeOnTrackPreview() {
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
internal fun HomeStalledPreview() {
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
internal fun HomeDarkPreview() {
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

@Preview(name = "Lever page — protein", showBackground = true, heightDp = 700, widthDp = 400)
@Composable
private fun ProteinLeverPagePreview() {
    PreviewShell(ThemeMode.LIGHT) {
        LeverDetailPage(
            lever = sampleLevers(training = 3).first { it.key == "protein" },
            onBack = {},
        )
    }
}

@Preview(name = "Lever page — training", showBackground = true, heightDp = 700, widthDp = 400)
@Composable
private fun TrainingLeverPagePreview() {
    PreviewShell(ThemeMode.LIGHT) {
        LeverDetailPage(
            lever = sampleLevers(training = 3).first { it.key == "training" },
            onBack = {},
        )
    }
}

@Preview(name = "Lever page — calorie balance", showBackground = true, heightDp = 700, widthDp = 400)
@Composable
internal fun CalorieBalanceLeverPagePreview() {
    PreviewShell(ThemeMode.LIGHT) {
        LeverDetailPage(
            lever = sampleLevers(training = 3).first { it.key == "energy_balance" },
            onBack = {},
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
