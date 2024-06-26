package com.artemchep.keyguard.platform.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.artemchep.keyguard.platform.Platform

@Composable
actual fun Platform.hasDarkThemeEnabled(): Boolean = isSystemInDarkTheme()
