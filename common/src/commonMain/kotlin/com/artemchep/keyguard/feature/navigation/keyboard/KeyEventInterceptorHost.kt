package com.artemchep.keyguard.feature.navigation.keyboard

import androidx.compose.ui.input.key.KeyEvent

interface KeyEventInterceptorHost {
    /**
     * Invoke to add the key event interceptor. The [block] function will be
     * called instead of altering the navigation stack. To remove the interceptor
     * call the returned lambda.
     */
    fun interceptKeyEvent(
        block: (KeyEvent) -> Boolean,
    ): () -> Unit
}
