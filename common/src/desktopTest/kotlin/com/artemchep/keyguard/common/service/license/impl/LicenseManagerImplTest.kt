package com.artemchep.keyguard.common.service.license.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.VaultSettingsKeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.impl.JsonKeyValueStore
import com.artemchep.keyguard.common.service.licensekey.LicenseRepository
import com.artemchep.keyguard.common.service.licensekey.entity.LicenseEntitlementEntity
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaim
import com.artemchep.keyguard.common.service.licensekey.model.LicenseClaimCandidate
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.licensekey.model.LicenseSource
import com.artemchep.keyguard.common.service.licensekey.model.LicenseStatus
import com.artemchep.keyguard.common.service.licensekey.decoder.KeyguardKg2LicensePublicKeys
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseKeyDecoder
import com.artemchep.keyguard.common.service.licensekey.decoder.Kg2LicenseProductKind
import com.artemchep.keyguard.common.service.licensekey.LicenseSignatureVerifier
import com.artemchep.keyguard.common.service.licensekey.impl.LicenseManagerImpl
import com.artemchep.keyguard.common.service.settings.entity.LocalClaimedLicenseStateEntity
import com.artemchep.keyguard.common.service.settings.entity.LocalRedeemedLicenseStateEntity
import com.artemchep.keyguard.common.service.settings.impl.VaultSettingsRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LicenseManagerImplTest {
    @Test
    fun `valid redeem persists decoded active entitlement`() = runTest {
        val fixture = createFixture()

        val entitlement = fixture.manager
            .redeem("  $SUBSCRIPTION_LICENSE_KEY  ")
            .bind()

        val decoded = assertIs<LicenseEntitlement.Decoded>(entitlement)
        assertEquals(SUBSCRIPTION_LICENSE_KEY, decoded.licenseKey)
        assertEquals("ABCDEFGHJKLMNPQR", decoded.licenseId)
        assertEquals("premium", decoded.tier)
        assertEquals(Kg2LicenseProductKind.Subscription, decoded.productKind)
        assertEquals(LicenseEntitlement.Expiry.PaidThrough("2026-07"), decoded.expiry)
        assertEquals(LicenseStatus.ACTIVE, decoded.status)
        assertNull(decoded.source)

        val stored = fixture.settings
            .getRedeemedLicenseState()
            .first()
        assertEquals(SUBSCRIPTION_LICENSE_KEY, stored?.licenseKey)
        assertNull(stored?.snapshot)

        assertIs<LicenseEntitlement.Decoded>(
            fixture.manager.redeemed.first(),
        )
    }

    @Test
    fun `invalid redeem returns undecodable and leaves stored redeemed state empty`() = runTest {
        val fixture = createFixture()

        val entitlement = fixture.manager
            .redeem("not-a-license")
            .bind()

        val undecodable = assertIs<LicenseEntitlement.Undecodable>(entitlement)
        assertEquals("not-a-license", undecodable.licenseKey)
        assertEquals(LicenseStatus.INVALID, undecodable.status)
        assertEquals("INVALID_LICENSE_KEY", undecodable.reason)
        assertNull(fixture.settings.getRedeemedLicenseState().first())
        assertNull(fixture.manager.redeemed.first())
    }

    @Test
    fun `refresh skips redeemed license with fresh snapshot`() = runTest {
        val fixture = createFixture()
        val freshSnapshot = entitlementEntity(
            licenseKey = SUBSCRIPTION_LICENSE_KEY,
            status = "active",
            checkAfter = TEST_NOW + 1.hours,
        )
        fixture.settings
            .setRedeemedLicenseState(
                LocalRedeemedLicenseStateEntity(
                    licenseKey = SUBSCRIPTION_LICENSE_KEY,
                    snapshot = freshSnapshot,
                ),
            )
            .bind()

        val entitlement = fixture.manager
            .refreshRedeemedIfNeeded(force = false)
            .bind()

        val decoded = assertIs<LicenseEntitlement.Decoded>(entitlement)
        assertEquals(LicenseStatus.ACTIVE, decoded.status)
        assertEquals(emptyList(), fixture.repository.statusCalls)
        assertEquals(freshSnapshot, fixture.settings.getRedeemedLicenseState().first()?.snapshot)
    }

    @Test
    fun `refresh updates stale redeemed snapshot and adopts decodable server key`() = runTest {
        val fixture = createFixture()
        fixture.settings
            .setRedeemedLicenseState(
                LocalRedeemedLicenseStateEntity(
                    licenseKey = SUBSCRIPTION_LICENSE_KEY,
                    snapshot = entitlementEntity(
                        licenseKey = SUBSCRIPTION_LICENSE_KEY,
                        status = "active",
                        checkAfter = TEST_NOW - 1.seconds,
                    ),
                ),
            )
            .bind()
        fixture.repository.enqueueStatus(
            entitlementEntity(
                licenseKey = LIFETIME_LICENSE_KEY,
                status = "active",
                checkAfter = TEST_NOW + 2.hours,
            ),
        )

        val entitlement = fixture.manager
            .refreshRedeemedIfNeeded(force = false)
            .bind()

        val decoded = assertIs<LicenseEntitlement.Decoded>(entitlement)
        assertEquals(LIFETIME_LICENSE_KEY, decoded.licenseKey)
        assertEquals(Kg2LicenseProductKind.Lifetime, decoded.productKind)
        assertEquals(LicenseEntitlement.Expiry.Lifetime, decoded.expiry)
        assertEquals(listOf(SUBSCRIPTION_LICENSE_KEY), fixture.repository.statusCalls)
        assertEquals(LIFETIME_LICENSE_KEY, fixture.settings.getRedeemedLicenseState().first()?.licenseKey)
    }

    @Test
    fun `refresh keeps previous redeemed state on repository failure`() = runTest {
        val fixture = createFixture()
        val previousSnapshot = entitlementEntity(
            licenseKey = SUBSCRIPTION_LICENSE_KEY,
            status = "active",
            checkAfter = TEST_NOW - 1.seconds,
        )
        fixture.settings
            .setRedeemedLicenseState(
                LocalRedeemedLicenseStateEntity(
                    licenseKey = SUBSCRIPTION_LICENSE_KEY,
                    snapshot = previousSnapshot,
                ),
            )
            .bind()
        fixture.repository.enqueueStatus(IllegalStateException("status failed"))

        val entitlement = fixture.manager
            .refreshRedeemedIfNeeded(force = false)
            .bind()

        val decoded = assertIs<LicenseEntitlement.Decoded>(entitlement)
        assertEquals(LicenseStatus.ACTIVE, decoded.status)
        assertEquals(previousSnapshot, fixture.settings.getRedeemedLicenseState().first()?.snapshot)
    }

    @Test
    fun `refresh persists negative redeemed status without replacing stored key`() = runTest {
        val fixture = createFixture()
        fixture.settings
            .setRedeemedLicenseState(
                LocalRedeemedLicenseStateEntity(
                    licenseKey = SUBSCRIPTION_LICENSE_KEY,
                    snapshot = null,
                ),
            )
            .bind()
        val revokedSnapshot = entitlementEntity(
            licenseKey = null,
            status = "revoked",
            checkAfter = null,
            reason = "REFUNDED",
        )
        fixture.repository.enqueueStatus(revokedSnapshot)

        val entitlement = fixture.manager
            .refreshRedeemedIfNeeded(force = false)
            .bind()

        val decoded = assertIs<LicenseEntitlement.Decoded>(entitlement)
        assertEquals(SUBSCRIPTION_LICENSE_KEY, decoded.licenseKey)
        assertEquals(LicenseStatus.REVOKED, decoded.status)
        assertEquals("REFUNDED", decoded.reason)
        val stored = fixture.settings.getRedeemedLicenseState().first()
        assertEquals(SUBSCRIPTION_LICENSE_KEY, stored?.licenseKey)
        assertEquals(revokedSnapshot, stored?.snapshot)
    }

    @Test
    fun `claim persists source metadata and skips fresh non-forced claim`() = runTest {
        val fixture = createFixture()
        fixture.repository.enqueueClaim(
            entitlementEntity(
                licenseKey = SUBSCRIPTION_LICENSE_KEY,
                status = "active",
                checkAfter = TEST_NOW + 1.hours,
            ),
        )

        val entitlement = fixture.manager
            .claim(TEST_CLAIM)
            .bind()

        val decoded = assertIs<LicenseEntitlement.Decoded>(entitlement)
        assertEquals(TEST_SOURCE, decoded.source)
        assertEquals(LicenseStatus.ACTIVE, decoded.status)
        val stored = assertNotNull(fixture.settings.getClaimedLicenseState().first())
        assertEquals(SUBSCRIPTION_LICENSE_KEY, stored.licenseKey)
        assertEquals(TEST_SOURCE, stored.source)
        assertNull(fixture.settings.getLicenseClaimFailure().first())

        val skipped = fixture.manager
            .claim(TEST_CLAIM, force = false)
            .bind()

        assertIs<LicenseEntitlement.Decoded>(skipped)
        assertEquals(1, fixture.repository.claimCalls.size)
    }

    @Test
    fun `claim stores recent failure throttles non-forced retry and force bypasses throttle`() = runTest {
        val fixture = createFixture()
        fixture.repository.enqueueClaim(IllegalStateException("claim failed"))

        assertFailsWith<IllegalStateException> {
            fixture.manager
                .claim(TEST_CLAIM)
                .bind()
        }

        val failure = assertNotNull(fixture.settings.getLicenseClaimFailure().first())
        assertEquals(TEST_SOURCE.fingerprint, failure.sourceFingerprint)
        assertEquals(TEST_NOW.toString(), failure.failedAt)

        val throttled = fixture.manager
            .claim(TEST_CLAIM, force = false)
            .bind()
        assertNull(throttled)
        assertEquals(1, fixture.repository.claimCalls.size)

        fixture.repository.enqueueClaim(
            entitlementEntity(
                licenseKey = SUBSCRIPTION_LICENSE_KEY,
                status = "active",
                checkAfter = TEST_NOW + 1.hours,
            ),
        )
        val forced = fixture.manager
            .claim(TEST_CLAIM, force = true)
            .bind()

        assertIs<LicenseEntitlement.Decoded>(forced)
        assertEquals(2, fixture.repository.claimCalls.size)
        assertNull(fixture.settings.getLicenseClaimFailure().first())
    }

    @Test
    fun `claim success without key keeps same source stored key and updates snapshot`() = runTest {
        val fixture = createFixture()
        fixture.settings
            .setClaimedLicenseState(
                LocalClaimedLicenseStateEntity(
                    licenseKey = SUBSCRIPTION_LICENSE_KEY,
                    snapshot = entitlementEntity(
                        licenseKey = SUBSCRIPTION_LICENSE_KEY,
                        status = "active",
                        checkAfter = TEST_NOW - 1.seconds,
                    ),
                    source = TEST_SOURCE,
                ),
            )
            .bind()
        val revokedSnapshot = entitlementEntity(
            licenseKey = null,
            status = "revoked",
            checkAfter = null,
            reason = "REFUNDED",
        )
        fixture.repository.enqueueClaim(revokedSnapshot)

        val entitlement = fixture.manager
            .claim(TEST_CLAIM, force = true)
            .bind()

        val decoded = assertIs<LicenseEntitlement.Decoded>(entitlement)
        assertEquals(SUBSCRIPTION_LICENSE_KEY, decoded.licenseKey)
        assertEquals(LicenseStatus.REVOKED, decoded.status)
        assertEquals(TEST_SOURCE, decoded.source)
        val stored = assertNotNull(fixture.settings.getClaimedLicenseState().first())
        assertEquals(SUBSCRIPTION_LICENSE_KEY, stored.licenseKey)
        assertEquals(revokedSnapshot, stored.snapshot)
    }

    @Test
    fun `claim success without usable key clears claimed state when there is no same source fallback`() = runTest {
        val fixture = createFixture()
        fixture.settings
            .setClaimedLicenseState(
                LocalClaimedLicenseStateEntity(
                    licenseKey = SUBSCRIPTION_LICENSE_KEY,
                    snapshot = entitlementEntity(
                        licenseKey = SUBSCRIPTION_LICENSE_KEY,
                        status = "active",
                        checkAfter = TEST_NOW - 1.seconds,
                    ),
                    source = OTHER_SOURCE,
                ),
            )
            .bind()
        fixture.repository.enqueueClaim(
            entitlementEntity(
                licenseKey = "not-a-license",
                status = "active",
                checkAfter = TEST_NOW + 1.hours,
            ),
        )

        val entitlement = fixture.manager
            .claim(TEST_CLAIM, force = true)
            .bind()

        assertNull(entitlement)
        assertNull(fixture.settings.getClaimedLicenseState().first())
    }

    private fun createFixture(
        now: Instant = TEST_NOW,
    ): Fixture {
        val repository = FakeLicenseRepository()
        val settings = VaultSettingsRepositoryImpl(
            store = TestVaultSettingsKeyValueStore(),
            json = Json {
                ignoreUnknownKeys = true
            },
        )
        val manager = LicenseManagerImpl(
            licenseRepository = repository,
            decoder = Kg2LicenseKeyDecoder(
                signatureVerifier = LicenseSignatureVerifier { _, _, _ -> true },
                publicKeysById = KeyguardKg2LicensePublicKeys.values,
            ),
            vaultSettingsReadWriteRepository = settings,
            now = { now },
        )
        return Fixture(
            manager = manager,
            repository = repository,
            settings = settings,
        )
    }

    private data class Fixture(
        val manager: LicenseManagerImpl,
        val repository: FakeLicenseRepository,
        val settings: VaultSettingsRepositoryImpl,
    )

    private class FakeLicenseRepository : LicenseRepository {
        val claimCalls = mutableListOf<LicenseClaim>()
        val statusCalls = mutableListOf<String>()

        private val claimResponses = ArrayDeque<RepositoryResponse>()
        private val statusResponses = ArrayDeque<RepositoryResponse>()

        fun enqueueClaim(entity: LicenseEntitlementEntity) {
            claimResponses += RepositoryResponse.Success(entity)
        }

        fun enqueueClaim(exception: Throwable) {
            claimResponses += RepositoryResponse.Failure(exception)
        }

        fun enqueueStatus(entity: LicenseEntitlementEntity) {
            statusResponses += RepositoryResponse.Success(entity)
        }

        fun enqueueStatus(exception: Throwable) {
            statusResponses += RepositoryResponse.Failure(exception)
        }

        override fun claim(
            claim: LicenseClaim,
        ): IO<LicenseEntitlementEntity> = ioEffect {
            claimCalls += claim
            claimResponses.removeFirstOrNull()
                .toEntity()
        }

        override fun status(
            licenseKey: String,
        ): IO<LicenseEntitlementEntity> = ioEffect {
            statusCalls += licenseKey
            statusResponses.removeFirstOrNull()
                .toEntity()
        }

        private fun RepositoryResponse?.toEntity(): LicenseEntitlementEntity =
            when (this) {
                is RepositoryResponse.Success -> entity
                is RepositoryResponse.Failure -> throw exception
                null -> error("Unexpected license repository call.")
            }
    }

    private sealed interface RepositoryResponse {
        data class Success(
            val entity: LicenseEntitlementEntity,
        ) : RepositoryResponse

        data class Failure(
            val exception: Throwable,
        ) : RepositoryResponse
    }

    private class TestVaultSettingsKeyValueStore(
        private val delegate: JsonKeyValueStore = JsonKeyValueStore(),
    ) : VaultSettingsKeyValueStore, KeyValueStore by delegate
}

private fun entitlementEntity(
    licenseKey: String?,
    status: String,
    checkAfter: Instant?,
    reason: String? = null,
): LicenseEntitlementEntity = LicenseEntitlementEntity(
    licenseId = "ABCDEFGHJKLMNPQR",
    licenseKey = licenseKey,
    licensed = status == "active" || status == "grace",
    status = status,
    tier = "premium",
    productKind = "subscription",
    productId = "premium",
    expiresAt = "2026-07-01T00:00:00Z",
    checkAfter = checkAfter?.toString(),
    reason = reason,
)

private val TEST_NOW = Instant.parse("2026-01-01T00:00:00Z")

private val TEST_SOURCE = LicenseSource(
    provider = LicenseSource.PROVIDER_GOOGLE_PLAY,
    productId = "premium",
    productType = "subs",
    purchaseTokenHash = "purchase-token-hash",
)

private val OTHER_SOURCE = LicenseSource(
    provider = LicenseSource.PROVIDER_GOOGLE_PLAY,
    productId = "premium_lifetime",
    productType = "inapp",
    purchaseTokenHash = "other-purchase-token-hash",
)

private val TEST_CLAIM = LicenseClaimCandidate(
    claim = LicenseClaim.Google(
        purchaseToken = "purchase-token-1234567890",
        productId = TEST_SOURCE.productId,
        productType = TEST_SOURCE.productType,
    ),
    source = TEST_SOURCE,
)

private const val SUBSCRIPTION_LICENSE_KEY =
    "KG2A.AgBEMhTHQlS2Nc8RAT4" +
            ".YjxsLYGEaqm1sc6ygG-c4OQqM2tC50YzduTLsXd8_yfoFJGpr-lO_E43boe8RgOcd_5HG54UhQZCo2DXjiZ_Xw"

private const val LIFETIME_LICENSE_KEY =
    "KG2A.AgBEMhTHQlS2NdAS__8" +
            ".BeXEYQf63KWXTca_xvHTgQuR1BNqNKA6khJ8S_s8WKgU0ZfKGC8tibGRYGXHroK8RvkUH_A5HacAhTh8NvMSJw"
