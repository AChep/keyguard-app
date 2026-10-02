package com.artemchep.keyguard.apple.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Prompts compete for one process-wide host; rejected prompts must never queue for later. */
internal class AuthPromptCoordinator {
    private val active = MutableStateFlow(false)
    val isActive = active.asStateFlow()

    suspend fun run(onRejected: () -> Unit = {}, block: suspend () -> Unit) {
        if (!active.compareAndSet(false, true)) {
            onRejected()
            return
        }
        try {
            block()
        } finally {
            active.value = false
        }
    }
}
