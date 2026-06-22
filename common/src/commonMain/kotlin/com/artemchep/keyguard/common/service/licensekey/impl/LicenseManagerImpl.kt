package com.artemchep.keyguard.common.service.licensekey.impl

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaimCandidate
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.licensekey.model.LicenseSource
import com.artemchep.keyguard.common.service.licensekey.model.LicenseStatus
import com.artemchep.keyguard.common.service.licensekey.LicenseManager
import com.artemchep.keyguard.common.service.licensekey.LicenseRepository
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseKeyDecoder
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseKeyMetadata
import com.artemchep.keyguard.common.service.licensekey.entity.LicenseEntitlementEntity
import com.artemchep.keyguard.common.service.settings.VaultSettingsReadWriteRepository
import com.artemchep.keyguard.common.service.settings.entity.LocalClaimedLicenseStateEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalLicenseClaimFailureEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalRedeemedLicenseStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LicenseManagerImpl(
    private val licenseRepository: LicenseRepository,
    private val decoder: Kg2LicenseKeyDecoder,
    private val vaultSettingsReadWriteRepository: VaultSettingsReadWriteRepository,
    private val now: () -> Instant = { Clock.System.now() },
) : LicenseManager {
    companion object {
        private const val INVALID_LICENSE_KEY_REASON = "INVALID_LICENSE_KEY"
        private const val CLAIM_RETRY_DELAY_SECONDS = 6L * 60L * 60L
    }

    private val redeemedMutex = Mutex()

    private val claimedMutex = Mutex()

    override val redeemed: Flow<LicenseEntitlement?> = vaultSettingsReadWriteRepository
        .getRedeemedLicenseState()
        .map { state ->
            state?.toEntitlement()
        }

    override val claimed: Flow<LicenseEntitlement?> = vaultSettingsReadWriteRepository
        .getClaimedLicenseState()
        .map { state ->
            state?.toEntitlement()
        }

    constructor(directDI: DirectDI) : this(
        licenseRepository = directDI.instance(),
        decoder = directDI.instance(),
        vaultSettingsReadWriteRepository = directDI.instance(),
    )

    override fun redeem(
        licenseKey: String,
    ): IO<LicenseEntitlement> = ioEffect(Dispatchers.Default) {
        val normalizedLicenseKey = licenseKey
            .trim()
        val entitlement = entitlement(
            licenseKey = normalizedLicenseKey,
            snapshot = null,
            source = null,
        )
        // Save to the store, if the entitlement
        // was successfully decoded.
        if (entitlement is LicenseEntitlement.Decoded) {
            val entity = LocalRedeemedLicenseStateEntity(
                licenseKey = normalizedLicenseKey,
                snapshot = null,
            )
            vaultSettingsReadWriteRepository
                .setRedeemedLicenseState(entity)
                .bind()
        }

        entitlement
    }

    override fun refreshRedeemedIfNeeded(
        force: Boolean,
    ): IO<LicenseEntitlement?> = ioEffect(Dispatchers.Default) {
        redeemedMutex.withLock {
            refreshRedeemedIfNeededLocked(force = force)
        }
    }

    private suspend fun refreshRedeemedIfNeededLocked(
        force: Boolean,
    ): LicenseEntitlement? {
        val entitlement = redeemed
            .first()
        if (entitlement !is LicenseEntitlement.Decoded) {
            return null
        }

        // Check if the entitlement should be refreshed
        // or if we can keep the previous one.
        if (!force && !entitlement.shouldRefresh(now())) {
            return entitlement
        }

        val newEntitlementEntity = when (
            val result = licenseRepository
                .status(entitlement.licenseKey)
                .attempt()
                .bind()
        ) {
            is Either.Left -> return entitlement // failed to refresh, keep the current one
            is Either.Right -> result.value
        }
        val newLicenseKey = newEntitlementEntity.licenseKey
            ?.trim()
            ?: entitlement.licenseKey

        val entity = if (decoder.decodeOrNull(newLicenseKey) != null) {
            LocalRedeemedLicenseStateEntity(
                licenseKey = newLicenseKey,
                snapshot = newEntitlementEntity,
            )
        } else {
            null // clear
        }
        vaultSettingsReadWriteRepository
            .setRedeemedLicenseState(entity)
            .bind()
        return entity?.toEntitlement()
    }

    override fun clearRedeemed(): IO<Unit> = ioEffect(Dispatchers.Default) {
        vaultSettingsReadWriteRepository
            .setRedeemedLicenseState(null)
            .bind()
    }

    override fun claim(
        claim: LicenseClaimCandidate,
        force: Boolean,
    ): IO<LicenseEntitlement?> = ioEffect(Dispatchers.Default) {
        claimedMutex.withLock {
            refreshRedeemedIfNeededLocked(
                claim = claim,
                force = force,
            )
        }
    }

    private suspend fun refreshRedeemedIfNeededLocked(
        claim: LicenseClaimCandidate,
        force: Boolean,
    ): LicenseEntitlement? {
        val now = now()
        val source = claim.source
        val sourceFingerprint = source.fingerprint

        val cur = claimed.first()
        val currentState = vaultSettingsReadWriteRepository
            .getClaimedLicenseState()
            .first()
        val currentEntitlement = currentState
            ?.toEntitlement()
        val currentSourceMatches =
            cur?.source?.fingerprint == sourceFingerprint

        if (!force && currentSourceMatches) {
            val decodedEntitlement = cur as? LicenseEntitlement.Decoded
            if (decodedEntitlement?.shouldRefresh(now) == false) {
                return currentEntitlement
            }
        }

        if (!force) {
            val recentFailure = vaultSettingsReadWriteRepository
                .getLicenseClaimFailure()
                .first()
                ?.isRecentFor(sourceFingerprint, now)
                ?: false
            if (recentFailure) {
                return currentEntitlement
                    .takeIf { currentSourceMatches }
            }
        }

        val snapshot = when (
            val result = licenseRepository
                .claim(claim.claim)
                .attempt()
                .bind()
        ) {
            is Either.Left -> {
                val failure = LocalLicenseClaimFailureEntity(
                    sourceFingerprint = sourceFingerprint,
                    failedAt = now.toString(),
                )
                vaultSettingsReadWriteRepository
                    .setClaimedLicenseAttemptFailure(failure)
                    .bind()
                throw result.value
            }

            is Either.Right -> result.value
        }

        vaultSettingsReadWriteRepository
            .setClaimedLicenseAttemptFailure(null)
            .bind()

        val serverLicenseKey = snapshot.licenseKey
            ?.trim()
            ?.takeIf(::canDecode)
        val storedLicenseKey = currentState
            ?.licenseKey
            ?.trim()
            ?.takeIf { currentSourceMatches }
            ?.takeIf(::canDecode)
        val updatedLicenseKey = serverLicenseKey ?: storedLicenseKey
        if (updatedLicenseKey == null) {
            vaultSettingsReadWriteRepository
                .setClaimedLicenseState(null)
                .bind()
            return null
        }

        val updatedState = LocalClaimedLicenseStateEntity(
            licenseKey = updatedLicenseKey,
            snapshot = snapshot,
            source = source,
        )
        vaultSettingsReadWriteRepository
            .setClaimedLicenseState(updatedState)
            .bind()
        return updatedState.toEntitlement()
    }

    private fun LocalRedeemedLicenseStateEntity.toEntitlement(): LicenseEntitlement =
        entitlement(
            licenseKey = licenseKey,
            snapshot = snapshot,
            source = null,
        )

    private fun LocalClaimedLicenseStateEntity.toEntitlement(): LicenseEntitlement =
        entitlement(
            licenseKey = licenseKey,
            snapshot = snapshot,
            source = source,
        )

    private fun entitlement(
        licenseKey: String,
        snapshot: LicenseEntitlementEntity?,
        source: LicenseSource?,
    ): LicenseEntitlement {
        val normalizedLicenseKey = licenseKey.trim()
        val metadata = decoder.decodeOrNull(normalizedLicenseKey)
            ?: return LicenseEntitlement.Undecodable(
                licenseKey = normalizedLicenseKey,
                source = source,
                reason = snapshot?.reason ?: INVALID_LICENSE_KEY_REASON,
            )

        return LicenseEntitlement.Decoded(
            licenseKey = normalizedLicenseKey,
            licenseId = metadata.licenseId,
            tier = metadata.tier,
            productKind = metadata.productKind,
            expiry = metadata.expiry(),
            status = snapshot
                ?.let { LicenseStatus.of(it.status) }
                ?: LicenseStatus.ACTIVE,
            checkAfter = snapshot?.checkAfter.toInstantOrNull(),
            reason = snapshot?.reason,
            source = source,
        )
    }

    private fun Kg2LicenseKeyMetadata.expiry(): LicenseEntitlement.Expiry =
        if (isLifetime) {
            LicenseEntitlement.Expiry.Lifetime
        } else {
            LicenseEntitlement.Expiry.PaidThrough(expiryYearMonth)
        }

    private fun canDecode(licenseKey: String): Boolean =
        decoder.decodeOrNull(licenseKey) != null

    private fun LicenseEntitlement.Decoded.shouldRefresh(
        now: Instant,
    ): Boolean = checkAfter == null || checkAfter <= now

    private fun LocalLicenseClaimFailureEntity.isRecentFor(
        sourceFingerprint: String,
        now: Instant,
    ): Boolean {
        if (this.sourceFingerprint != sourceFingerprint) {
            return false
        }
        val failedAt = failedAt.toInstantOrNull()
            ?: return false
        return failedAt + CLAIM_RETRY_DELAY_SECONDS.seconds > now
    }

    private fun String?.toInstantOrNull(): Instant? =
        this?.let { value ->
            runCatching {
                Instant.parse(value)
            }.getOrNull()
        }
}