package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepositoryEmpty
import com.artemchep.keyguard.common.service.gpgagent.isEligibleForGpgAgent
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentKeyInfoRows
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.gpgagent.toGpgPublicKeyEntry
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.vault.testDomainSessionAccess
import com.artemchep.keyguard.common.service.vault.testVaultSession
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.test.gpgMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class GpgAgentListKeysContractTest {
    @Test
    fun `archived and trashed keys remain mappable but ineligible for the agent`() {
        val cipher = createActiveCiphers().first()
        val secret = assertNotNull(cipher.toGpgAgentSecretOrNull())
        assertTrue(cipher.isEligibleForGpgAgent())

        val date = Instant.parse("2024-02-01T00:00:00Z")
        listOf(cipher.copy(archivedDate = date), cipher.copy(deletedDate = date)).forEach { inactive ->
            assertEquals(secret.copy(cipher = inactive), inactive.toGpgAgentSecretOrNull())
            assertFalse(inactive.isEligibleForGpgAgent())
        }
    }

    @Test
    fun `locked and unlocked KEYINFO listings agree and collapse shared keygrips`() = runTest {
        // The "backup" cipher holds the same component key as "primary",
        // which the per-cipher catalog intentionally keeps twice.
        val activeCiphers = createActiveCiphers()
        // An archived public-only copy must not hide the active private key
        // when it precedes that key in either listing's source order.
        val primary = activeCiphers.first()
        val archivedPublicCopy = primary.copy(
            id = "archived-public",
            archivedDate = Instant.parse("2024-02-01T00:00:00Z"),
            gpgKey = primary.gpgKey?.copy(privateKeyArmored = null),
        )
        val ciphers = listOf(archivedPublicCopy) + activeCiphers
        // The locked catalog is what the syncer would have persisted for the same secrets.
        val rows = activeCiphers
            .mapNotNull { it.toGpgAgentSecretOrNull() }
            .flatMap { it.toGpgPublicKeyEntry(name = it.cipher.name).toGpgAgentKeyInfoRows() }
        val expected = createProcessor(null, FakeGpgPublicKeyRepository { rows }).listKeysOrThrow()
        listOf(ciphers, ciphers.reversed()).forEach { ordered ->
            val unlockedKeys = createProcessor(
                session = createSession(ordered),
                repository = GpgPublicKeyRepositoryEmpty,
            ).listKeysOrThrow()
            assertEquals(expected, unlockedKeys)
        }
        assertEquals(expected, createProcessor(null, FakeGpgPublicKeyRepository { rows.reversed() }).listKeysOrThrow())
        assertEquals(listOf(SHARED_KEYGRIP, OTHER_KEYGRIP), expected.map { it.keygrip })
        val shared = expected.single { it.keygrip == SHARED_KEYGRIP }
        assertTrue(shared.canDecrypt)
        assertEquals("Backup", shared.name)
    }

    private fun createActiveCiphers(): List<DSecret> = listOf(
        createGpgCipher(
            id = "0-public",
            name = "Public only",
            fingerprint = FINGERPRINT_1,
            keygrip = SHARED_KEYGRIP,
        ).let { it.copy(gpgKey = it.gpgKey?.copy(privateKeyArmored = null)) },
        createGpgCipher(
            id = "primary",
            name = "Primary",
            fingerprint = FINGERPRINT_1,
            keygrip = SHARED_KEYGRIP,
        ),
        createGpgCipher(
            id = "backup",
            name = "Backup",
            fingerprint = FINGERPRINT_1,
            keygrip = SHARED_KEYGRIP,
        ),
        createGpgCipher(
            id = "other",
            name = "Other",
            fingerprint = FINGERPRINT_2,
            keygrip = OTHER_KEYGRIP,
        ),
    )

    private fun createSession(ciphers: List<DSecret>) = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.V1,
            byteArray = ByteArray(size = MASTER_KEY_BYTES),
        ),
        session = testVaultSession {
            scoped<GetCiphers> {
                object : GetCiphers {
                    override fun invoke(): Flow<List<DSecret>> = flowOf(ciphers)
                }
            }
        },
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private suspend fun GpgAgentRequestProcessorImpl.listKeysOrThrow() =
        (listKeys(caller = null) as GpgAgentRequestProcessor.ListKeysResult.Success)
            .response
            .keys

    private fun TestScope.createProcessor(
        session: MasterSession?,
        repository: GpgPublicKeyRepository,
    ) = GpgAgentRequestProcessorImpl(
        sessionAccess = testDomainSessionAccess(),
        logRepository = LogRepositoryBridge(emptyList()),
        crypto = NativeGpgAgentCrypto,
        getVaultSession = FakeGetVaultSession(session),
        getGpgAgentApprovalWindow = GetGpgAgentApprovalWindowNoOp,
        getGpgAgentFilter = object : GetGpgAgentFilter {
            override fun invoke(): Flow<GpgAgentFilter> = flowOf(GpgAgentFilter())
        },
        scope = backgroundScope,
        gpgPublicKeyRepository = repository,
    )

    private class FakeGetVaultSession(
        override val valueOrNull: MasterSession?,
    ) : GetVaultSession {
        override fun invoke(): Flow<MasterSession> =
            valueOrNull?.let(::flowOf) ?: emptyFlow()
    }

    private fun createGpgCipher(
        id: String,
        name: String,
        fingerprint: String,
        keygrip: String,
    ): DSecret = createSecret(
        id = id,
        name = name,
        accountId = "account",
        type = DSecret.Type.GpgKey,
        gpgKey = DSecret.GpgKey(
            privateKeyArmored = "private-key",
            publicKeyArmored = "public-key",
            fingerprint = fingerprint,
            metadata = gpgMetadata(
                GpgAgentKeyMetadataKey(
                        keygrip = keygrip,
                        fingerprint = fingerprint,
                        algorithm = "ED25519",
                        capabilities = setOf("sign", "decrypt"),
                ),
            ),
        ),
    )

    private companion object {
        const val MASTER_KEY_BYTES = 32
        const val SHARED_KEYGRIP = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val OTHER_KEYGRIP = "1123456789ABCDEF0123456789ABCDEF01234567"
        const val FINGERPRINT_1 = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        const val FINGERPRINT_2 = "ABCDEF0123456789ABCDEF0123456789ABCDEF02"
    }
}
