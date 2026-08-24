package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyInfoRow
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.test.gpgMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyEntry
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRow
import com.artemchep.keyguard.common.service.gpgagent.isEligibleForGpgAgent
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetGpgAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.direct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GpgPublicKeySyncerImplTest {
    @Test
    fun `GPG toggle controls the catalog and preserves key capabilities`() = runTest {
        val repository = RecordingGpgPublicKeyRepository()
        val enabled = MutableStateFlow(true)
        val publicOnly = createGpgSecret(
            id = "public",
            name = "Public only",
            privateKeyArmored = null,
            publicKeyArmored = "public-key",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            keygrip = "0123456789ABCDEF0123456789ABCDEF01234567",
        )
        val privateBacked = createGpgSecret(
            id = "private",
            name = "Private backed",
            privateKeyArmored = "private-key",
            publicKeyArmored = "public-private-key",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
            keygrip = "1123456789ABCDEF0123456789ABCDEF01234567",
        )
        val decryptOnly = createGpgSecret(
            id = "decrypt-only",
            name = "Decrypt only",
            privateKeyArmored = "decrypt-only-private-key",
            publicKeyArmored = "decrypt-only-public-key",
            fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF03",
            keygrip = "2123456789ABCDEF0123456789ABCDEF01234567",
            capabilities = setOf("decrypt"),
        )
        val syncer = createSyncer(
            repository = repository,
            ciphers = MutableStateFlow(listOf(publicOnly, privateBacked, decryptOnly)),
            gpgAgentEnabled = enabled,
            displayKeyNames = MutableStateFlow(true),
            filter = MutableStateFlow(GpgAgentFilter()),
            gpgKeyMetadataResolver = metadataResolver(
                publicOnly,
                privateBacked,
                decryptOnly,
            ),
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )

        val job = syncer.launch(this)
        try {
            advanceUntilIdle()

            assertTrue(publicOnly.isEligibleForGpgAgent())
            assertEquals(
                listOf(
                    GpgPublicKeyEntry(
                        accountId = "account",
                        cipherId = "public",
                        publicKeyArmored = "public-key",
                        primaryFingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                        canSign = false,
                        canDecrypt = false,
                        name = "Public only",
                        keyInfo = listOf(
                            GpgPublicKeyEntry.KeyInfo(
                                keygrip = "0123456789ABCDEF0123456789ABCDEF01234567",
                                fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                                algorithm = "ED25519",
                                canSign = false,
                                canDecrypt = false,
                            ),
                        ),
                    ),
                    GpgPublicKeyEntry(
                        accountId = "account",
                        cipherId = "private",
                        publicKeyArmored = "public-private-key",
                        primaryFingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
                        canSign = true,
                        canDecrypt = true,
                        name = "Private backed",
                        keyInfo = listOf(
                            GpgPublicKeyEntry.KeyInfo(
                                keygrip = "1123456789ABCDEF0123456789ABCDEF01234567",
                                fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
                                algorithm = "ED25519",
                                canSign = true,
                                canDecrypt = true,
                            ),
                        ),
                    ),
                    GpgPublicKeyEntry(
                        accountId = "account",
                        cipherId = "decrypt-only",
                        publicKeyArmored = "decrypt-only-public-key",
                        primaryFingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF03",
                        canSign = false,
                        canDecrypt = true,
                        name = "Decrypt only",
                        keyInfo = listOf(
                            GpgPublicKeyEntry.KeyInfo(
                                keygrip = "2123456789ABCDEF0123456789ABCDEF01234567",
                                fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF03",
                                algorithm = "ED25519",
                                canSign = false,
                                canDecrypt = true,
                            ),
                        ),
                    ),
                ),
                repository.entries,
            )

            enabled.value = false
            advanceUntilIdle()

            assertTrue(repository.entries.isEmpty())
        } finally {
            job.cancel()
        }
    }

    private fun createSyncer(
        repository: GpgPublicKeyRepository,
        ciphers: Flow<List<DSecret>>,
        gpgAgentEnabled: Flow<Boolean>,
        displayKeyNames: Flow<Boolean>,
        filter: Flow<GpgAgentFilter>,
        gpgKeyMetadataResolver: GpgKeyMetadataResolver,
        defaultDispatcher: CoroutineDispatcher,
    ) = GpgPublicKeySyncerImpl(
        directDI = DI {}.direct,
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = ciphers
        },
        getGpgAgent = object : GetGpgAgent {
            override fun invoke(): Flow<Boolean> = gpgAgentEnabled
        },
        getGpgAgentFilter = object : GetGpgAgentFilter {
            override fun invoke(): Flow<GpgAgentFilter> = filter
        },
        getGpgAgentDisplayKeyNames = object : GetGpgAgentDisplayKeyNames {
            override fun invoke(): Flow<Boolean> = displayKeyNames
        },
        gpgPublicKeyRepository = repository,
        logRepository = NoOpLogRepository,
        gpgKeyMetadataResolver = gpgKeyMetadataResolver,
        defaultDispatcher = defaultDispatcher,
    )

    private fun metadataResolver(
        vararg ciphers: DSecret,
    ): GpgKeyMetadataResolver {
        val metadataByFingerprint = ciphers.associate { cipher ->
            val gpgKey = requireNotNull(cipher.gpgKey)
            requireNotNull(gpgKey.fingerprint) to requireNotNull(gpgKey.metadata)
        }
        return object : GpgKeyMetadataResolver {
            override fun resolve(
                privateKeyArmored: String?,
                publicKeyArmored: String?,
                fingerprint: String?,
                candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
            ): GpgAgentMetadataResolution? {
                val metadata = metadataByFingerprint[fingerprint] ?: return null
                return GpgAgentMetadataResolution(
                    metadata = metadata,
                    authorization = GpgAgentAuthorizationSnapshot(
                        evaluatedAtEpochSeconds = 1,
                        policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
                        keys = metadata.routableAgentKeys,
                    ),
                )
            }
        }
    }

    private class RecordingGpgPublicKeyRepository : GpgPublicKeyRepository {
        var entries: List<GpgPublicKeyEntry> = emptyList()

        override fun getPublicKeys(): IO<List<GpgPublicKeyRow>> = {
            entries.mapNotNull { entry ->
                GpgPublicKeyRow(
                    accountId = entry.accountId,
                    cipherId = entry.cipherId,
                    publicKeyArmored = entry.publicKeyArmored
                        ?: return@mapNotNull null,
                    primaryFingerprint = entry.primaryFingerprint
                        ?: return@mapNotNull null,
                    canSign = entry.canSign,
                    canDecrypt = entry.canDecrypt,
                    name = entry.name,
                )
            }
        }

        override fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>> = {
            entries.flatMap { entry ->
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
        }

        override fun getKeyInfoByKeygrip(
            keygrip: String,
        ): IO<List<GpgAgentKeyInfoRow>> = {
            getKeyInfo().invoke()
                .filter { it.keygrip == keygrip }
        }

        override fun replaceAll(
            entries: List<GpgPublicKeyEntry>,
        ): IO<Unit> = {
            this.entries = entries
        }

        override fun clear(): IO<Unit> = {
            entries = emptyList()
        }

        override fun clearNames(): IO<Unit> = {
            entries = entries.map { it.copy(name = null) }
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

    private fun createGpgSecret(
        id: String,
        name: String,
        privateKeyArmored: String?,
        publicKeyArmored: String,
        fingerprint: String,
        keygrip: String,
        capabilities: Set<String> = setOf("sign", "decrypt"),
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
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = fingerprint,
            metadata = gpgMetadata(
                GpgAgentKeyMetadataKey(
                        keygrip = keygrip,
                        fingerprint = fingerprint,
                        algorithm = "ED25519",
                        capabilities = capabilities,
                ),
            ),
        ),
    )
}
