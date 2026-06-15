@file:JvmName("PlatformTheme")

package com.artemchep.keyguard.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.artemchep.autotype.getSystemAccentColor as getNativeSystemAccentColor
import com.artemchep.keyguard.ui.LocalComposeWindow
import com.artemchep.keyguard.ui.theme.m3.dynamicColorScheme
import io.github.kdroidfilter.platformtools.darkmodedetector.windows.setWindowsAdaptiveTitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
actual fun appDynamicDarkColorScheme(): ColorScheme {
    val accentColor = rememberSystemAccentColor()
    return remember(accentColor) {
        if (accentColor != 0) {
            dynamicColorScheme(
                keyColor = Color(accentColor),
                isDark = true,
            )
        } else {
            plainDarkColorScheme()
        }
    }
}

@Composable
actual fun appDynamicLightColorScheme(): ColorScheme {
    val accentColor = rememberSystemAccentColor()
    return remember(accentColor) {
        if (accentColor != 0) {
            dynamicColorScheme(
                keyColor = Color(accentColor),
                isDark = false,
            )
        } else {
            plainLightColorScheme()
        }
    }
}

@Composable
private fun rememberSystemAccentColor(): Int {
    var accentColor by remember {
        mutableStateOf(0)
    }

    val updatedLifecycle by rememberUpdatedState(LocalLifecycleOwner.current.lifecycle)
    LaunchedEffect(Unit) {
        while (isActive) {
            accentColor = withContext(Dispatchers.IO) {
                getNativeSystemAccentColor()
            }

            // We are potentially spawning processes to check the actual
            // accent color, so let's be conservative with the refresh rate.
            val delayMs = if (updatedLifecycle.currentState >= Lifecycle.State.RESUMED) {
                4000L
            } else 8000L
            delay(delayMs)
        }
    }
    return accentColor
}

@Composable
actual fun SystemUiThemeEffect() {
    val dark = MaterialTheme.colorScheme.isDark
    LocalComposeWindow.current.setWindowsAdaptiveTitleBar(dark)
}
