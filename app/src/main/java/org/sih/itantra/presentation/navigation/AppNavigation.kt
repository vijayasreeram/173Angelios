package org.sih.itantra.presentation.navigation

import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.emergency.EmergencyScreen
import org.sih.itantra.presentation.network.NetworkScreen
import org.sih.itantra.presentation.onboarding.UserRegistrationScreen
import org.sih.itantra.presentation.profile.ProfileScreen
import org.sih.itantra.presentation.splash.SplashScreen
import org.sih.itantra.presentation.talk.TalkScreen
import org.sih.itantra.presentation.ui.components.TacticalTopBar
import org.sih.itantra.presentation.ui.theme.*

/**
 * Tactical Navigation Architecture:
 * - Splash: Multi-stage cinematic AKHET assembly
 * - 1st Page: Operator Registration (Name, Gender, Preferred 10-Language Selection)
 * - 4 Dashboards:
 *   1. CONVO: Push-To-Talk + Pre-Commands + Speech-To-Text Stream
 *   2. SOS: Multi-Hop Wi-Fi Emergency Broadcast to All Devices
 *   3. MESH: 360° Radar Sweep, Connected Device Counter & Private Peer Selection
 *   4. PROFILE: User Identity & Dynamic 10-Language Switcher
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Splash : Screen("splash", "SPLASH", Icons.Default.Sensors)
    data object Register : Screen("register", "Register", Icons.Default.AppRegistration)
    data object Talk : Screen("talk", "CONVO", Icons.Default.RecordVoiceOver)
    data object Emergency : Screen("emergency", "SOS", Icons.Default.Warning)
    data object Network : Screen("network", "MESH", Icons.Default.Hub)
    data object Profile : Screen("profile", "PROFILE", Icons.Default.Person)
}

val DashboardNavItems = listOf(
    Screen.Talk,
    Screen.Emergency,
    Screen.Network,
    Screen.Profile
)

private val NavOrder = listOf(
    Screen.Talk.route,
    Screen.Emergency.route,
    Screen.Network.route,
    Screen.Profile.route
)

private fun getSlideDirection(initialRoute: String?, targetRoute: String?): Int {
    val initialIdx = NavOrder.indexOf(initialRoute)
    val targetIdx = NavOrder.indexOf(targetRoute)
    return if (initialIdx != -1 && targetIdx != -1) {
        if (targetIdx > initialIdx) 1 else -1
    } else 0
}

@Composable
fun AppNavigation(repository: CommunicationRepository) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val userProfile by repository.userProfile.collectAsState()
    val selectedLanguage by repository.selectedLanguage.collectAsState()
    val isSosActive by repository.emergencyManager.isSosActive.collectAsState()
    val activeTargetNode by repository.activeTargetNode.collectAsState()

    val isFullScreen = currentRoute == Screen.Splash.route || currentRoute == Screen.Register.route

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

    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TacticalTopBar(
                    selectedLanguage = selectedLanguage,
                    isSosActive = isSosActive,
                    userProfile = userProfile,
                    activeTargetNode = activeTargetNode,
                    onOpenLanguage = { navController.navigate(Screen.Profile.route) },
                    onTriggerSos = { navController.navigate(Screen.Emergency.route) },
                    onClearTarget = { repository.clearPrivatePeer() }
                )
            }
        },
        bottomBar = {
            if (!isFullScreen) {
                val selectedIndex = NavOrder.indexOf(currentRoute).coerceAtLeast(0)
                val pillOffsetRatio by animateFloatAsState(
                    targetValue = selectedIndex.toFloat(),
                    animationSpec = tween(280, easing = FastOutSlowInEasing),
                    label = "navPillOffset"
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(CyberVoidBlack)
                        .border(
                            1.dp,
                            CyberBorderRed,
                            RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                        )
                ) {
                    val tabWidth = maxWidth / DashboardNavItems.size

                    // Active Tab Sliding Highlight Pill
                    Box(
                        modifier = Modifier
                            .offset(x = tabWidth * pillOffsetRatio)
                            .width(tabWidth)
                            .fillMaxHeight()
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (currentRoute == Screen.Emergency.route) CyberNeonRed.copy(alpha = 0.28f)
                                else CyberNeonRed.copy(alpha = 0.16f)
                            )
                            .border(
                                1.dp,
                                if (currentRoute == Screen.Emergency.route) CyberNeonRedBright.copy(alpha = 0.8f)
                                else CyberBorderRedBright.copy(alpha = 0.45f),
                                RoundedCornerShape(10.dp)
                            )
                    )

                    // Row of Interactive Tabs
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DashboardNavItems.forEach { screen ->
                            val isSelected = currentRoute == screen.route
                            val isEmergency = screen == Screen.Emergency

                            val iconScale by animateFloatAsState(
                                targetValue = if (isSelected) 1.15f else 1.0f,
                                animationSpec = spring(
                                    dampingRatio = 0.5f,
                                    stiffness = Spring.StiffnessMedium
                                ),
                                label = "tabIconScale"
                            )

                            val iconColor by animateColorAsState(
                                targetValue = when {
                                    isEmergency -> if (isSelected) CyberNeonRedBright else CyberNeonRed.copy(alpha = 0.85f)
                                    isSelected -> CyberNeonRedBright
                                    else -> CyberTextDim
                                },
                                animationSpec = tween(200),
                                label = "tabIconColor"
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (currentRoute != screen.route) {
                                            navController.navigate(screen.route) {
                                                popUpTo(navController.graph.findStartDestination().id) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.label,
                                    tint = iconColor,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        }
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = screen.label,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    letterSpacing = 0.5.sp,
                                    color = if (isSelected) CyberTextWhite else CyberTextDim
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = CyberVoidBlack
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Splash.route,
                modifier = Modifier.fillMaxSize(),
                enterTransition = {
                    if (reduceMotion) {
                        fadeIn(animationSpec = tween(200))
                    } else if (initialState.destination.route == Screen.Splash.route) {
                        fadeIn(animationSpec = tween(400))
                    } else {
                        val dir = getSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        if (dir != 0) {
                            slideInHorizontally(
                                initialOffsetX = { fullWidth -> dir * fullWidth },
                                animationSpec = tween(280, easing = FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing))
                        } else {
                            fadeIn(animationSpec = tween(280))
                        }
                    }
                },
                exitTransition = {
                    if (reduceMotion) {
                        fadeOut(animationSpec = tween(200))
                    } else if (initialState.destination.route == Screen.Splash.route) {
                        fadeOut(animationSpec = tween(300))
                    } else {
                        val dir = getSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        if (dir != 0) {
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> -dir * fullWidth },
                                animationSpec = tween(280, easing = FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing))
                        } else {
                            fadeOut(animationSpec = tween(280))
                        }
                    }
                }
            ) {
                // Splash Screen: Cinematic Launch Sequence
                composable(Screen.Splash.route) {
                    SplashScreen(
                        onNavigateNext = {
                            val destination = if (userProfile.isInitialized) Screen.Talk.route else Screen.Register.route
                            navController.navigate(destination) {
                                popUpTo(Screen.Splash.route) { inclusive = true }
                            }
                        }
                    )
                }

                // First Page: User Onboarding / Registration
                composable(Screen.Register.route) {
                    UserRegistrationScreen(
                        repository = repository,
                        onRegistrationComplete = {
                            navController.navigate(Screen.Talk.route) {
                                popUpTo(Screen.Register.route) { inclusive = true }
                            }
                        }
                    )
                }

                // Dashboard 1: CONVO (Push-To-Talk + Pre-Commands)
                composable(Screen.Talk.route) {
                    TalkScreen(repository = repository)
                }

                // Dashboard 2: SOS (Wi-Fi Emergency Broadcast to All)
                composable(Screen.Emergency.route) {
                    EmergencyScreen(repository = repository)
                }

                // Dashboard 3: MESH (Radar + Device Count + Private Peer Selection)
                composable(Screen.Network.route) {
                    NetworkScreen(repository = repository)
                }

                // Dashboard 4: PROFILE (User Details + 10-Language Switcher)
                composable(Screen.Profile.route) {
                    ProfileScreen(repository = repository)
                }
            }
        }
    }
}

