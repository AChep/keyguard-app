package com.artemchep.keyguard.platform.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@Composable
actual fun Platform.hasDarkThemeEnabled(): Boolean = when (this) {
    is Platform.Desktop.Linux -> isLinuxPortalInDarkTheme()
    else -> isSystemInDarkTheme()
}

@Composable
private fun isLinuxPortalInDarkTheme(): Boolean {
    val darkThemeDefault = isSystemInDarkTheme()
    var darkThemeState by remember {
        mutableStateOf(darkThemeDefault)
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlin.runCatching {
                darkThemeState = readLinuxPortalInDarkTheme()
            }.getOrElse {
                // Stop checking for the appearance, just use
                // whatever Compose provides.
                darkThemeState = darkThemeDefault
                return@LaunchedEffect
            }

            delay(2000L)
            ensureActive()
        }
    }
    return darkThemeState
}

private suspend fun readLinuxPortalInDarkTheme() = withContext(Dispatchers.IO) {
    val command = listOf(
        "gdbus",
        "call",
        "--session",
        "--dest=org.freedesktop.portal.Desktop",
        "--object-path=/org/freedesktop/portal/desktop",
        "--method=org.freedesktop.portal.Settings.Read",
        "org.freedesktop.appearance",
        "color-scheme",
    )
    val pb = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    val process = pb.start().apply {
        waitFor(60, TimeUnit.MINUTES)
    }

    val result = process.inputStream.reader().readText()
    // 0: No preference
    // 1: Prefer dark appearance
    // 2: Prefer light appearance
    result[10] == '1'
}
