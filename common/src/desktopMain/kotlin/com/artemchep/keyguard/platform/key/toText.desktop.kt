package com.artemchep.keyguard.platform.key

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import java.awt.event.KeyEvent

actual fun Key.toText(): String =
    KeyEvent.getKeyText(nativeKeyCode)
