package com.artemchep.keyguard.platform.input

import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
actual fun rememberKeyboardInputLanguage(
    enabled: Boolean,
): State<String?> {
    val context = LocalContext.current
    val windowFocused = enabled && LocalWindowInfo.current.isWindowFocused
    return produceState<String?>(null, enabled, windowFocused, context) {
        if (!enabled) {
            value = null
            return@produceState
        }

        // There is no callback for input method subtype changes, so poll
        // it. A focused field stays focused in the background, so keep the
        // last value until the window is focused again.
        val imm = context.getSystemService(InputMethodManager::class.java)
        while (windowFocused) {
            value = imm?.getKeyboardInputLanguage()
            delay(1.seconds)
        }
    }
}

private fun InputMethodManager.getKeyboardInputLanguage(): String? {
    val subtype = runCatching { currentInputMethodSubtype }
        .getOrNull()
        ?: return null
    val tag = subtype.languageTag
        .ifEmpty {
            @Suppress("DEPRECATION")
            subtype.locale
        }
    return Locale.forLanguageTag(tag.replace('_', '-'))
        .language
        .takeIf { it.isNotBlank() }
}
