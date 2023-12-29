package com.artemchep.keyguard.feature.onboarding

import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource

data class OnboardingItem(
    val title: StringResource,
    val text: StringResource,
    val premium: Boolean = false,
    val icon: ImageVector? = null,
)
