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
import org.jetbrains.skiko.currentSystemTheme
import com.artemchep.keyguard.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.skiko.SystemTheme
import java.util.concurrent.TimeUnit

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
private fun isLinuxPortalInDarkTheme(): Boolean = rememberIsDarkThemeWithAutoUpdate(
    initialValue = isSystemInDarkTheme(),
) {
    readLinuxPortalInDarkTheme()
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
