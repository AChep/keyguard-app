package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.service.patch.ModelDiffUtil
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.getMergeRules
import com.artemchep.keyguard.provider.bitwarden.usecase.util.with3WayMergePasswordHistoryOrNull
import kotlin.time.Instant

private val cipherMergeRules by lazy { BitwardenCipher.getMergeRules() }

internal data class CipherConflictResolution(
    val cipher: BitwardenCipher,
    val requiresRemoteWrite: Boolean,
    val mode: Mode,
) {
    enum class Mode {
        ThreeWay,
        RemoteFallback,
    }
}

/**
 * Resolves a cipher changed both locally and remotely.
 *
 * The field-level rules intentionally remain provider-independent so Bitwarden
 * and KeePass resolve the same conflict in the same way. Provider operations
 * are still responsible for choosing whether their remote format can durably
 * store displaced secrets, decoding the remote model, and publishing
 * [CipherConflictResolution.cipher].
 */
internal fun resolveCipherConflict(
    base: BitwardenCipher?,
    local: BitwardenCipher,
    remote: BitwardenCipher,
    at: Instant,
    preserveDisplacedSecretsInPasswordHistory: Boolean,
): CipherConflictResolution {
    if (base != null) {
        val merged =
            with(ModelDiffUtil()) {
                cipherMergeRules.merge(base, local, remote)
            } as BitwardenCipher?
        if (merged != null) {
            // TODO: Password history merge re-introduces deleted password-history
            //  entries during conflict merge. A remote/user deletion can be undone
            //  and uploaded again if the local side still has that base entry.
            val withHistory = if (preserveDisplacedSecretsInPasswordHistory) {
                merged.with3WayMergePasswordHistoryOrNull(
                    at = at,
                    remote,
                    local,
                ) ?: merged
            } else {
                merged
            }
            return CipherConflictResolution(
                cipher = withHistory.copy(revisionDate = at),
                requiresRemoteWrite = true,
                mode = CipherConflictResolution.Mode.ThreeWay,
            )
        }
    }

    val fallbackWithHistory = if (preserveDisplacedSecretsInPasswordHistory) {
        remote.with3WayMergePasswordHistoryOrNull(
            at = at,
            local,
        )
    } else {
        null
    }
    return CipherConflictResolution(
        cipher = fallbackWithHistory?.copy(revisionDate = at) ?: remote,
        requiresRemoteWrite = fallbackWithHistory != null,
        mode = CipherConflictResolution.Mode.RemoteFallback,
    )
}
