package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.DGpgKeyserverUploadResult
import com.artemchep.keyguard.common.model.DGpgKeyserverUploadResult.EmailStatus
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.model.UploadGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverLocalKey
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PRIMARY_FINGERPRINT
import com.artemchep.keyguard.crypto.GPG_TEST_CV25519_PUBLIC_KEY
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CIPHER_ID = "cipher-id"
private const val ACCOUNT_ID = "account-id"
private const val OTHER_FINGERPRINT = "0123456789ABCDEF0123456789ABCDEF01234567"

class UploadGpgPublicKeyImplTest {
    @Test
    fun `upload without addresses does not request verification`() = runTest {
        val client = UploadFakeKeyserverClient(
            uploadResult = vksResult("alice@example.com" to EmailStatus.UNPUBLISHED),
        )
        val repository = FakeGpgKeyserverStateRepository()

        val result = createUseCase(client, repository)
            .invoke(UploadGpgPublicKeyRequest(CIPHER_ID, ACCOUNT_ID))
            .bind()

        assertEquals(listOf(GPG_TEST_CV25519_PUBLIC_KEY), client.uploads)
        assertTrue(client.verifyRequests.isEmpty())
        assertTrue(result.verificationRequestedEmails.isEmpty())
        assertTrue(result.alreadyPublishedEmails.isEmpty())
    }

    @Test
    fun `upload requests verification only for unpublished or pending addresses`() = runTest {
        val client = UploadFakeKeyserverClient(
            uploadResult = vksResult(
                "alice@example.com" to EmailStatus.UNPUBLISHED,
                "Bob@example.com" to EmailStatus.PENDING,
                "carol@example.com" to EmailStatus.PUBLISHED,
                "dave@example.com" to EmailStatus.REVOKED,
            ),
            verifyResult = vksResult(
                "alice@example.com" to EmailStatus.PENDING,
                "Bob@example.com" to EmailStatus.PENDING,
                "carol@example.com" to EmailStatus.PUBLISHED,
            ),
        )
        val repository = FakeGpgKeyserverStateRepository()

        val result = createUseCase(client, repository)
            .invoke(
                UploadGpgPublicKeyRequest(
                    cipherId = CIPHER_ID,
                    accountId = ACCOUNT_ID,
                    verifyEmails = setOf(
                        "alice@example.com",
                        "bob@example.com",
                        "carol@example.com",
                        "dave@example.com",
                        "unknown@example.com",
                    ),
                ),
            )
            .bind()

        val verifyRequest = client.verifyRequests.single()
        assertEquals("token", verifyRequest.first)
        assertEquals(setOf("alice@example.com", "Bob@example.com"), verifyRequest.second.toSet())
        assertEquals(setOf("alice@example.com", "Bob@example.com"), result.verificationRequestedEmails)
        assertEquals(setOf("carol@example.com"), result.alreadyPublishedEmails)
    }

    @Test
    fun `upload fails when verification is requested but no token was returned`() = runTest {
        val client = UploadFakeKeyserverClient(
            uploadResult = vksResult("alice@example.com" to EmailStatus.UNPUBLISHED)
                .copy(token = null),
        )

        val result = createUseCase(client, FakeGpgKeyserverStateRepository())
            .invoke(
                UploadGpgPublicKeyRequest(
                    cipherId = CIPHER_ID,
                    accountId = ACCOUNT_ID,
                    verifyEmails = setOf("alice@example.com"),
                ),
            )
            .attempt()
            .bind()

        assertIs<IllegalStateException>(result.leftOrNull())
        assertTrue(client.verifyRequests.isEmpty())
    }

    @Test
    fun `VKS upload records the publication status`() = runTest {
        val repository = FakeGpgKeyserverStateRepository()
        val unverified = createUseCase(
            client = UploadFakeKeyserverClient(
                uploadResult = vksResult("alice@example.com" to EmailStatus.UNPUBLISHED),
            ),
            repository = repository,
        )
        unverified(UploadGpgPublicKeyRequest(CIPHER_ID, ACCOUNT_ID)).bind()
        assertEquals(
            GpgKeyserverVerificationStatus.FOUND_UNVERIFIED,
            repository.saved.values.single().publicationStatus,
        )

        val verified = createUseCase(
            client = UploadFakeKeyserverClient(
                uploadResult = vksResult("alice@example.com" to EmailStatus.PUBLISHED),
            ),
            repository = repository,
        )
        verified(UploadGpgPublicKeyRequest(CIPHER_ID, ACCOUNT_ID)).bind()
        val state = repository.saved.values.single()
        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, state.publicationStatus)
        assertEquals(GpgKeyserverConfig.DEFAULT_URL, state.sourceKeyserver)
        assertEquals(CIPHER_ID, state.cipherId)
    }

    @Test
    fun `HKP upload records nothing`() = runTest {
        val repository = FakeGpgKeyserverStateRepository()
        val useCase = createUseCase(
            client = UploadFakeKeyserverClient(uploadResult = DGpgKeyserverUploadResult()),
            repository = repository,
            config = GpgKeyserverConfig(
                url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                protocol = GpgKeyserverConfig.Protocol.HKP,
            ),
        )

        useCase(
            UploadGpgPublicKeyRequest(
                cipherId = CIPHER_ID,
                accountId = ACCOUNT_ID,
                verifyEmails = setOf("alice@example.com"),
            ),
        ).bind()

        assertTrue(repository.saved.isEmpty())
    }

    @Test
    fun `upload fails before requesting verification when the keyserver confirms a different fingerprint`() = runTest {
        val repository = FakeGpgKeyserverStateRepository()
        val client = UploadFakeKeyserverClient(
            uploadResult = vksResult("alice@example.com" to EmailStatus.UNPUBLISHED)
                .copy(fingerprint = OTHER_FINGERPRINT),
        )
        val result = createUseCase(client, repository)
            .invoke(
                UploadGpgPublicKeyRequest(
                    cipherId = CIPHER_ID,
                    accountId = ACCOUNT_ID,
                    verifyEmails = setOf("alice@example.com"),
                ),
            )
            .attempt()
            .bind()

        assertIs<IllegalStateException>(result.leftOrNull())
        assertTrue(client.verifyRequests.isEmpty())
        assertNull(repository.saved.values.firstOrNull())
    }
}

private fun vksResult(
    vararg status: Pair<String, EmailStatus>,
) = DGpgKeyserverUploadResult(
    fingerprint = GPG_TEST_CV25519_PRIMARY_FINGERPRINT,
    emailStatus = status.toMap(),
    token = "token",
)

private fun createUseCase(
    client: GpgKeyserverClient,
    repository: FakeGpgKeyserverStateRepository,
    config: GpgKeyserverConfig = GpgKeyserverConfig(),
): UploadGpgPublicKeyImpl {
    val cipher = createGpgSecret(cipherId = CIPHER_ID, accountId = ACCOUNT_ID)
    return UploadGpgPublicKeyImpl(
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = flowOf(listOf(cipher))
        },
        getGpgKeyserverConfig = object : GetGpgKeyserverConfig {
            override fun invoke(): Flow<GpgKeyserverConfig> = flowOf(config)
        },
        keyserverClient = client,
        keyserverStateRepository = repository.apply {
            localKeys = listOf(
                GpgKeyserverLocalKey(
                    cipherId = cipher.id,
                    fingerprint = GPG_TEST_CV25519_PRIMARY_FINGERPRINT,
                    publicKeyArmored = GPG_TEST_CV25519_PUBLIC_KEY,
                ),
            )
        },
        metadataResolver = NativeGpgKeyMetadataResolver,
        reconciler = NativeGpgCertificateMaterialReconciler,
    )
}

private class UploadFakeKeyserverClient(
    private val uploadResult: DGpgKeyserverUploadResult,
    private val verifyResult: DGpgKeyserverUploadResult = uploadResult,
) : GpgKeyserverClient {
    val uploads = mutableListOf<String>()
    val verifyRequests = mutableListOf<Pair<String, List<String>>>()

    override fun search(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>> = ioEffect {
        error("Search is not used by upload.")
    }

    override fun canServeSearch(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): Boolean = error("Search is not used by upload.")

    override fun getByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverResult?> = ioEffect {
        error("Lookups are not used by upload.")
    }

    override fun getByEmail(
        email: String,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>> = ioEffect {
        error("Lookups are not used by upload.")
    }

    override fun upload(
        publicKeyArmored: String,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverUploadResult> = ioEffect {
        uploads += publicKeyArmored
        uploadResult
    }

    override fun requestVerify(
        token: String,
        addresses: Collection<String>,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverUploadResult> = ioEffect {
        verifyRequests += token to addresses.toList()
        verifyResult
    }
}
