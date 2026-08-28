package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolverUnsupported
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgKeyserverRefreshOutcomeTest {
    @Test
    fun `unchanged refresh succeeds without a cipher write or dirty backup`() = runTest {
        for (privateKey in listOf(null, "", " \n\t")) {
            val initial = refreshTestCipher().let { cipher ->
                cipher.copy(gpgKey = cipher.gpgKey?.copy(privateKeyArmored = privateKey))
            }
            GpgKeyserverRefreshTestFixture(initial = listOf(initial)).use { fixture ->
                val before = fixture.row()

                repeat(2) {
                    assertEquals(RefreshGpgPublicKeysResult(1, 0, 0), fixture.useCase(fixture.request).bind())
                }

                assertEquals(before, fixture.row())
                assertEquals(0, fixture.backupDirtyCount)
                assertEquals(2, fixture.lastRefreshes.size)
                val state = fixture.stateRepository.getByFingerprint(REFRESH_FINGERPRINT).first()
                assertEquals(
                    fixture.lastRefreshes.last()?.toEpochMilliseconds(),
                    state?.lastRefreshedAt?.toEpochMilliseconds(),
                )
            }
        }
    }

    @Test
    fun `invalid remote material does not write or report a refresh`() = runTest {
        val invalid = listOf(
            DGpgKeyserverResult(REFRESH_FINGERPRINT, publicKeyArmored = "malformed"),
            DGpgKeyserverResult(REFRESH_FINGERPRINT, publicKeyArmored = ""),
            DGpgKeyserverResult(REFRESH_FINGERPRINT, publicKeyArmored = null),
            DGpgKeyserverResult("A".repeat(40), publicKeyArmored = REFRESH_PUBLIC_KEY),
        )
        for (response in invalid) {
            GpgKeyserverRefreshTestFixture().use { fixture ->
                fixture.lookup = { response }
                val before = fixture.row()

                assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind())
                assertEquals(before, fixture.row())
                assertTrue(fixture.lastRefreshes.isEmpty())
                assertTrue(fixture.stateRepository.getAll().first().isEmpty())
            }
        }
    }

    @Test
    fun `invalid native material is not replaced by a legacy fallback`() = runTest {
        val legacyFields = listOf(
            BitwardenCipher.Field(
                name = GpgAgentFields.PUBLIC_KEY_ARMORED,
                value = REFRESH_PUBLIC_KEY,
                type = BitwardenCipher.Field.Type.Hidden,
            ),
            BitwardenCipher.Field(
                name = GpgAgentFields.FINGERPRINT,
                value = REFRESH_FINGERPRINT,
                type = BitwardenCipher.Field.Type.Text,
            ),
        )
        val malformed = refreshTestCipher(publicKey = "malformed local evidence")
        val invalid = listOf(
            malformed,
            malformed.copy(fields = legacyFields),
            refreshTestCipher().let { cipher ->
                cipher.copy(
                    gpgKey = cipher.gpgKey?.copy(fingerprint = "A".repeat(40)),
                    fields = legacyFields,
                )
            },
        )
        for (cipher in invalid) {
            GpgKeyserverRefreshTestFixture(initial = listOf(cipher)).use { fixture ->
                val before = fixture.row()

                assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind())
                assertEquals(before, fixture.row())
                assertTrue(fixture.lastRefreshes.isEmpty())
            }
        }
    }

    @Test
    fun `unavailable metadata does not fall back to stale metadata`() = runTest {
        GpgKeyserverRefreshTestFixture(resolver = GpgKeyMetadataResolverUnsupported).use { fixture ->
            val before = fixture.row()

            assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind())
            assertEquals(before, fixture.row())
            assertTrue(fixture.stateRepository.getAll().first().isEmpty())
        }
    }

    @Test
    fun `one lookup failure does not prevent another key from refreshing`() = runTest {
        val failed = refreshTestCipher().let { it.copy(gpgKey = it.gpgKey?.copy(fingerprint = "A".repeat(40))) }
        GpgKeyserverRefreshTestFixture(
            initial = listOf(failed, refreshTestCipher(id = "second")),
        ).use { fixture ->
            fixture.lookup = { fingerprint ->
                check(fingerprint == REFRESH_FINGERPRINT) { "Lookup failed" }
                DGpgKeyserverResult(fingerprint, publicKeyArmored = REFRESH_PUBLIC_KEY)
            }

            assertEquals(RefreshGpgPublicKeysResult(1, 0, 0, 1), fixture.useCase(fixture.request).bind())
            assertEquals(2, fixture.lookups.size)
            assertEquals(1, fixture.lastRefreshes.size)
            assertEquals("second", fixture.stateRepository.getAll().first().single().cipherId)
        }
    }

    @Test
    fun `not found records a check without changing the last successful refresh`() = runTest {
        GpgKeyserverRefreshTestFixture().use { fixture ->
            val old = DGpgKeyserverState(
                fingerprint = REFRESH_FINGERPRINT,
                cipherId = REFRESH_CIPHER_ID,
                verificationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                lastCheckedAt = REFRESH_CREATED_AT,
                lastRefreshedAt = REFRESH_CREATED_AT,
                sourceKeyserver = "https://keys.example.test",
            )
            fixture.stateRepository.put(old).bind()
            fixture.lookup = { null }

            assertEquals(RefreshGpgPublicKeysResult(0, 1, 0), fixture.useCase(fixture.request).bind())
            val state = fixture.stateRepository.getByFingerprint(REFRESH_FINGERPRINT).first()
            assertEquals(GpgKeyserverVerificationStatus.NOT_FOUND, state?.verificationStatus)
            assertEquals(old.lastRefreshedAt, state?.lastRefreshedAt)
            assertEquals(
                fixture.lastRefreshes.single()?.toEpochMilliseconds(),
                state?.lastCheckedAt?.toEpochMilliseconds(),
            )
        }
    }

    @Test
    fun `lookup cancellation and fatal errors propagate without recording success`() = runTest {
        GpgKeyserverRefreshTestFixture().use { fixture ->
            fixture.lookup = { throw CancellationException("cancelled") }
            assertFailsWith<CancellationException> { fixture.useCase(fixture.request).bind() }
            fixture.lookup = { throw AssertionError("fatal") }
            assertFailsWith<AssertionError> { fixture.useCase(fixture.request).bind() }

            assertTrue(fixture.lastRefreshes.isEmpty())
            assertTrue(fixture.stateRepository.getAll().first().isEmpty())
        }
    }

    @Test
    fun `write denial never records a successful refresh`() = runTest {
        GpgKeyserverRefreshTestFixture().use { fixture ->
            fixture.canWrite = false
            val before = fixture.row()

            assertFailsWith<Exception> { fixture.useCase(fixture.request).bind() }
            assertEquals(before, fixture.row())
            assertTrue(fixture.lastRefreshes.isEmpty())
            assertNull(fixture.stateRepository.getByFingerprint(REFRESH_FINGERPRINT).first())
        }
    }

    @Test
    fun `missing and partial responses cannot clear a known revocation status`() = runTest {
        GpgKeyserverRefreshTestFixture().use { fixture ->
            fixture.stateRepository.put(
                DGpgKeyserverState(
                    fingerprint = REFRESH_FINGERPRINT,
                    cipherId = REFRESH_CIPHER_ID,
                    verificationStatus = GpgKeyserverVerificationStatus.REVOKED,
                    lastCheckedAt = REFRESH_CREATED_AT,
                    lastRefreshedAt = REFRESH_CREATED_AT,
                    sourceKeyserver = null,
                ),
            ).bind()

            val responses = listOf(
                null,
                DGpgKeyserverResult(REFRESH_FINGERPRINT, publicKeyArmored = REFRESH_PUBLIC_KEY),
            )
            for (response in responses) {
                fixture.lookup = { response }

                val result = fixture.useCase(fixture.request).bind()

                assertEquals(if (response == null) 1 else 0, result.notFound)
                assertEquals(if (response == null) 0 else 1, result.refreshed)
                val state = fixture.stateRepository.getByFingerprint(REFRESH_FINGERPRINT).first()
                assertEquals(GpgKeyserverVerificationStatus.REVOKED, state?.verificationStatus)
                assertEquals(
                    fixture.lastRefreshes.last()?.toEpochMilliseconds(),
                    state?.lastCheckedAt?.toEpochMilliseconds(),
                )
                if (response == null) {
                    assertEquals(REFRESH_CREATED_AT, state?.lastRefreshedAt)
                }
            }
        }
    }
}
