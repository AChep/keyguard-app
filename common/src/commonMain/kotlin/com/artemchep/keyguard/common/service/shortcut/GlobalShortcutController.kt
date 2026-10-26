package com.artemchep.keyguard.common.service.shortcut

import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import kotlinx.coroutines.flow.StateFlow

interface GlobalShortcutController {
    val state: StateFlow<GlobalShortcutRuntimeState>

    /** Applies [transform] to the [action]'s latest saved configuration. */
    suspend fun update(
        action: GlobalShortcutAction,
        transform: (GlobalShortcutConfig) -> GlobalShortcutConfig,
    ): GlobalShortcutError?

    /** Unregisters every shortcut until the returned recording is released. */
    suspend fun suspendRecording(): GlobalShortcutRecording
}
