package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.apple.KeyguardCore
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class KeyguardVaultStatus {
    LOADING,
    NEEDS_CREATE,
    LOCKED,
    UNLOCKED,
}
/** Mirror of the SwiftUI `ScenePhase` cases the Apple apps report via [KeyguardCore.setScenePhase]. */
enum class KeyguardScenePhase {
    ACTIVE,
    INACTIVE,
    BACKGROUND,
}

/**
 * A handle that lets Swift stop an ongoing [kotlinx.coroutines.flow.Flow]
 * subscription started by one of the `observe*` functions.
 */
class KeyguardCancellable internal constructor(
    private val onCancel: () -> Unit,
) {
    internal constructor(job: Job) : this(onCancel = { job.cancel() })

    fun cancel() {
        onCancel()
    }
}

/** A packed ARGB color as a non-negative 32-bit value, the form Swift decodes. */
internal fun Int.toArgbLong(): Long = toUInt().toLong()

internal fun VaultState.toStatus(): KeyguardVaultStatus = when (this) {
    is VaultState.Loading -> KeyguardVaultStatus.LOADING
    is VaultState.Create -> KeyguardVaultStatus.NEEDS_CREATE
    is VaultState.Unlock -> KeyguardVaultStatus.LOCKED
    is VaultState.Main -> KeyguardVaultStatus.UNLOCKED
}
