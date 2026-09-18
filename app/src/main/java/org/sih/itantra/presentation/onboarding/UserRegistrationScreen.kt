package org.sih.itantra.presentation.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.sih.itantra.R
import org.sih.itantra.domain.model.Language
import org.sih.itantra.domain.repository.CommunicationRepository
import org.sih.itantra.presentation.ui.theme.*

/**
 * 1st Page: Operator Registration & Hardware Provisioning.
 * Collects Name, Gender, and Preferred Language (10 Indian Languages)
 * in a Cyberpunk Glassmorphic Red Tactical Interface.
 */
@Composable
fun UserRegistrationScreen(
    repository: CommunicationRepository,
    onRegistrationComplete: () -> Unit
) {
    val existingProfile by repository.userProfile.collectAsState()
    val focusManager = LocalFocusManager.current

    var nameText by remember { mutableStateOf(if (existingProfile.isInitialized) existingProfile.username else "") }
    var selectedGender by remember { mutableStateOf(existingProfile.gender.ifBlank { "Male" }) }
    var selectedLang by remember { mutableStateOf(existingProfile.preferredLanguage) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val isroLanguages = remember {
        listOf(
            Language.HINDI,
            Language.GUJARATI,
            Language.MARATHI,
            Language.KANNADA,
            Language.MALAYALAM,
            Language.TAMIL,
            Language.TELUGU,
            Language.ODIA,
            Language.BENGALI,
            Language.ENGLISH
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "cyberGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        CyberVoidBlack,
                        Color(0xFF14050B),
                        CyberVoidBlack
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. AKHET Official Logo with Cyberpunk Glow Halo
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .shadow(
                        elevation = 20.dp,
                        shape = CircleShape,
                        spotColor = CyberNeonRed.copy(alpha = glowAlpha),
                        ambientColor = CyberNeonRed
                    )
                    .clip(CircleShape)
                    .background(CyberGlassSurface)
                    .border(2.dp, CyberNeonRed.copy(alpha = glowAlpha), CircleShape)
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_akhet_logo),
                    contentDescription = "AKHET Logo",
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2. Tactical Headings
            Text(
                text = "ANGELIOS // TACTICAL NODE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp,
                letterSpacing = 1.5.sp,
                color = CyberNeonRedBright
            )
            Text(
                text = "ISRO SMART INDIA HACKATHON • ID 26173",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                color = CyberTextMuted
            )
            Text(
                text = "Turning Signals into Survival • 100% Offline Mesh",
                fontFamily = FontFamily.SansSerif,
                fontSize = 11.sp,
                color = CyberTextDim
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 3. User Details Glassmorphic Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CyberGlassSurface)
                    .border(1.dp, CyberBorderRed, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

                    // A. Operator Name
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = CyberNeonRedBright,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "OPERATOR NAME",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberNeonRedBright,
                                letterSpacing = 0.8.sp
                            )
                        }

                        OutlinedTextField(
                            value = nameText,
                            onValueChange = {
                                nameText = it
                                if (errorMessage != null) errorMessage = null
                            },
                            placeholder = {
                                Text(
                                    text = "Enter your full name or call-sign",
                                    color = CyberTextDim,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = CyberTextWhite,
                                unfocusedTextColor = CyberTextWhite,
                                focusedBorderColor = CyberNeonRedBright,
                                unfocusedBorderColor = CyberBorderSubtle,
                                focusedContainerColor = CyberVoidBlack.copy(alpha = 0.6f),
                                unfocusedContainerColor = CyberVoidBlack.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // B. Gender Selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "GENDER IDENTITY",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberNeonRedBright,
                            letterSpacing = 0.8.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf("Male", "Female", "Other").forEach { genderOption ->
                                val isSelected = selectedGender.equals(genderOption, ignoreCase = true)
                                val bgColor by animateColorAsState(
                                    targetValue = if (isSelected) CyberNeonRed else CyberCardDark.copy(alpha = 0.7f),
                                    label = "genderBg"
                                )
                                val borderColor by animateColorAsState(
                                    targetValue = if (isSelected) CyberNeonRedBright else CyberBorderSubtle,
                                    label = "genderBorder"
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(42.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(bgColor)
                                        .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                        .clickable {
                                            selectedGender = genderOption
                                            focusManager.clearFocus()
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = genderOption.uppercase(),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) CyberTextWhite else CyberTextMuted
                                    )
                                }
                            }
                        }
                    }

                    // C. Preferred Language Selection (10 ISRO Languages)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Translate,
                                    contentDescription = null,
                                    tint = CyberNeonRedBright,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "PREFERRED LANGUAGE",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberNeonRedBright,
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Text(
                                text = "10 ISRO LANGS",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = CyberMatrixGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = "Your offline neural transceiver and speech synthesis will prioritize this language:",
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 11.sp,
                            color = CyberTextDim
                        )

                        // 2-column language grid
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            isroLanguages.chunked(2).forEach { rowLangs ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowLangs.forEach { lang ->
                                        val isSelected = selectedLang == lang
                                        val chipBg by animateColorAsState(
                                            targetValue = if (isSelected) CyberNeonRed.copy(alpha = 0.35f) else CyberCardDark.copy(alpha = 0.5f),
                                            label = "chipBg"
                                        )
                                        val chipBorder by animateColorAsState(
                                            targetValue = if (isSelected) CyberNeonRedBright else CyberBorderSubtle,
                                            label = "chipBorder"
                                        )

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(chipBg)
                                                .border(1.dp, chipBorder, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    selectedLang = lang
                                                    focusManager.clearFocus()
                                                }
                                                .padding(horizontal = 10.dp, vertical = 9.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = lang.nativeName,
                                                        fontFamily = FontFamily.SansSerif,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) CyberTextWhite else CyberTextMuted
                                                    )
                                                    Text(
                                                        text = lang.displayName.uppercase(),
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 9.sp,
                                                        color = if (isSelected) CyberNeonRedBright else CyberTextDim
                                                    )
                                                }

                                                if (isSelected) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(18.dp)
                                                            .clip(CircleShape)
                                                            .background(CyberNeonRed),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected",
                                                            tint = CyberTextWhite,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = CyberNeonRedBright,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. Submit & Initialize Button
            Button(
                onClick = {
                    val trimmedName = nameText.trim()
                    if (trimmedName.isBlank()) {
                        errorMessage = "Please enter your operator name to join the mesh network"
                        return@Button
                    }
                    errorMessage = null
                    repository.updateUserProfile(
                        username = trimmedName,
                        gender = selectedGender,
                        preferredLanguage = selectedLang,
                        callsign = "AKHET-${(100..999).random()}",
                        role = "Field Unit"
                    )
                    onRegistrationComplete()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .shadow(12.dp, RoundedCornerShape(10.dp), spotColor = CyberNeonRed),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberNeonRed
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "INITIALIZE TACTICAL NODE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = CyberTextWhite
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Enter",
                        tint = CyberTextWhite,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Hardware security notice
            Text(
                text = "MIL-SPEC OFFLINE ARCHITECTURE • ZERO TELEMETRY TRANSMITTED TO CLOUD",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = CyberTextDim,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
