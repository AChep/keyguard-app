package com.artemchep.keyguard.platform.input

import android.content.res.Configuration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun isHardwareKeyboardInput(): Boolean {
    val configuration = LocalConfiguration.current
    val attached = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
    // A user may keep the on-screen keyboard while a hardware
    // keyboard is attached; it shows its state itself.
    return attached && !WindowInsets.isImeVisible
}
