package com.artemchep.keyguard.platform.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.artemchep.dbus.portal.PortalColorScheme
import com.artemchep.dbus.portal.observePortalColorSchemeDbus
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme

@Composable
actual fun Platform.hasDarkThemeEnabled(): Boolean = when (this) {
    is Platform.Desktop.Linux -> isLinuxPortalInDarkTheme()
    else -> isDesktopInDarkTheme()
}

@Composable
private fun isDesktopInDarkTheme(): Boolean = rememberIsDarkThemeWithAutoUpdate(
    initialValue = isSystemInDarkTheme(),
) {
    // We hook to the Skiko APIs, check the isSystemInDarkTheme()
    // internals for the details/updates.
    currentSystemTheme == SystemTheme.DARK
}

@Composable
private fun isLinuxPortalInDarkTheme(): Boolean {
    val fallback = isSystemInDarkTheme()
    var portalColorScheme by remember {
        mutableStateOf<PortalColorScheme?>(null)
    }

    LaunchedEffect(Unit) {
        observePortalColorSchemeDbus()
            .collect {
                portalColorScheme = it
            }
    }

    return portalColorScheme.resolve(fallback)
}

@Composable
private fun rememberIsDarkThemeWithAutoUpdate(
    initialValue: Boolean,
    block: suspend () -> Boolean,
): Boolean {
    var currentTheme by remember {
        mutableStateOf(initialValue)
    }

    val updatedLifecycle by rememberUpdatedState(LocalLifecycleOwner.current.lifecycle)
    LaunchedEffect(Unit) {
        while (true) {
            kotlin.runCatching {
                currentTheme = block()
            }.getOrElse {
                // Stop checking for the updates
                return@LaunchedEffect
            }

            val delayMs = if (updatedLifecycle.currentState >= Lifecycle.State.RESUMED) {
                2000L
            } else {
                4000L
            }
            delay(delayMs)
            ensureActive()
        }
    }

    return currentTheme
}

private fun PortalColorScheme?.resolve(fallback: Boolean): Boolean =
    when (this) {
        PortalColorScheme.NO_PREFERENCE -> fallback
        PortalColorScheme.PREFER_DARK -> true
        PortalColorScheme.PREFER_LIGHT -> false
        null -> fallback
    }
