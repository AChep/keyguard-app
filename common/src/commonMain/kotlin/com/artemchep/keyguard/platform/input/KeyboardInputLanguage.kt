package com.artemchep.keyguard.platform.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Language of the active keyboard as a lowercase ISO 639 code, for
 * example `ru`, or `null` if it is unknown or [enabled] is `false`.
 */
@Composable
expect fun rememberKeyboardInputLanguage(
    enabled: Boolean,
): State<String?>
