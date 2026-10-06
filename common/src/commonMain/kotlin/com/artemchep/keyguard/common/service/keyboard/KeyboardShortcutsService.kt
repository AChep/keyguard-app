package com.artemchep.keyguard.common.service.keyboard

import androidx.compose.ui.input.key.KeyEvent
import com.artemchep.keyguard.platform.WindowId

interface KeyboardShortcutsService {
    fun handle(windowId: WindowId, keyEvent: KeyEvent): Boolean
}
