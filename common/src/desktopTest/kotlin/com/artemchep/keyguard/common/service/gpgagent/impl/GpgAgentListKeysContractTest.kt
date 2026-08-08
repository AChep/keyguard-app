package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyInfoRow
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepositoryEmpty
import com.artemchep.keyguard.common.service.gpgagent.toGpgAgentSecretOrNull
import com.artemchep.keyguard.common.service.gpgagent.toGpgPublicKeyEntry
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindowNoOp
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class GpgAgentListKeysContractTest {
    @Test
    fun `locked and unlocked KEYINFO listings agree and collapse shared keygrips`() = runTest {
        // The "backup" cipher holds the same component key as "primary",
        // which the per-cipher catalog intentionally keeps twice.
        val ciphers = listOf(
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
        val secrets = ciphers.mapNotNull { it.toGpgAgentSecretOrNull() }
        assertEquals(ciphers.size, secrets.size)

        val unlockedProcessor = createProcessor(
            session = MasterSession.Key(
                masterKey = MasterKey(
                    version = MasterKdfVersion.V1,
                    byteArray = ByteArray(size = MASTER_KEY_BYTES),
                ),
                di = DI {
                    bindSingleton<GetCiphers> {
                        object : GetCiphers {
                            override fun invoke(): Flow<List<DSecret>> = flowOf(ciphers)
                        }
                    }
                },
                origin = MasterSession.Key.Authenticated,
                createdAt = Instant.parse("2024-01-01T00:00:00Z"),
            ),
            repository = GpgPublicKeyRepositoryEmpty,
        )
        // The locked catalog is what the syncer would have persisted for
        // the very same secrets, flattened the way the repository joins it.
        val lockedProcessor = createProcessor(
            session = null,
            repository = FakeGpgPublicKeyRepository(
                keyInfo = buildCatalogRows(secrets),
            ),
        )

        val unlockedKeys = unlockedProcessor.listKeysOrThrow()
        val lockedKeys = lockedProcessor.listKeysOrThrow()

        // A shared keygrip may keep a row of either cipher, so the
        // display name is allowed to differ between the two paths;
        // the key identity is not.
        assertEquals(
            unlockedKeys.map { it.copy(name = "") }.toSet(),
            lockedKeys.map { it.copy(name = "") }.toSet(),
        )
        assertEquals(
            listOf(SHARED_KEYGRIP, OTHER_KEYGRIP).sorted(),
            unlockedKeys.map { it.keygrip }.sorted(),
        )
    }

    private fun buildCatalogRows(
        secrets: List<GpgAgentSecret>,
    ): List<GpgAgentKeyInfoRow> = secrets
        .map { it.toGpgPublicKeyEntry(name = it.cipher.name) }
        .flatMap { entry ->
            entry.keyInfo.map { key ->
                GpgAgentKeyInfoRow(
                    accountId = entry.accountId,
                    cipherId = entry.cipherId,
                    keygrip = key.keygrip,
                    fingerprint = key.fingerprint,
                    algorithm = key.algorithm,
                    canSign = key.canSign,
                    canDecrypt = key.canDecrypt,
                    name = entry.name,
                )
            }
        }
        .sortedWith(
            compareBy(
                { it.fingerprint },
                { it.keygrip },
                { it.accountId },
                { it.cipherId },
            ),
        )

    private suspend fun GpgAgentRequestProcessorImpl.listKeysOrThrow() =
        (listKeys(caller = null) as GpgAgentRequestProcessor.ListKeysResult.Success)
            .response
            .keys

    private fun TestScope.createProcessor(
        session: MasterSession?,
        repository: GpgPublicKeyRepository,
    ) = GpgAgentRequestProcessorImpl(
        logRepository = NoOpLogRepository,
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

    private class FakeGpgPublicKeyRepository(
        private val keyInfo: List<GpgAgentKeyInfoRow>,
    ) : GpgPublicKeyRepository by GpgPublicKeyRepositoryEmpty {
        override fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>> = {
            keyInfo
        }

        override fun getKeyInfoByKeygrip(
            keygrip: String,
        ): IO<List<GpgAgentKeyInfoRow>> = {
            keyInfo.filter { it.keygrip == keygrip }
        }
    }

    private object NoOpLogRepository : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit
    }

    private fun createGpgCipher(
        id: String,
        name: String,
        fingerprint: String,
        keygrip: String,
    ): DSecret = DSecret(
        id = id,
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
        createdDate = Instant.parse("2024-01-01T00:00:00Z"),
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = name,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.GpgKey,
        gpgKey = DSecret.GpgKey(
            privateKeyArmored = "private-key",
            publicKeyArmored = "public-key",
            fingerprint = fingerprint,
            metadata = GpgAgentKeyMetadata(
                keys = listOf(
                    GpgAgentKeyMetadataKey(
                        keygrip = keygrip,
                        fingerprint = fingerprint,
                        algorithm = "ED25519",
                        capabilities = setOf("sign", "decrypt"),
                    ),
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
