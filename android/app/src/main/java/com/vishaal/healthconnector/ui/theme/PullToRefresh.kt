package com.vishaal.healthconnector.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A pull-to-refresh built on `foundation`'s nested-scroll rather than a Material component, so it
 * matches the app's hand-built visual language and pulls in no Material dependency. Overscrolling a
 * scrollable child past the top rubber-bands a themed arc indicator into view; releasing past the
 * threshold fires [onRefresh]. The indicator settles to a spinner while [isRefreshing] is true and
 * springs home when it clears.
 *
 * The pull is a functional, direct-manipulation gesture, so it stays available even under reduced
 * motion (only its decorative flourishes are toned down).
 */
@Composable
fun PullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxPx = with(density) { 128.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }

    // Drive the indicator to its resting spinner slot while refreshing, then home when done.
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) offset.animateTo(thresholdPx, Motion.liveSpring())
        else offset.animateTo(0f, Motion.standardSpring())
    }

    val connection = remember(isRefreshing, enabled, thresholdPx, maxPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || isRefreshing) return Offset.Zero
                // Scrolling back up while the indicator is out: retract it before the list moves.
                if (source == NestedScrollSource.UserInput && available.y < 0 && offset.value > 0f) {
                    val target = (offset.value + available.y).coerceAtLeast(0f)
                    val consumed = target - offset.value
                    scope.launch { offset.snapTo(target) }
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!enabled || isRefreshing) return Offset.Zero
                // Leftover downward drag at the top of the list feeds the pull, with rubber-band
                // resistance that stiffens the further it is stretched.
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    val resistance = 0.55f * (1f - (offset.value / maxPx).coerceIn(0f, 1f)) + 0.12f
                    val target = (offset.value + available.y * resistance).coerceIn(0f, maxPx)
                    scope.launch { offset.snapTo(target) }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!enabled || isRefreshing) return Velocity.Zero
                if (offset.value >= thresholdPx) {
                    onRefresh()
                    offset.animateTo(thresholdPx, Motion.liveSpring())
                } else if (offset.value > 0f) {
                    offset.animateTo(0f, Motion.standardSpring())
                }
                return Velocity.Zero
            }
        }
    }

    Box(modifier = modifier.nestedScroll(connection)) {
        PullIndicator(
            offsetPx = offset.value,
            thresholdPx = thresholdPx,
            refreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        // Push the content down by the live pull distance so it tracks the finger.
        Box(
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, offset.value.toInt())
                }
            },
            content = content,
        )
    }
}

/**
 * The pull affordance: a themed arc that grows and rotates with the pull, snaps to full and brightens
 * once past the trigger threshold, then spins continuously while the refresh runs.
 */
@Composable
private fun PullIndicator(
    offsetPx: Float,
    thresholdPx: Float,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = HealthTheme.colors
    val pull = (offsetPx / thresholdPx).coerceIn(0f, 1f)
    val armed = offsetPx >= thresholdPx

    val spin by rememberInfiniteTransition(label = "ptr-spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ptr-spin-angle",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { offsetPx.toDp() }),
        contentAlignment = Alignment.Center,
    ) {
        if (offsetPx <= 1f) return@Box
        val ringColor = if (armed || refreshing) colors.primary else colors.muted
        val alpha = (pull * 1.2f).coerceIn(0f, 1f)
        Canvas(modifier = Modifier.size(26.dp)) {
            val stroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
            // Faint track.
            drawArc(
                color = colors.border.copy(alpha = alpha * 0.6f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
            val start = if (refreshing) spin else -90f + pull * 40f
            val sweep = if (refreshing) 300f else (300f * pull).coerceAtLeast(12f)
            drawArc(
                color = ringColor.copy(alpha = if (refreshing) 1f else alpha),
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke,
            )
        }
    }
}
