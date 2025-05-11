package com.artemchep.keyguard.common.service.keyboard

import androidx.compose.ui.input.key.KeyEvent

interface KeyboardShortcutsService {
    fun handle(keyEvent: KeyEvent): Boolean
}
