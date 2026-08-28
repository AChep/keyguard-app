package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysRequest
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.model.canEdit
import com.artemchep.keyguard.common.service.crypto.GpgCertificateMaterialReconciler
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverStateRepository
import com.artemchep.keyguard.common.service.gpgkeyserver.gpgKeyserverRefreshFingerprintOrNull
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverLastRefresh
import com.artemchep.keyguard.common.usecase.RefreshGpgPublicKeys
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.Instant

class RefreshGpgPublicKeysImpl(
    private val getCiphers: GetCiphers,
    private val getGpgKeyserverConfig: GetGpgKeyserverConfig,
    private val putGpgKeyserverLastRefresh: PutGpgKeyserverLastRefresh,
    private val keyserverClient: GpgKeyserverClient,
    private val keyserverStateRepository: GpgKeyserverStateRepository,
    private val modifyCipherById: ModifyCipherById,
    private val gpgKeyMetadataResolver: GpgKeyMetadataResolver,
    private val certificateMaterialReconciler: GpgCertificateMaterialReconciler,
) : RefreshGpgPublicKeys {
    constructor(directDI: DirectDI) : this(
        getCiphers = directDI.instance(),
        getGpgKeyserverConfig = directDI.instance(),
        putGpgKeyserverLastRefresh = directDI.instance(),
        keyserverClient = directDI.instance(),
        keyserverStateRepository = directDI.instance(),
        modifyCipherById = directDI.instance(),
        gpgKeyMetadataResolver = directDI.instance(),
        certificateMaterialReconciler = directDI.instance(),
    )

    override fun invoke(
        request: RefreshGpgPublicKeysRequest,
    ): IO<RefreshGpgPublicKeysResult> = ioEffect(Dispatchers.Default) {
        if (request.cipherIds.isEmpty()) {
            return@ioEffect RefreshGpgPublicKeysResult(0, 0, 0)
        }
        val targets = getCiphers().first()
            .filter { cipher ->
                cipher.id in request.cipherIds &&
                    request.accountId?.let { it == cipher.accountId } != false
            }
            .mapNotNull(::toRefreshTarget)
        val skipped = request.cipherIds.size - targets.map { it.cipherId }.toSet().size
        if (targets.isEmpty()) {
            return@ioEffect RefreshGpgPublicKeysResult(0, 0, skipped)
        }

        val config = getGpgKeyserverConfig().first()
        val lookups = targets.map { it.fingerprint }.distinct().associateWith { fingerprint ->
            runCatchingNonFatal {
                keyserverClient.getByFingerprint(fingerprint, config).bind()
            }
        }
        val outcomes = targets.associateWith { target ->
            lookups.getValue(target.fingerprint).fold(
                onSuccess = { result ->
                    RefreshOutcome.Pending(result)
                },
                onFailure = { RefreshOutcome.Failed },
            )
        }.toMutableMap()
        applyRefreshes(outcomes)

        val now = Clock.System.now()
        outcomes.forEach { (target, outcome) ->
            when (outcome) {
                is RefreshOutcome.Refreshed -> recordOutcome(target, outcome.result, config, now)
                RefreshOutcome.NotFound -> recordOutcome(target, null, config, now)
                RefreshOutcome.Failed, is RefreshOutcome.Pending -> Unit
            }
        }
        val refreshed = outcomes.values.count { it is RefreshOutcome.Refreshed }
        val notFound = outcomes.values.count { it is RefreshOutcome.NotFound }
        if (refreshed + notFound > 0) {
            putGpgKeyserverLastRefresh(now).bind()
        }
        RefreshGpgPublicKeysResult(
            refreshed = refreshed,
            notFound = notFound,
            skipped = skipped,
            failed = outcomes.values.count { it is RefreshOutcome.Failed },
        )
    }

    private suspend fun applyRefreshes(
        outcomes: MutableMap<RefreshTarget, RefreshOutcome>,
    ) {
        val pending = outcomes.filterValues { it is RefreshOutcome.Pending }
        if (pending.isEmpty()) return
        val targetsById = pending.keys.associateBy { it.cipherId }
        modifyCipherById(cipherIds = targetsById.keys) { model ->
            val target = targetsById.getValue(model.cipherId)
            val result = (pending.getValue(target) as RefreshOutcome.Pending).result
            val current = model.data_
            val key = current.gpgKeyForRefresh(target.accountId, target.fingerprint)
            if (key == null) {
                outcomes[target] = RefreshOutcome.Failed
                return@modifyCipherById model
            }
            if (result == null) {
                outcomes[target] = RefreshOutcome.NotFound
                return@modifyCipherById model
            }
            val refreshed = key.withGpgKeyserverRefresh(
                expectedPrimaryFingerprint = target.fingerprint,
                result = result,
                reconciler = certificateMaterialReconciler,
                resolver = gpgKeyMetadataResolver,
            )
            if (refreshed == null) {
                outcomes[target] = RefreshOutcome.Failed
                model
            } else {
                // An accepted identical certificate is a successful refresh, but the existing
                // mutation helper skips its write and does not bump the item's revision.
                outcomes[target] = RefreshOutcome.Refreshed(result)
                model.copy(data_ = current.copy(gpgKey = refreshed))
            }
        }.bind()
        // Missing or non-editable rows never reach the transform.
        pending.keys.forEach { target ->
            if (outcomes[target] is RefreshOutcome.Pending) {
                outcomes[target] = RefreshOutcome.Failed
            }
        }
    }

    private suspend fun recordOutcome(
        target: RefreshTarget,
        result: DGpgKeyserverResult?,
        config: GpgKeyserverConfig,
        now: Instant,
    ) {
        val current = keyserverStateRepository.getByFingerprint(target.fingerprint).first()
        val status = when {
            result?.revoked == true || current?.verificationStatus == GpgKeyserverVerificationStatus.REVOKED ->
                GpgKeyserverVerificationStatus.REVOKED
            result == null -> GpgKeyserverVerificationStatus.NOT_FOUND
            current?.verificationStatus == GpgKeyserverVerificationStatus.VERIFIED ->
                GpgKeyserverVerificationStatus.VERIFIED
            else -> GpgKeyserverVerificationStatus.FOUND_UNVERIFIED
        }
        keyserverStateRepository.put(
            DGpgKeyserverState(
                fingerprint = target.fingerprint,
                cipherId = target.cipherId,
                verificationStatus = status,
                lastCheckedAt = now,
                lastRefreshedAt = if (result != null) now else current?.lastRefreshedAt,
                sourceKeyserver = result?.sourceKeyserver ?: current?.sourceKeyserver ?: config.url,
            ),
        ).bind()
    }

    private fun toRefreshTarget(cipher: DSecret): RefreshTarget? {
        if (cipher.deleted || cipher.service.deleted || !cipher.canEdit()) return null
        return cipher.gpgKeyserverRefreshFingerprintOrNull()?.let { fingerprint ->
            RefreshTarget(
                cipherId = cipher.id,
                accountId = cipher.accountId,
                fingerprint = fingerprint,
            )
        }
    }
}

private data class RefreshTarget(
    val cipherId: String,
    val accountId: String,
    val fingerprint: String,
)

private sealed interface RefreshOutcome {
    data class Pending(val result: DGpgKeyserverResult?) : RefreshOutcome
    data class Refreshed(val result: DGpgKeyserverResult) : RefreshOutcome
    data object NotFound : RefreshOutcome
    data object Failed : RefreshOutcome
}
