package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementService
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementServiceUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationService
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationServiceUnsupported
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DraftGpgKeyMutationCoordinatorTest {
    @Test
    fun `replacement commits the returned material without changing editor evidence`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val requests = mutableListOf<GpgUserIdReplacementRequest>()
        val coordinator = coordinator(
            guard = guard,
            replacementService = object : GpgUserIdReplacementService {
                override fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult {
                    requests += request
                    return replacementSuccess(changed = true, key = updatedMaterial)
                }
            },
        )

        val outcome = coordinator.replaceUserId(
            snapshot = guard.snapshot(),
            oldIdentityId = oldIdentityId,
            newUserId = newUserId,
        )

        assertIs<DraftGpgKeyMutationOutcome.Complete<GpgUserIdReplacementResult>>(outcome)
        assertEquals(sourceMaterial, requests.single().key)
        assertEquals(candidates, requests.single().candidateRevocationKeys)
        assertEquals(updatedMaterial.privateKeyArmored, sink.value.privateKeyArmored)
        assertEquals(updatedMaterial.publicKeyArmored, sink.value.publicKeyArmored)
        assertEquals(sourceDraft.userId, sink.value.userId)
        assertEquals(sourceDraft.typeLabel, sink.value.typeLabel)
        assertFalse(coordinator.inProgress.value)
    }

    @Test
    fun `no-change revocation preserves ownership of the reviewed snapshot`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val snapshot = guard.snapshot()
        val coordinator = coordinator(
            guard = guard,
            revocationService = object : GpgUserIdRevocationService {
                override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
                    revocationSuccess(changed = false, key = sourceMaterial)
            },
        )

        val outcome = coordinator.revokeUserId(snapshot, oldIdentityId)

        val complete = assertIs<
            DraftGpgKeyMutationOutcome.Complete<GpgUserIdRevocationResult>,
        >(outcome)
        assertFalse(assertIs<GpgUserIdRevocationResult.Success>(complete.result).changed)
        assertTrue(guard.isCurrent(snapshot))
        assertEquals(sourceDraft, sink.value)
    }

    @Test
    fun `a second mutation reports busy while candidate loading is active`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            guard = guard,
            loadCandidateRevocationKeys = {
                started.complete(Unit)
                release.await()
                candidates
            },
            revocationService = object : GpgUserIdRevocationService {
                override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
                    revocationSuccess(changed = false, key = sourceMaterial)
            },
        )
        val snapshot = guard.snapshot()
        val first = async {
            coordinator.revokeUserId(snapshot, oldIdentityId)
        }
        started.await()

        val busy = coordinator.revokeUserId(snapshot, oldIdentityId)

        assertEquals(DraftGpgKeyMutationOutcome.Busy, busy)
        assertTrue(coordinator.inProgress.value)
        release.complete(Unit)
        assertIs<DraftGpgKeyMutationOutcome.Complete<GpgUserIdRevocationResult>>(first.await())
        assertFalse(coordinator.inProgress.value)
    }

    @Test
    fun `expiration replacement and revocation share one busy gate`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            guard = guard,
            loadCandidateRevocationKeys = {
                started.complete(Unit)
                release.await()
                candidates
            },
            expirationService = object : GpgKeyExpirationService {
                override fun update(request: GpgKeyExpirationRequest): GpgKeyExpirationResult =
                    GpgKeyExpirationResult.Success(updatedMaterial)
            },
            replacementService = object : GpgUserIdReplacementService {
                override fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult =
                    error("replacement service should not be called")
            },
            revocationService = object : GpgUserIdRevocationService {
                override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
                    error("revocation service should not be called")
            },
        )
        val snapshot = guard.snapshot()
        val expiration = async {
            coordinator.updateExpiration(snapshot, expirationChange)
        }
        started.await()

        assertEquals(
            DraftGpgKeyMutationOutcome.Busy,
            coordinator.replaceUserId(snapshot, oldIdentityId, newUserId),
        )
        assertEquals(
            DraftGpgKeyMutationOutcome.Busy,
            coordinator.revokeUserId(snapshot, oldIdentityId),
        )

        release.complete(Unit)
        assertIs<DraftGpgKeyMutationOutcome.Complete<GpgKeyExpirationResult>>(expiration.await())
        assertFalse(coordinator.inProgress.value)
    }

    @Test
    fun `stale and ABA snapshots do not reach crypto`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val snapshot = guard.snapshot()
        var invocations = 0
        val coordinator = coordinator(
            guard = guard,
            replacementService = object : GpgUserIdReplacementService {
                override fun replace(request: GpgUserIdReplacementRequest): GpgUserIdReplacementResult {
                    invocations += 1
                    return replacementSuccess(changed = true, key = updatedMaterial)
                }
            },
        )
        guard.replace(sourceDraft.copy(userId = "temporary"))
        guard.replace(sourceDraft)

        val outcome = coordinator.replaceUserId(snapshot, oldIdentityId, newUserId)

        assertEquals(DraftGpgKeyMutationOutcome.Conflict, outcome)
        assertEquals(0, invocations)
        assertEquals(sourceDraft, sink.value)
    }

    @Test
    fun `an edit during crypto wins over the returned key`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val snapshot = guard.snapshot()
        val edited = sourceDraft.copy(userId = "concurrent edit")
        val coordinator = coordinator(
            guard = guard,
            expirationService = object : GpgKeyExpirationService {
                override fun update(request: GpgKeyExpirationRequest): GpgKeyExpirationResult {
                    guard.replace(edited)
                    return GpgKeyExpirationResult.Success(updatedMaterial)
                }
            },
        )

        val outcome = coordinator.updateExpiration(snapshot, expirationChange)

        assertEquals(DraftGpgKeyMutationOutcome.Conflict, outcome)
        assertEquals(edited, sink.value)
    }

    @Test
    fun `unsupported service is reported before candidate loading`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        var loadedCandidates = false
        val coordinator = coordinator(
            guard = guard,
            loadCandidateRevocationKeys = {
                loadedCandidates = true
                candidates
            },
        )

        val outcome = coordinator.replaceUserId(guard.snapshot(), oldIdentityId, newUserId)

        assertEquals(DraftGpgKeyMutationOutcome.Unsupported, outcome)
        assertFalse(loadedCandidates)
        assertFalse(coordinator.inProgress.value)
    }

    @Test
    fun `candidate loading failure returns failed and cancellation propagates`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val ordinaryFailure = coordinator(
            guard = guard,
            loadCandidateRevocationKeys = { error("failed") },
            revocationService = object : GpgUserIdRevocationService {
                override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
                    revocationSuccess(changed = false, key = sourceMaterial)
            },
        )
        assertEquals(
            DraftGpgKeyMutationOutcome.Failed,
            ordinaryFailure.revokeUserId(guard.snapshot(), oldIdentityId),
        )
        assertFalse(ordinaryFailure.inProgress.value)

        val cancellation = CancellationException("draft closed")
        val cancelled = coordinator(
            guard = guard,
            loadCandidateRevocationKeys = { throw cancellation },
            revocationService = object : GpgUserIdRevocationService {
                override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
                    revocationSuccess(changed = false, key = sourceMaterial)
            },
        )
        val actual = assertFailsWith<CancellationException> {
            cancelled.revokeUserId(guard.snapshot(), oldIdentityId)
        }
        assertTrue(actual === cancellation)
        assertFalse(cancelled.inProgress.value)
    }

    @Test
    fun `fatal candidate loading failure propagates`() = runTest {
        val sink = MutableStateFlow(sourceDraft)
        val guard = GpgKeyMutationGuard(sink)
        val fatal = AssertionError("fatal")
        val coordinator = coordinator(
            guard = guard,
            loadCandidateRevocationKeys = { throw fatal },
            revocationService = object : GpgUserIdRevocationService {
                override fun revoke(request: GpgUserIdRevocationRequest): GpgUserIdRevocationResult =
                    error("revocation service should not be called")
            },
        )

        val actual = assertFailsWith<AssertionError> {
            coordinator.revokeUserId(guard.snapshot(), oldIdentityId)
        }

        assertTrue(actual === fatal)
        assertFalse(coordinator.inProgress.value)
    }

    private fun TestScope.coordinator(
        guard: GpgKeyMutationGuard,
        loadCandidateRevocationKeys: suspend (GpgKeyMaterial) -> List<GpgOpenPgpPublicKey> =
            { candidates },
        expirationService: GpgKeyExpirationService = object : GpgKeyExpirationService {
            override fun update(request: GpgKeyExpirationRequest): GpgKeyExpirationResult =
                error("expiration service should not be called")
        },
        replacementService: GpgUserIdReplacementService = GpgUserIdReplacementServiceUnsupported,
        revocationService: GpgUserIdRevocationService = GpgUserIdRevocationServiceUnsupported,
    ) = DraftGpgKeyMutationCoordinator(
        mutations = guard,
        loadCandidateRevocationKeys = loadCandidateRevocationKeys,
        expirationService = expirationService,
        userIdReplacementService = replacementService,
        userIdRevocationService = revocationService,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
    )

    private fun replacementSuccess(
        changed: Boolean,
        key: GpgKeyMaterial,
    ) = GpgUserIdReplacementResult.Success(
        key = key,
        replacementCertificateArmored = if (changed) "replacement certificate" else "",
        changed = changed,
        effectiveAt = Instant.fromEpochSeconds(1_700_000_000),
        oldIdentityId = oldIdentityId,
        newIdentityId = if (changed) "v1:new" else oldIdentityId,
        primaryUserId = if (changed) newUserId else sourceDraft.userId,
    )

    private fun revocationSuccess(
        changed: Boolean,
        key: GpgKeyMaterial,
    ) = GpgUserIdRevocationResult.Success(
        key = key,
        revocationCertificateArmored = if (changed) "revocation certificate" else "",
        changed = changed,
        effectiveAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    private companion object {
        const val oldIdentityId = "v1:old"
        const val newUserId = "New Identity <new@example.test>"
        val metadata = GpgAgentKeyMetadata()
        val sourceMaterial = GpgKeyMaterial(
            privateKeyArmored = "private-source",
            publicKeyArmored = "public-source",
            fingerprint = "PRIMARY",
            metadata = metadata,
        )
        val updatedMaterial = sourceMaterial.copy(
            privateKeyArmored = "private-updated",
            publicKeyArmored = "public-updated",
        )
        val sourceDraft = GeneratedGpgKey(
            privateKeyArmored = sourceMaterial.privateKeyArmored,
            publicKeyArmored = sourceMaterial.publicKeyArmored,
            fingerprint = sourceMaterial.fingerprint,
            metadata = metadata,
            userId = "Source Identity <source@example.test>",
            typeLabel = "OpenPGP",
        )
        val candidates = listOf(GpgOpenPgpPublicKey("candidate"))
        val expirationChange = GpgKeyExpirationChange(
            expiresAt = null,
            componentFingerprints = setOf("PRIMARY"),
        )
    }
}
