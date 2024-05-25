package com.artemchep.keyguard.feature.onboarding

import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.StringResource

data class OnboardingItem(
    val title: StringResource,
    val text: StringResource,
    val premium: Boolean = false,
    val icon: ImageVector? = null,
)
