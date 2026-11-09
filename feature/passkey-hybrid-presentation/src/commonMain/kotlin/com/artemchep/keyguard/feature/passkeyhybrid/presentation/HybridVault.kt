package com.artemchep.keyguard.feature.passkeyhybrid.presentation

import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnCredential
import kotlinx.coroutines.flow.StateFlow

/** A lease on one unlocked vault. An implementation must also reject replacement sessions. */
interface HybridVaultSession {
    val active: StateFlow<Boolean>

    fun requireActive()
}

data class HybridSaveTarget(
    val accountId: String,
    val cipherId: String,
)

class HybridWriteException : IllegalStateException("Registration target is not writable")

/** Application adapters own vault models, visibility, write policy, and durable persistence. */
interface HybridVault {
    suspend fun openSession(): HybridVaultSession?

    suspend fun candidates(request: WebAuthnAssertionHashRequest): List<HybridCandidate>

    /** Re-read after verification; return null for a removed or unavailable credential. */
    suspend fun credential(candidate: HybridCandidate): WebAuthnCredential?

    suspend fun requireWritable(target: HybridSaveTarget)

    /** Include hidden and archived credentials for registration exclusion checks. */
    suspend fun credentials(): List<WebAuthnCredential>

    /** Recheck the target and write access before saving. Success means durable local storage. */
    suspend fun save(
        target: HybridSaveTarget,
        credential: WebAuthnCredential,
    ): Boolean

    /** History failures must not invalidate an already delivered assertion. */
    suspend fun recordUsage(candidate: HybridCandidate)
}
