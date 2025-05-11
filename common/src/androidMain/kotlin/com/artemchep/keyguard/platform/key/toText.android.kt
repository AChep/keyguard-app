package com.artemchep.keyguard.platform.key

import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode

actual fun Key.toText(): String =
    KeyEvent.keyCodeToString(nativeKeyCode)
