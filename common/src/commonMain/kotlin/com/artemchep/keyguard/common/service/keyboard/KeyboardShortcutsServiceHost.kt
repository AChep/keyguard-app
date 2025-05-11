package com.artemchep.keyguard.common.service.keyboard

import androidx.compose.ui.input.key.KeyEvent

interface KeyboardShortcutsServiceHost {
    fun register(block: (KeyEvent) -> Boolean): () -> Unit
}
