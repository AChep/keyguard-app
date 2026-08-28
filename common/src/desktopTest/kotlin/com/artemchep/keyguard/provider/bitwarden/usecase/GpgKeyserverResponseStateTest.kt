package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.model.VerifyGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.GpgKeyserverClientImpl
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.keyserverResponseConfigs
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.keyserverResponseHttpClient
import com.artemchep.keyguard.common.service.gpgkeyserver.impl.keyserverResponseParser
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.impl.VerifyGpgPublicKeyImpl
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestPasswordStrength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GpgKeyserverResponseStateTest {
    @Test
    fun `invalid responses cannot change stored state during refresh or verification`() = runTest {
        val key = assertIs<GpgPublicKeyParseResult.Success>(
            NativeGpgPublicKeyParser.parse(REFRESH_PUBLIC_KEY),
        ).keys.single()
        val responses = listOf(
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Empty),
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed),
            GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.UnsupportedKeyVersion),
            GpgPublicKeyParseResult.Success(emptyList()),
            GpgPublicKeyParseResult.Success(listOf(key.copy(fingerprint = "A".repeat(40)))),
        )
        for (config in keyserverResponseConfigs) {
            for (response in responses) {
                keyserverResponseHttpClient().use { http ->
                    val client = GpgKeyserverClientImpl(http, keyserverResponseParser(response))
                    assertFailureKeepsState(config, client)
                }
            }
        }
    }

    private suspend fun assertFailureKeepsState(
        config: GpgKeyserverConfig,
        client: GpgKeyserverClient,
    ) {
        GpgKeyserverRefreshTestFixture().use { fixture ->
            val previous = DGpgKeyserverState(
                fingerprint = REFRESH_FINGERPRINT,
                cipherId = REFRESH_CIPHER_ID,
                verificationStatus = GpgKeyserverVerificationStatus.REVOKED,
                publicationStatus = GpgKeyserverVerificationStatus.VERIFIED,
                hasUnbackedRevocation = true,
                revocationEvidenceArmored = REFRESH_PUBLIC_KEY,
                lastCheckedAt = REFRESH_CREATED_AT,
                lastRefreshedAt = REFRESH_CREATED_AT,
                sourceKeyserver = "https://previous.example.test",
            )
            fixture.stateRepository.put(previous).bind()
            val before = fixture.row()
            fixture.lookup = { fingerprint -> client.getByFingerprint(fingerprint, config).bind() }

            assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind())
            assertEquals(previous, fixture.stateRepository.getByFingerprint(REFRESH_FINGERPRINT).first())
            assertTrue(fixture.lastRefreshes.isEmpty())

            val verify = verifier(fixture, config, client)
            assertFailsWith<IllegalStateException> {
                verify(VerifyGpgPublicKeyRequest(REFRESH_CIPHER_ID, REFRESH_ACCOUNT_ID)).bind()
            }

            assertEquals(previous, fixture.stateRepository.getByFingerprint(REFRESH_FINGERPRINT).first())
            assertEquals(before, fixture.row())
            assertEquals(0, fixture.backupDirtyCount)
            assertTrue(fixture.lastRefreshes.isEmpty())
        }
    }

    private fun verifier(
        fixture: GpgKeyserverRefreshTestFixture,
        config: GpgKeyserverConfig,
        client: GpgKeyserverClient,
    ) = VerifyGpgPublicKeyImpl(
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = flow {
                emit(listOf(fixture.row().data_.toDomain(UploadTestPasswordStrength)))
            }
        },
        getGpgKeyserverConfig = object : GetGpgKeyserverConfig {
            override fun invoke() = flowOf(config)
        },
        keyserverClient = client,
        keyserverStateRepository = fixture.stateRepository,
        parser = NativeGpgPublicKeyParser,
        metadataResolver = NativeGpgKeyMetadataResolver,
        reconciler = NativeGpgCertificateMaterialReconciler,
    )
}
