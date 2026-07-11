package com.vishaal.healthconnector.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Central motion vocabulary for the app. One place so every screen animates with the same easing,
 * timing, and physics — the difference between "things move" and a coherent, buttery feel.
 *
 * The values follow a small set of rules borrowed from strong motion practice:
 *  - Enters/exits use a punchy ease-out (built-in curves are too weak to feel intentional).
 *  - On-screen movement uses ease-in-out; gestures and "alive" elements use springs (interruptible).
 *  - UI transitions stay short (< 300 ms); only rare, first-time moments earn longer, playful motion.
 *  - Nothing appears from nothing — enters pair opacity with a small translate/scale.
 *  - Every animation collapses to instant (or a bare cross-fade) when the user has reduced motion on.
 */
object Motion {
    // --- Easing ---------------------------------------------------------------
    /** Strong ease-out — instant initial movement, gentle settle. Enters, reveals, feedback. */
    val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

    /** Strong ease-in-out — natural accel/decel for elements moving on screen. */
    val EaseInOut = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)

    /** iOS-drawer curve (Ionic) — used for screen-to-screen slide transitions. */
    val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    val Linear = LinearEasing
    val Sine = EaseInOutSine

    // --- Durations (ms) -------------------------------------------------------
    const val Press = 120
    const val Fast = 180
    const val Medium = 260
    const val Nav = 340
    const val Celebrate = 620

    // --- Springs --------------------------------------------------------------
    /** Standard settle — no bounce. Progress bars, layout shifts, count-ups. */
    fun <T> standardSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** A hair of bounce — for elements that should feel alive (celebration pop, pull indicator). */
    fun <T> liveSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)

    /** Snappy press-release spring for tactile feedback. */
    fun <T> pressSpring(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh)

    fun tweenFast(easing: androidx.compose.animation.core.Easing = EaseOut) =
        tween<Float>(durationMillis = Fast, easing = easing)

    fun tweenMedium(easing: androidx.compose.animation.core.Easing = EaseOut) =
        tween<Float>(durationMillis = Medium, easing = easing)
}

/**
 * Whether the user has asked the system to minimize motion (Developer options / accessibility set the
 * animator duration scale to 0). When true, callers should skip decorative motion and fall back to a
 * plain cross-fade or an instant change — honoring the product's reduced-motion promise.
 */
val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    val scale by produceState(initialValue = 1f, context) {
        value = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
    }
    return scale == 0f
}

/**
 * Tactile press feedback: the element scales down slightly while held and springs back on release.
 * Buttons and cards must feel like they physically respond to a touch. Pass the same
 * [interactionSource] you give to `clickable` so the scale tracks the real press state. No-ops under
 * reduced motion.
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Modifier = composed {
    val reduced = LocalReducedMotion.current
    if (reduced) return@composed this

    var pressed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            pressed = when (interaction) {
                is PressInteraction.Press -> true
                is PressInteraction.Release, is PressInteraction.Cancel -> false
                else -> pressed
            }
        }
    }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.pressSpring(),
        label = "press-scale",
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The standard "content arrives" enter: a soft fade paired with a small upward slide, on the strong
 * ease-out curve. Reduced motion degrades this to a plain fade (never a hard cut — appearing from
 * nothing reads as broken).
 */
@Composable
fun contentEnter(reduced: Boolean, delayMillis: Int = 0): EnterTransition {
    if (reduced) return fadeIn(animationSpec = tween(Motion.Fast, delayMillis = delayMillis))
    val fade: FiniteAnimationSpec<Float> = tween(Motion.Medium, delayMillis = delayMillis, easing = Motion.EaseOut)
    return fadeIn(animationSpec = fade) + slideInVertically(
        animationSpec = tween(Motion.Medium, delayMillis = delayMillis, easing = Motion.EaseOut),
        initialOffsetY = { it / 8 },
    )
}

/**
 * Animates a numeric value toward [target] so figures roll into place instead of snapping. Returns a
 * float you can format for display. Does NOT count up from zero — it eases only when [target] changes
 * (right for values like bodyweight, where a roll from 0 would read as a gimmick). Under reduced
 * motion the value jumps straight to the target.
 */
@Composable
fun animatedNumber(
    target: Float,
    spec: AnimationSpec<Float> = Motion.standardSpring(),
): State<Float> {
    val reduced = LocalReducedMotion.current
    return animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduced) tween(0) else spec,
        label = "animated-number",
    )
}

/**
 * Counts a value up from zero the first time it appears, then eases to any later [target]. Good for
 * tallies where the roll-up is satisfying (steps, protein, calories, scores). Snaps instantly under
 * reduced motion.
 */
@Composable
fun countUpNumber(
    target: Float,
    durationMillis: Int = 720,
): State<Float> {
    val reduced = LocalReducedMotion.current
    val anim = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) anim.snapTo(target)
        else anim.animateTo(target, tween(durationMillis, easing = Motion.EaseOut))
    }
    return anim.asState()
}

/**
 * A one-shot entrance for content that is laid out (as opposed to swapped by AnimatedContent): the
 * element fades and lifts into place once, on the strong ease-out curve, offset by [index] * a small
 * step so a group of items cascades in. Composes cleanly with layout modifiers like `weight`, unlike
 * wrapping in AnimatedVisibility. No-ops under reduced motion.
 */
fun Modifier.enterOnce(index: Int = 0, translationYDp: Float = 16f): Modifier = composed {
    val reduced = LocalReducedMotion.current
    if (reduced) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (index > 0) delay(index * 55L)
        progress.animateTo(1f, tween(Motion.Medium, easing = Motion.EaseOut))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * translationYDp.dp.toPx()
    }
}
