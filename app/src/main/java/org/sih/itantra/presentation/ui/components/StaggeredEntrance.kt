package org.sih.itantra.presentation.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Reusable Staggered Entrance Animation for tactical card panels.
 * Provides hardware-accelerated translationY + alpha entrance.
 * Automatically falls back to instant layout when system Reduce Motion is enabled.
 */
@Composable
fun StaggeredEntrance(
    index: Int,
    delayPerItemMs: Int = 60,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f
            ) == 0f
        } catch (_: Exception) {
            false
        }
    }

    if (reduceMotion) {
        Box(modifier = modifier) {
            content()
        }
    } else {
        var visible by remember { mutableStateOf(false) }

        LaunchedEffect(index) {
            delay((index * delayPerItemMs).toLong())
            visible = true
        }

        val alpha by animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "entranceAlpha"
        )

        val offsetY by animateFloatAsState(
            targetValue = if (visible) 0f else 22f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "entranceOffset"
        )

        Box(
            modifier = modifier.graphicsLayer {
                this.alpha = alpha
                this.translationY = offsetY * density
            }
        ) {
            content()
        }
    }
}
