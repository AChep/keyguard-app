package com.artemchep.keyguard.platform.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.artemchep.dbus.portal.PortalColorScheme
import com.artemchep.dbus.portal.observePortalColorSchemeDbus
import com.artemchep.keyguard.platform.Platform

@Composable
actual fun Platform.hasDarkThemeEnabled(): Boolean = when (this) {
    is Platform.Desktop.Linux -> isLinuxPortalInDarkTheme()
    else -> isSystemInDarkTheme()
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

private fun PortalColorScheme?.resolve(fallback: Boolean): Boolean =
    when (this) {
        PortalColorScheme.NO_PREFERENCE -> fallback
        PortalColorScheme.PREFER_DARK -> true
        PortalColorScheme.PREFER_LIGHT -> false
        null -> fallback
    }
