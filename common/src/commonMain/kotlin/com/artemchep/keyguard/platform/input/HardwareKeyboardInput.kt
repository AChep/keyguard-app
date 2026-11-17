package com.artemchep.keyguard.platform.input

import androidx.compose.runtime.Composable

/**
 * `true` if the user types on a hardware keyboard, so the keyboard's
 * state, such as Caps Lock or the language, is not shown on the screen.
 */
@Composable
expect fun isHardwareKeyboardInput(): Boolean
