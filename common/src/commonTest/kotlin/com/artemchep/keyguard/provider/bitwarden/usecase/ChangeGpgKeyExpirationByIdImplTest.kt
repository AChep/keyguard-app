package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationChange
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationServiceUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationByIdRequest
import com.artemchep.keyguard.common.usecase.ChangeGpgKeyExpirationByIdResult
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ChangeGpgKeyExpirationByIdImplTest {
    @Test
    fun `successful renewal commits the exact source snapshot and updated material`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(gpgSecret()),
            service = service,
            commit = commit,
        )

        val result = useCase(request).bindBlocking()

        assertEquals(ChangeGpgKeyExpirationByIdResult.Success, result)
        assertEquals(
            listOf(
                GpgKeyExpirationRequest(
                    key = sourceMaterial,
                    change = request.change,
                    candidateRevocationKeys = listOf(
                        GpgOpenPgpPublicKey(sourceMaterial.publicKeyArmored),
                    ),
                ),
            ),
            service.requests,
        )
        assertEquals(
            listOf(
                CommitCall(
                    cipherId = CIPHER_ID,
                    sourceKey = sourceMaterial,
                    updatedKey = updatedMaterial,
                ),
            ),
            commit.calls,
        )
    }

    @Test
    fun `renewal supplies unique non-deleted native and legacy public keys`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val useCase = useCase(
            ciphers = listOf(
                gpgSecret(),
                gpgSecret(
                    id = "native-revoker",
                    gpgKey = gpgKey(publicKeyArmored = "public-native-revoker"),
                ),
                gpgSecret(
                    id = "legacy-revoker",
                    type = DSecret.Type.SecureNote,
                    gpgKey = null,
                    fields = listOf(
                        DSecret.Field(
                            name = GpgAgentFields.PUBLIC_KEY_ARMORED,
                            value = "public-legacy-revoker",
                            type = DSecret.Field.Type.Text,
                        ),
                    ),
                ),
                gpgSecret(
                    id = "duplicate-revoker",
                    gpgKey = gpgKey(publicKeyArmored = "public-native-revoker"),
                ),
                gpgSecret(
                    id = "blank-key",
                    gpgKey = gpgKey(publicKeyArmored = "  "),
                ),
                gpgSecret(
                    id = "deleted-revoker",
                    deletedDate = Instant.fromEpochSeconds(1),
                    gpgKey = gpgKey(publicKeyArmored = "public-deleted-revoker"),
                ),
            ),
            service = service,
            commit = RecordingCommit(result = true),
        )

        assertEquals(ChangeGpgKeyExpirationByIdResult.Success, useCase(request).bindBlocking())
        assertEquals(
            listOf(
                GpgOpenPgpPublicKey(sourceMaterial.publicKeyArmored),
                GpgOpenPgpPublicKey("public-native-revoker"),
                GpgOpenPgpPublicKey("public-legacy-revoker"),
            ),
            service.requests.single().candidateRevocationKeys,
        )
    }

    @Test
    fun `same-fingerprint certificate refresh after the request is rejected`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(
                gpgSecret(
                    gpgKey = gpgKey(publicKeyArmored = "public-refreshed"),
                ),
            ),
            service = service,
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.NotChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.Conflict,
            ),
            useCase(request).bindBlocking(),
        )
        assertTrue(service.requests.isEmpty())
        assertTrue(commit.calls.isEmpty())
    }

    @Test
    fun `stored key identity change after the request is rejected`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(
                gpgSecret(
                    gpgKey = gpgKey(
                        fingerprint = "REPLACEMENT",
                    ),
                ),
            ),
            service = service,
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.NotChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.Conflict,
            ),
            useCase(request).bindBlocking(),
        )
        assertTrue(service.requests.isEmpty())
        assertTrue(commit.calls.isEmpty())
    }

    @Test
    fun `missing stored fingerprint remains supported`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(
                gpgSecret(
                    gpgKey = gpgKey(fingerprint = null),
                ),
            ),
            service = service,
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.Success,
            useCase(
                request.copy(expectedKeyFingerprint = null),
            ).bindBlocking(),
        )
        assertEquals(1, service.requests.size)
        assertEquals(1, commit.calls.size)
    }

    @Test
    fun `a rejected optimistic commit is reported as not changed`() {
        val commit = RecordingCommit(result = false)
        val useCase = useCase(
            ciphers = listOf(gpgSecret()),
            service = RecordingExpirationService(
                result = GpgKeyExpirationResult.Success(updatedMaterial),
            ),
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.NotChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.Conflict,
            ),
            useCase(request).bindBlocking(),
        )
        assertEquals(1, commit.calls.size)
        assertEquals(sourceMaterial, commit.calls.single().sourceKey)
    }

    @Test
    fun `crypto failure leaves persistence untouched`() {
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(gpgSecret()),
            service = RecordingExpirationService(
                result = GpgKeyExpirationResult.Error(GpgKeyExpirationError.ProtectedSecretKey),
            ),
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.CryptoFailure(
                GpgKeyExpirationError.ProtectedSecretKey,
            ),
            useCase(request).bindBlocking(),
        )
        assertTrue(commit.calls.isEmpty())
    }

    @Test
    fun `missing cipher is not changed and skips crypto and persistence`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = emptyList(),
            service = service,
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.NotChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.NotFound,
            ),
            useCase(request).bindBlocking(),
        )
        assertTrue(service.requests.isEmpty())
        assertTrue(commit.calls.isEmpty())
    }

    @Test
    fun `missing gpg key is not changed and skips crypto and persistence`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(gpgSecret(gpgKey = null)),
            service = service,
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.NotChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.MissingGpgKey,
            ),
            useCase(request).bindBlocking(),
        )
        assertTrue(service.requests.isEmpty())
        assertTrue(commit.calls.isEmpty())
    }

    @Test
    fun `non-editable cipher is not changed and skips crypto and persistence`() {
        val service = RecordingExpirationService(
            result = GpgKeyExpirationResult.Success(updatedMaterial),
        )
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(gpgSecret(editable = false)),
            service = service,
            commit = commit,
        )

        assertEquals(
            ChangeGpgKeyExpirationByIdResult.NotChanged(
                ChangeGpgKeyExpirationByIdResult.NotChanged.Reason.NotEditable,
            ),
            useCase(request).bindBlocking(),
        )
        assertTrue(service.requests.isEmpty())
        assertTrue(commit.calls.isEmpty())
    }

    @Test
    fun `unsupported service is reported without invoking crypto or persistence`() {
        val commit = RecordingCommit(result = true)
        val useCase = useCase(
            ciphers = listOf(gpgSecret()),
            service = GpgKeyExpirationServiceUnsupported,
            commit = commit,
        )

        assertFalse(useCase.isSupported)
        assertEquals(
            ChangeGpgKeyExpirationByIdResult.CryptoFailure(
                GpgKeyExpirationError.UnsupportedPlatform,
            ),
            useCase(request).bindBlocking(),
        )
        assertTrue(commit.calls.isEmpty())
    }

    private fun useCase(
        ciphers: List<DSecret>,
        service: GpgKeyExpirationService,
        commit: GpgKeyExpirationCommitter,
    ) = ChangeGpgKeyExpirationByIdImpl(
        getCiphers = object : GetCiphers {
            override fun invoke() = flowOf(ciphers)
        },
        gpgKeyExpirationService = service,
        commitUpdatedKey = commit,
    )

    private fun gpgSecret(
        id: String = CIPHER_ID,
        gpgKey: DSecret.GpgKey? = gpgKey(),
        editable: Boolean = true,
        deletedDate: Instant? = null,
        type: DSecret.Type = DSecret.Type.GpgKey,
        fields: List<DSecret.Field> = emptyList(),
    ) = DSecret(
        id = id,
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.fromEpochSeconds(0),
        createdDate = null,
        archivedDate = null,
        deletedDate = deletedDate,
        service = if (editable) {
            BitwardenService()
        } else {
            BitwardenService(
                error = BitwardenService.Error(
                    code = BitwardenService.Error.CODE_DECODING_FAILED,
                    revisionDate = Instant.fromEpochSeconds(0),
                ),
            )
        },
        name = "GPG key",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        fields = fields,
        type = type,
        gpgKey = gpgKey,
    )

    private fun gpgKey(
        publicKeyArmored: String? = sourceMaterial.publicKeyArmored,
        fingerprint: String? = sourceMaterial.fingerprint,
    ) = DSecret.GpgKey(
        privateKeyArmored = sourceMaterial.privateKeyArmored,
        publicKeyArmored = publicKeyArmored,
        fingerprint = fingerprint,
        metadata = sourceMaterial.metadata,
    )

    private class RecordingExpirationService(
        private val result: GpgKeyExpirationResult,
    ) : GpgKeyExpirationService {
        val requests = mutableListOf<GpgKeyExpirationRequest>()

        override fun update(
            request: GpgKeyExpirationRequest,
        ): GpgKeyExpirationResult {
            requests += request
            return result
        }
    }

    private class RecordingCommit(
        private val result: Boolean,
    ) : GpgKeyExpirationCommitter {
        val calls = mutableListOf<CommitCall>()

        override fun invoke(
            cipherId: String,
            sourceKey: GpgKeyMaterial,
            updatedKey: GpgKeyMaterial,
        ): IO<Boolean> = ioEffect {
            calls += CommitCall(cipherId, sourceKey, updatedKey)
            result
        }
    }

    private data class CommitCall(
        val cipherId: String,
        val sourceKey: GpgKeyMaterial,
        val updatedKey: GpgKeyMaterial,
    )

    private companion object {
        const val CIPHER_ID = "cipher"

        val sourceMetadata = GpgAgentKeyMetadata(
            keys = listOf(
                GpgAgentKeyMetadataKey(
                    keygrip = "SOURCE-GRIP",
                    fingerprint = "PRIMARY",
                    capabilities = setOf("sign"),
                ),
            ),
        )
        val updatedMetadata = GpgAgentKeyMetadata(
            keys = listOf(
                GpgAgentKeyMetadataKey(
                    keygrip = "SOURCE-GRIP",
                    fingerprint = "PRIMARY",
                    capabilities = setOf("sign"),
                ),
            ),
        )
        val sourceMaterial = GpgKeyMaterial(
            privateKeyArmored = "private-source",
            publicKeyArmored = "public-source",
            fingerprint = "PRIMARY",
            metadata = sourceMetadata,
        )
        val updatedMaterial = GpgKeyMaterial(
            privateKeyArmored = "private-updated",
            publicKeyArmored = "public-updated",
            fingerprint = "PRIMARY",
            metadata = updatedMetadata,
        )
        val request = ChangeGpgKeyExpirationByIdRequest(
            cipherId = CIPHER_ID,
            expectedPublicKeyArmored = sourceMaterial.publicKeyArmored,
            expectedKeyFingerprint = sourceMaterial.fingerprint,
            change = GpgKeyExpirationChange(
                expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                componentFingerprints = setOf("PRIMARY"),
            ),
        )
    }
}
