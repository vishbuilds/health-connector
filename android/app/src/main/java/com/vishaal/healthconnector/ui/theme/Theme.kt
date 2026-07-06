package com.vishaal.healthconnector.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestaurantMenu
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val surfaceStrong: Color,
    val ink: Color,
    val muted: Color,
    val primary: Color,
    val primaryDark: Color,
    val primarySoft: Color,
    val blue: Color,
    val blueSoft: Color,
    val yellow: Color,
    val yellowSoft: Color,
    val red: Color,
    val redSoft: Color,
    val border: Color,
    val shadow: Color,
)

@Immutable
data class AppTypography(
    val display: TextStyle,
    val title: TextStyle,
    val subtitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val small: TextStyle,
)

private val LightColors = AppColors(
    background = Color(0xFFF7FAFF),
    surface = Color.White,
    surfaceStrong = Color(0xFFEFF6FF),
    ink = Color(0xFF223041),
    muted = Color(0xFF647385),
    primary = Color(0xFF58CC02),
    primaryDark = Color(0xFF3F9700),
    primarySoft = Color(0xFFE7F9D9),
    blue = Color(0xFF1CB0F6),
    blueSoft = Color(0xFFDDF3FF),
    yellow = Color(0xFFFFC800),
    yellowSoft = Color(0xFFFFF4C2),
    red = Color(0xFFFF4B4B),
    redSoft = Color(0xFFFFE1E1),
    border = Color(0xFFD7E1EA),
    shadow = Color(0x33223041),
)

private val DarkColors = AppColors(
    background = Color(0xFF111A22),
    surface = Color(0xFF192530),
    surfaceStrong = Color(0xFF223241),
    ink = Color(0xFFF0F6FB),
    muted = Color(0xFFB8C5D0),
    primary = Color(0xFF76D94B),
    primaryDark = Color(0xFFB7F58E),
    primarySoft = Color(0xFF253F24),
    blue = Color(0xFF55C7FF),
    blueSoft = Color(0xFF17384E),
    yellow = Color(0xFFFFD84A),
    yellowSoft = Color(0xFF453A12),
    red = Color(0xFFFF7777),
    redSoft = Color(0xFF4A2429),
    border = Color(0xFF304454),
    shadow = Color(0x66000000),
)

private val Typography = AppTypography(
    display = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = 0.sp,
    ),
    title = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    subtitle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    label = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
    small = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

private val LocalAppColors = compositionLocalOf { LightColors }
private val LocalAppTypography = compositionLocalOf { Typography }

object HealthTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
    val type: AppTypography
        @Composable get() = LocalAppTypography.current
}

@Composable
fun HealthConnectorTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(
        LocalAppColors provides if (darkTheme) DarkColors else LightColors,
        LocalAppTypography provides Typography,
        content = content,
    )
}

@Composable
fun AppSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HealthTheme.colors.background),
    ) {
        content()
    }
}

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HealthTheme.type.body,
    color: Color = HealthTheme.colors.ink,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign == null) {
            style.copy(color = color)
        } else {
            style.copy(color = color, textAlign = textAlign)
        },
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    background: Color = HealthTheme.colors.surface,
    border: Color = HealthTheme.colors.border,
    radius: Dp = 8.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(3.dp, RoundedCornerShape(radius), ambientColor = HealthTheme.colors.shadow, spotColor = HealthTheme.colors.shadow)
            .clip(RoundedCornerShape(radius))
            .background(background)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(radius))
            .padding(padding),
    ) {
        content()
    }
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    leadingIconRes: Int? = null,
) {
    val colors = HealthTheme.colors
    val background = when {
        !enabled -> colors.border
        secondary -> colors.blueSoft
        else -> colors.primary
    }
    val foreground = when {
        !enabled -> colors.muted
        secondary -> colors.ink
        else -> Color.White
    }
    val bottom = when {
        !enabled -> colors.border
        secondary -> colors.blue
        else -> colors.primaryDark
    }
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bottom)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIconRes != null) {
                Image(
                    painter = painterResource(leadingIconRes),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(foreground),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                )
            }
            AppText(
                text = text,
                style = HealthTheme.type.label,
                color = foreground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(text = text, style = HealthTheme.type.label, color = HealthTheme.colors.blue)
    }
}

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = HealthTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        textStyle = HealthTheme.type.body.copy(color = colors.ink),
        modifier = modifier,
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(BorderStroke(2.dp, colors.border), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isBlank()) {
                    AppText(text = label, color = colors.muted)
                }
                innerTextField()
            }
        },
    )
}

@Composable
fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = HealthTheme.colors
    val (background, foreground) = when (tone) {
        StatusTone.GOOD -> colors.primarySoft to colors.primaryDark
        StatusTone.INFO -> colors.blueSoft to colors.blue
        StatusTone.WARN -> colors.yellowSoft to colors.ink
        StatusTone.BAD -> colors.redSoft to colors.red
        StatusTone.NEUTRAL -> colors.surfaceStrong to colors.muted
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        AppText(text = text, style = HealthTheme.type.small, color = foreground, maxLines = 1)
    }
}

enum class StatusTone {
    GOOD,
    INFO,
    WARN,
    BAD,
    NEUTRAL,
}

/**
 * Animated shimmer placeholder. Compose the real card/row shapes out of these while data loads so
 * the skeleton mirrors the eventual layout instead of showing a bare spinner.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    val base = HealthTheme.colors.border
    val highlight = lerp(base, Color.White, 0.28f)
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shift by transition.animateFloat(
        initialValue = -700f,
        targetValue = 700f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-shift",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(shift, 0f),
                    end = Offset(shift + 340f, 0f),
                ),
            ),
    )
}

@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = HealthTheme.colors.primary,
    trackColor: Color = HealthTheme.colors.border,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clamped)
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
    }
}

@Composable
fun SegmentedControl(
    values: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HealthTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surfaceStrong)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(18.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) colors.surface else Color.Transparent)
                    .clickable(onClick = { onSelected(index) }),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = label,
                    style = HealthTheme.type.small,
                    color = if (selected) colors.ink else colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ScoreDonut(
    score: Int,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = HealthTheme.colors.border,
) {
    Canvas(modifier = modifier.size(96.dp)) {
        val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
        drawArc(
            color = trackColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(color, lerp(color, Color.White, 0.35f), color),
                center = center,
            ),
            startAngle = -90f,
            sweepAngle = 360f * (score.coerceIn(0, 100) / 100f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

/**
 * Minimalist trend line for the weight-trend hero: a smooth polyline through the recent daily
 * points with a soft gradient fill and a dot on the latest reading. Auto-scales to the value range
 * (a flat line still reads as flat). One point draws a lone dot; zero points draws nothing.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = HealthTheme.colors.primary,
) {
    if (values.isEmpty()) {
        Box(modifier)
        return
    }
    val fill = color.copy(alpha = 0.14f)
    Canvas(modifier = modifier) {
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f
        val padY = size.height * 0.16f
        val n = values.size
        fun px(i: Int) = if (n == 1) size.width / 2f else size.width * i / (n - 1)
        fun py(v: Float) = padY + (size.height - 2 * padY) * (1f - (v - minV) / range)

        if (n == 1) {
            drawCircle(color, radius = 5.dp.toPx(), center = Offset(px(0), py(values[0])))
            return@Canvas
        }

        val line = Path().apply {
            moveTo(px(0), py(values[0]))
            for (i in 1 until n) lineTo(px(i), py(values[i]))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(px(n - 1), size.height)
            lineTo(px(0), size.height)
            close()
        }
        drawPath(area, brush = Brush.verticalGradient(listOf(fill, Color.Transparent)))
        drawPath(
            line,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(px(n - 1), py(values[n - 1])))
    }
}

/**
 * Renders a Material icon (ImageVector) tinted to the theme. We keep the small [AppIconKind] enum
 * as the app-facing vocabulary so call sites stay decoupled from the icon library, but the glyphs
 * themselves now come from `material-icons-extended` rather than hand-drawn Canvas paths.
 */
@Composable
fun AppIcon(
    icon: AppIconKind,
    modifier: Modifier = Modifier,
    tint: Color = HealthTheme.colors.ink,
) {
    Image(
        imageVector = icon.imageVector,
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = Modifier.size(24.dp).then(modifier),
    )
}

enum class AppIconKind(val imageVector: ImageVector) {
    HOME(Icons.Rounded.Home),
    MENU(Icons.Rounded.MoreVert),
    CHECK(Icons.Rounded.Check),
    CHEVRON(Icons.Rounded.ChevronRight),
    BACK(Icons.Rounded.ChevronLeft),
    FLAME(Icons.Rounded.LocalFireDepartment),
    SLEEP(Icons.Rounded.Bedtime),
    STEPS(Icons.AutoMirrored.Rounded.DirectionsWalk),
    HYDRATION(Icons.Rounded.WaterDrop),
    CALORIES(Icons.Rounded.Bolt),
    INSIGHT(Icons.Rounded.Lightbulb),
    CALENDAR(Icons.Rounded.CalendarToday),
    TREND(Icons.AutoMirrored.Rounded.TrendingUp),
    PROTEIN(Icons.Rounded.RestaurantMenu),
    TRAINING(Icons.Rounded.FitnessCenter),
    HEART(Icons.Rounded.MonitorHeart),
    TREND_UP(Icons.AutoMirrored.Rounded.TrendingUp),
    TREND_DOWN(Icons.Rounded.TrendingDown),
    TREND_FLAT(Icons.Rounded.TrendingFlat),
}

/**
 * Circular icon button. The default is a filled surface chip with a hairline border; [ghost] drops
 * both so the icon floats on the background — used for secondary controls (e.g. the day-switch
 * arrows) that should recede behind the content.
 */
@Composable
fun IconButton(
    icon: AppIconKind,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ghost: Boolean = false,
    @Suppress("UNUSED_PARAMETER")
    contentDescription: String,
) {
    val colors = HealthTheme.colors
    val base = if (ghost) {
        Modifier.size(44.dp).clip(CircleShape)
    } else {
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), CircleShape)
    }
    val tint = when {
        !enabled -> if (ghost) colors.border else colors.border
        ghost -> colors.muted
        else -> colors.ink
    }
    Box(
        modifier = modifier
            .then(base)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon = icon, tint = tint)
    }
}
