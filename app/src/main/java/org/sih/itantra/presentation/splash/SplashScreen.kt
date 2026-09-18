package org.sih.itantra.presentation.splash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sih.itantra.R
import org.sih.itantra.presentation.ui.theme.*

/**
 * Cinematic Multi-Stage Splash Screen for AKHET / Angelios Tactical Mesh.
 * Stage 1 (0-600ms): Expanding center radial crimson bloom
 * Stage 2 (400-1200ms): AKHET pyramid converging strokes + EaseOutBack crest assembly
 * Stage 3 (1000-1600ms): "AKHET" tactical wordmark slamming in with EaseOutExpo
 * Stage 4 (1400-2200ms): "WE RISE BEYOND OUR LIMITS" character-by-character typewriter
 * Stage 5 (concurrent with 4): Scanning shimmer progress bar & cycling status telemetry
 * Exit (2200-2550ms): Smooth 1.0 -> 1.08 scale-up + fade into CONVO
 */
@Composable
fun SplashScreen(
    onNavigateNext: () -> Unit
) {
    var isExiting by remember { mutableStateOf(false) }

    // Stage 1: Radial crimson bloom (0 -> 0.3)
    val glowAlpha = remember { Animatable(0f) }
    val glowRadius = remember { Animatable(0.2f) }

    // Stage 2: Pyramid converging stroke progress & crest scale
    val pyramidStrokeProgress = remember { Animatable(0f) }
    val crestScale = remember { Animatable(0.85f) }
    val crestAlpha = remember { Animatable(0f) }

    // Stage 3: Wordmark slam (1.35 -> 1.0, EaseOutExpo)
    val wordmarkScale = remember { Animatable(1.35f) }
    val wordmarkAlpha = remember { Animatable(0f) }

    // Stage 4: Typewriter tagline
    val fullTagline = "WE RISE BEYOND OUR LIMITS"
    var typedTaglineLength by remember { mutableIntStateOf(0) }

    // Stage 5: Progress bar & status cycling
    val initProgress = remember { Animatable(0.08f) }
    var currentStatusIndex by remember { mutableIntStateOf(0) }
    val statusSteps = remember {
        listOf(
            "Loading offline neural translator...",
            "Checking base station...",
            "Initializing mesh radio...",
            "Tactical node online • Ready"
        )
    }

    // Exit transition (1.0 -> 1.08 scale + fade)
    val exitScale = remember { Animatable(1.0f) }
    val exitAlpha = remember { Animatable(1.0f) }

    val coroutineScope = rememberCoroutineScope()

    // Blinking cursor
    val infiniteTransition = rememberInfiniteTransition(label = "cursorAndShimmer")
    val cursorBlink by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )

    // Progress bar shimmer offset
    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    // Multi-stage timeline
    LaunchedEffect(Unit) {
        // Stage 1 (0-600ms): Radial glow expands from center
        launch {
            glowAlpha.animateTo(0.35f, tween(600, easing = LinearEasing))
        }
        launch {
            glowRadius.animateTo(1.0f, tween(800, easing = FastOutSlowInEasing))
        }

        delay(400)

        // Stage 2 (400-1200ms): Pyramid converging strokes & crest assembly with overshoot
        launch {
            pyramidStrokeProgress.animateTo(1.0f, tween(700, easing = FastOutSlowInEasing))
        }
        launch {
            crestAlpha.animateTo(1.0f, tween(500, easing = FastOutSlowInEasing))
        }
        launch {
            crestScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(750, easing = EaseOutBack)
            )
        }

        delay(550)

        // Stage 3 (1000-1600ms): "AKHET" wordmark slam
        launch {
            wordmarkAlpha.animateTo(1.0f, tween(250, easing = LinearEasing))
        }
        launch {
            wordmarkScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(500, easing = EaseOutExpo)
            )
        }

        delay(400)

        // Stage 4 & 5 (1400-2200ms): Tagline typewriter & Progress bar fill
        launch {
            initProgress.animateTo(0.38f, tween(300, easing = LinearEasing))
            currentStatusIndex = 1
            initProgress.animateTo(0.72f, tween(350, easing = LinearEasing))
            currentStatusIndex = 2
            initProgress.animateTo(1.0f, tween(300, easing = LinearEasing))
            currentStatusIndex = 3
        }

        // Typewriter loop (~25ms per char)
        for (i in 1..fullTagline.length) {
            typedTaglineLength = i
            delay(25L)
        }

        // Slight deliberate tactical pause at full ready state
        delay(350L)

        // Exit transition: scale-up (1.0 -> 1.08) + fade (350ms, EaseInExpo)
        isExiting = true
        launch {
            exitScale.animateTo(1.08f, tween(350, easing = EaseInExpo))
        }
        exitAlpha.animateTo(0f, tween(350, easing = EaseInExpo))

        onNavigateNext()
    }

    // Immediate skip function on tap
    val triggerSkip: () -> Unit = {
        if (!isExiting) {
            isExiting = true
            coroutineScope.launch {
                launch { exitScale.animateTo(1.05f, tween(200, easing = FastOutSlowInEasing)) }
                exitAlpha.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                onNavigateNext()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberVoidBlack)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = triggerSkip
            )
            .graphicsLayer {
                scaleX = exitScale.value
                scaleY = exitScale.value
                alpha = exitAlpha.value
            },
        contentAlignment = Alignment.Center
    ) {
        // Stage 1: Expanding Center Crimson Radial Bloom
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.width * 0.85f * glowRadius.value
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CyberNeonRed.copy(alpha = glowAlpha.value),
                        CyberCrimsonDark.copy(alpha = glowAlpha.value * 0.4f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxR.coerceAtLeast(10f)
                ),
                radius = maxR.coerceAtLeast(10f),
                center = center
            )
        }

        // Main Visual Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top ISRO / Mission HUD Header
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(CyberGlassSurface)
                    .border(1.dp, CyberBorderRedBright, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CyberMatrixGreen)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ISRO // SMART INDIA HACKATHON // PS 26173",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberNeonRedBright,
                    letterSpacing = 0.5.sp
                )
            }

            // Stage 2: AKHET Pyramid Assembly & Center Crest
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                // Canvas: Converging Pyramid Strokes
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val w = size.width
                    val h = size.height
                    val apex = Offset(w / 2f, h * 0.12f)
                    val bottomLeft = Offset(w * 0.15f, h * 0.88f)
                    val bottomRight = Offset(w * 0.85f, h * 0.88f)

                    val progress = pyramidStrokeProgress.value

                    // Left converging stroke (bottom-left -> apex)
                    val currentLeftEnd = Offset(
                        x = bottomLeft.x + (apex.x - bottomLeft.x) * progress,
                        y = bottomLeft.y + (apex.y - bottomLeft.y) * progress
                    )
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(CyberNeonRed, CyberNeonRedBright),
                            start = bottomLeft,
                            end = currentLeftEnd
                        ),
                        start = bottomLeft,
                        end = currentLeftEnd,
                        strokeWidth = 2.5.dp.toPx()
                    )

                    // Right converging stroke (bottom-right -> apex)
                    val currentRightEnd = Offset(
                        x = bottomRight.x + (apex.x - bottomRight.x) * progress,
                        y = bottomRight.y + (apex.y - bottomRight.y) * progress
                    )
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(CyberNeonRed, CyberNeonRedBright),
                            start = bottomRight,
                            end = currentRightEnd
                        ),
                        start = bottomRight,
                        end = currentRightEnd,
                        strokeWidth = 2.5.dp.toPx()
                    )

                    // Base connecting stroke (converging from corners to center)
                    if (progress > 0.4f) {
                        val baseProgress = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
                        val midBottom = Offset(w / 2f, h * 0.88f)
                        val curLeftBase = Offset(bottomLeft.x + (midBottom.x - bottomLeft.x) * baseProgress, midBottom.y)
                        val curRightBase = Offset(bottomRight.x + (midBottom.x - bottomRight.x) * baseProgress, midBottom.y)
                        drawLine(
                            color = CyberBorderRedBright.copy(alpha = baseProgress),
                            start = bottomLeft,
                            end = curLeftBase,
                            strokeWidth = 1.5.dp.toPx()
                        )
                        drawLine(
                            color = CyberBorderRedBright.copy(alpha = baseProgress),
                            start = bottomRight,
                            end = curRightBase,
                            strokeWidth = 1.5.dp.toPx()
                        )
                    }

                    // Tactical corner reticle brackets
                    val bracketLen = 14.dp.toPx()
                    // Bottom-left corner bracket
                    drawLine(color = CyberNeonRedBright, start = bottomLeft, end = Offset(bottomLeft.x, bottomLeft.y - bracketLen), strokeWidth = 1.5.dp.toPx())
                    drawLine(color = CyberNeonRedBright, start = bottomLeft, end = Offset(bottomLeft.x + bracketLen, bottomLeft.y), strokeWidth = 1.5.dp.toPx())
                    // Bottom-right corner bracket
                    drawLine(color = CyberNeonRedBright, start = bottomRight, end = Offset(bottomRight.x, bottomRight.y - bracketLen), strokeWidth = 1.5.dp.toPx())
                    drawLine(color = CyberNeonRedBright, start = bottomRight, end = Offset(bottomRight.x - bracketLen, bottomRight.y), strokeWidth = 1.5.dp.toPx())
                }

                // Central AKHET Crest assembling with EaseOutBack
                Box(
                    modifier = Modifier
                        .size(86.dp)
                        .graphicsLayer {
                            scaleX = crestScale.value
                            scaleY = crestScale.value
                            alpha = crestAlpha.value
                        }
                        .clip(CircleShape)
                        .background(CyberGlassSurface)
                        .border(2.dp, CyberNeonRedBright, CircleShape)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_akhet_logo),
                        contentDescription = "AKHET Logo",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Stage 3 & 4: Wordmark Slam & Typewriter Tagline
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Stage 3: Wordmark slam
                Text(
                    text = "AKHET",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Black,
                    fontSize = 38.sp,
                    letterSpacing = 6.sp,
                    color = CyberTextWhite,
                    modifier = Modifier.graphicsLayer {
                        scaleX = wordmarkScale.value
                        scaleY = wordmarkScale.value
                        alpha = wordmarkAlpha.value
                    }
                )

                // Sub-label
                Text(
                    text = "ANGELIOS TACTICAL MESH",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = CyberNeonRedBright,
                    modifier = Modifier.graphicsLayer {
                        alpha = wordmarkAlpha.value
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Stage 4: Tagline character-by-character typewriter
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.height(24.dp)
                ) {
                    val displayed = fullTagline.take(typedTaglineLength)
                    Text(
                        text = displayed,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.5.sp,
                        color = CyberTextMuted
                    )
                    if (cursorBlink > 0.45f && typedTaglineLength < fullTagline.length + 1) {
                        Text(
                            text = "█",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = CyberNeonRed
                        )
                    }
                }
            }

            // Stage 5: Progress Bar with Scanline Shimmer & Status Cycling
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status Text with Crossfade Transition
                Box(
                    modifier = Modifier.height(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = statusSteps[currentStatusIndex.coerceIn(0, statusSteps.lastIndex)],
                        transitionSpec = {
                            fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "statusCrossfade"
                    ) { statusText ->
                        Text(
                            text = statusText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = if (currentStatusIndex == 3) CyberMatrixGreen else CyberTextDim,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Thin Progress Bar with Shimmer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(CyberCardDark)
                ) {
                    val fillFraction = initProgress.value.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fillFraction)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        CyberNeonRed,
                                        CyberNeonRedBright,
                                        Color.White.copy(alpha = 0.9f),
                                        CyberNeonRedBright,
                                        CyberNeonRed
                                    ),
                                    startX = shimmerOffset * 300f,
                                    endX = (shimmerOffset + 1f) * 300f
                                )
                            )
                    )
                }

                // Skip / Tap cue
                Text(
                    text = "TAP ANYWHERE TO SKIP",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = CyberTextDim.copy(alpha = 0.7f),
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
