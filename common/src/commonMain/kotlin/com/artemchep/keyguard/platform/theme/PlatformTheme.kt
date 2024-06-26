package com.artemchep.keyguard.platform.theme

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.platform.Platform

@Composable
expect fun Platform.hasDarkThemeEnabled(): Boolean
