package com.artemchep.keyguard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import arrow.core.throwIfFatal
import com.artemchep.jna.windows.setWindowExcludedFromCapture
import com.artemchep.keyguard.common.model.AllowScreenshots
import com.artemchep.keyguard.common.usecase.GetAllowScreenshots
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.platform.recordLog
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import org.kodein.di.compose.rememberInstance

@Composable
fun WindowScreenshotProtectionEffect() {
    if (CurrentPlatform !is Platform.Desktop.Windows) {
        return
    }

    val window = LocalComposeWindow.current
    val windowHandle = window.windowHandle
    val getAllowScreenshots by rememberInstance<GetAllowScreenshots>()
    LaunchedEffect(
        getAllowScreenshots,
        windowHandle,
    ) {
        getAllowScreenshots()
            .distinctUntilChanged()
            .onEach { allowScreenshots ->
                val excluded = allowScreenshots < AllowScreenshots.LIMITED
                applyWindowScreenshotProtection(
                    windowHandle = windowHandle,
                    excluded = excluded,
                )
            }
            .collect()
    }
}

private fun applyWindowScreenshotProtection(
    windowHandle: Long,
    excluded: Boolean,
) {
    try {
        val success = setWindowExcludedFromCapture(
            windowHandle = windowHandle,
            excluded = excluded,
        )
        if (!success) {
            recordLog("Failed to update Windows screenshot protection.")
        }
    } catch (e: Throwable) {
        e.throwIfFatal()
        recordException(e)
    }
}
