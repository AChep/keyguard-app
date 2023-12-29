@file:JvmName("PlatformTheme")

package com.artemchep.keyguard.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@Composable
actual fun appDynamicDarkColorScheme(): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        dynamicDarkColorScheme(context)
    } else {
        // Use whatever default Google has to offer.
        darkColorScheme()
    }

@Composable
actual fun appDynamicLightColorScheme(): ColorScheme =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        dynamicLightColorScheme(context)
    } else {
        // Use whatever default Google has to offer.
        lightColorScheme()
    }

@Composable
actual fun SystemUiThemeEffect() {
    val systemUiController = rememberSystemUiController()
    val useDarkIcons = !MaterialTheme.colorScheme.isDark
    SideEffect {
        systemUiController.setSystemBarsColor(
            color = Color.Transparent,
            darkIcons = useDarkIcons,
        )
    }
}
