package com.vishaal.healthconnector.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A small, earned moment of delight when a refresh brings genuinely better news (a lever reaching
 * its best status, a score stepping up). Deliberately quiet: a couple of expanding rings, a soft
 * haptic tick, and a chip that slides in and clears itself after a beat. No streaks, no nagging, no
 * pressure — it fires only on a real improvement and never repeats for the same data.
 *
 * [event] is a one-shot signal; pass a fresh instance to celebrate and set it back to null when the
 * chip has been shown (via [onDone]). Under reduced motion the rings and haptic are skipped and only
 * the chip cross-fades.
 */
data class CelebrationEvent(val message: String)

@Composable
fun CelebrationOverlay(
    event: CelebrationEvent?,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    val haptics = LocalHapticFeedback.current
    val colors = HealthTheme.colors

    var visible by remember { mutableStateOf(false) }
    val ring = remember { Animatable(0f) }

    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        visible = true
        if (!reduced) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            ring.snapTo(0f)
            ring.animateTo(1f, tween(Motion.Celebrate, easing = Motion.EaseOut))
        }
        delay(1900)
        visible = false
        delay(240)
        onDone()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (!reduced && event != null && ring.value > 0f && ring.value < 1f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height * 0.28f)
                val maxR = size.minDimension * 0.62f
                // Two staggered rings expanding and fading — a soft pulse, not a firework.
                listOf(0f, 0.18f).forEach { lag ->
                    val p = ((ring.value - lag) / (1f - lag)).coerceIn(0f, 1f)
                    if (p <= 0f) return@forEach
                    drawCircle(
                        color = colors.primary.copy(alpha = (1f - p) * 0.35f),
                        radius = maxR * p,
                        center = center,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter = if (reduced) fadeIn() else fadeIn(tween(Motion.Fast)) +
                slideInVertically(tween(Motion.Medium, easing = Motion.EaseOut)) { -it },
            exit = if (reduced) fadeOut() else fadeOut(tween(Motion.Fast)) +
                slideOutVertically(tween(Motion.Fast)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
        ) {
            CelebrationChip(message = event?.message ?: "")
        }
    }
}

@Composable
private fun CelebrationChip(message: String) {
    val colors = HealthTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.primarySoft)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AppIcon(
            icon = AppIconKind.CHECK,
            tint = colors.primaryDark,
            modifier = Modifier
                .padding(end = 7.dp)
                .size(16.dp),
        )
        AppText(
            text = message,
            style = HealthTheme.type.label,
            color = colors.primaryDark,
            maxLines = 1,
        )
    }
}
