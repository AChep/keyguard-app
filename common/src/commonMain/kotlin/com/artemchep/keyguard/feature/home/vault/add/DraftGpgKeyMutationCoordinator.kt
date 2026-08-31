package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.model.toGpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementService
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Serializes cryptographic mutations of one editable GPG draft.
 *
 * Dialog-driven operations retain the snapshot that the user reviewed. A result is published only
 * if that exact generation still owns the draft, which also rejects ABA edits.
 */
internal class DraftGpgKeyMutationCoordinator(
    private val mutations: GpgKeyMutationGuard,
    private val loadCandidateRevocationKeys: suspend (GpgKeyMaterial) -> List<GpgOpenPgpPublicKey>,
    private val expirationService: GpgKeyExpirationService,
    private val userIdReplacementService: GpgUserIdReplacementService,
    private val userIdRevocationService: GpgUserIdRevocationService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableInProgress = MutableStateFlow(false)

    val inProgress: StateFlow<Boolean> = mutableInProgress.asStateFlow()

    suspend fun updateExpiration(
        snapshot: GpgKeyMutationGuard.Snapshot,
        change: GpgKeyExpirationChange,
    ): DraftGpgKeyMutationOutcome<GpgKeyExpirationResult> = mutate(
        expectedSnapshot = snapshot,
        supported = expirationService.isSupported,
        operation = { sourceKey, candidates ->
            expirationService.update(
                GpgKeyExpirationRequest(
                    key = sourceKey,
                    change = change,
                    candidateRevocationKeys = candidates,
                ),
            )
        },
        updatedKey = { result ->
            (result as? GpgKeyExpirationResult.Success)?.key
        },
    )

    suspend fun replaceUserId(
        snapshot: GpgKeyMutationGuard.Snapshot,
        oldIdentityId: String,
        newUserId: String,
    ): DraftGpgKeyMutationOutcome<GpgUserIdReplacementResult> = mutate(
        expectedSnapshot = snapshot,
        supported = userIdReplacementService.isSupported,
        operation = { sourceKey, candidates ->
            userIdReplacementService.replace(
                GpgUserIdReplacementRequest(
                    key = sourceKey,
                    oldIdentityId = oldIdentityId,
                    newUserId = newUserId,
                    candidateRevocationKeys = candidates,
                ),
            )
        },
        updatedKey = { result ->
            (result as? GpgUserIdReplacementResult.Success)
                ?.takeIf { it.changed }
                ?.key
        },
    )

    suspend fun revokeUserId(
        snapshot: GpgKeyMutationGuard.Snapshot,
        identityId: String,
    ): DraftGpgKeyMutationOutcome<GpgUserIdRevocationResult> = mutate(
        expectedSnapshot = snapshot,
        supported = userIdRevocationService.isSupported,
        operation = { sourceKey, candidates ->
            userIdRevocationService.revoke(
                GpgUserIdRevocationRequest(
                    key = sourceKey,
                    identityId = identityId,
                    candidateRevocationKeys = candidates,
                ),
            )
        },
        updatedKey = { result ->
            (result as? GpgUserIdRevocationResult.Success)
                ?.takeIf { it.changed }
                ?.key
        },
    )

    private suspend fun <T> mutate(
        expectedSnapshot: GpgKeyMutationGuard.Snapshot,
        supported: Boolean,
        operation: suspend (GpgKeyMaterial, List<GpgOpenPgpPublicKey>) -> T,
        updatedKey: (T) -> GpgKeyMaterial?,
    ): DraftGpgKeyMutationOutcome<T> {
        return when {
            !supported -> DraftGpgKeyMutationOutcome.Unsupported
            !mutableInProgress.compareAndSet(expect = false, update = true) ->
                DraftGpgKeyMutationOutcome.Busy

            else -> try {
                if (!mutations.isCurrent(expectedSnapshot)) {
                    DraftGpgKeyMutationOutcome.Conflict
                } else {
                    val sourceKey = expectedSnapshot.key.toGpgKeyMaterial()
                    withContext(dispatcher) {
                        runCatchingNonFatal {
                            operation(sourceKey, loadCandidateRevocationKeys(sourceKey))
                        }
                    }.fold(
                        onSuccess = { result ->
                            val material = updatedKey(result)
                            val remainsCurrent = if (material != null) {
                                mutations.commitMaterial(expectedSnapshot, material)
                            } else {
                                mutations.isCurrent(expectedSnapshot)
                            }
                            if (remainsCurrent) {
                                DraftGpgKeyMutationOutcome.Complete(result)
                            } else {
                                DraftGpgKeyMutationOutcome.Conflict
                            }
                        },
                        onFailure = {
                            DraftGpgKeyMutationOutcome.Failed
                        },
                    )
                }
            } finally {
                mutableInProgress.value = false
            }
        }
    }
}

internal sealed interface DraftGpgKeyMutationOutcome<out T> {
    data object Busy : DraftGpgKeyMutationOutcome<Nothing>

    data object Conflict : DraftGpgKeyMutationOutcome<Nothing>

    data object Unsupported : DraftGpgKeyMutationOutcome<Nothing>

    data object Failed : DraftGpgKeyMutationOutcome<Nothing>

    data class Complete<T>(
        val result: T,
    ) : DraftGpgKeyMutationOutcome<T>
}
