package com.artemchep.keyguard.feature.home.settings.shortcuts

import androidx.compose.ui.input.key.KeyEvent

internal actual val KeyEvent.isAltGraphPressed: Boolean
    get() = (nativeKeyEvent as? java.awt.event.KeyEvent)?.isAltGraphDown == true
