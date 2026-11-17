package com.artemchep.keyguard.platform.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalWindowInfo
import com.artemchep.autotype.getKeyboardInputLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

@Composable
actual fun rememberKeyboardInputLanguage(
    enabled: Boolean,
): State<String?> {
    val windowFocused = enabled && LocalWindowInfo.current.isWindowFocused
    return produceState<String?>(null, enabled, windowFocused) {
        if (!enabled) {
            value = null
            return@produceState
        }

        // The layout only matters while the window is focused,
        // so keep the last value until it is focused again.
        while (windowFocused) {
            value = withContext(Dispatchers.IO) {
                getKeyboardInputLanguage()
            }
            delay(1.seconds)
        }
    }
}
