package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentPublicKeyRow
import com.artemchep.keyguard.common.service.gpgagent.isEligibleForGpgAgent
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
class GpgAgentPublicKeySyncerImplTest {
    @Test
    fun `syncer stores public-only keys without private operation capabilities`() = runTest {
        val repository = RecordingGpgAgentPublicKeyRepository()
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
        val syncer = createSyncer(
            repository = repository,
            ciphers = MutableStateFlow(listOf(publicOnly, privateBacked)),
            gpgAgentEnabled = MutableStateFlow(true),
            displayKeyNames = MutableStateFlow(true),
            filter = MutableStateFlow(GpgAgentFilter()),
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )

        val job = syncer.launch(this)
        try {
            advanceUntilIdle()

            assertTrue(publicOnly.isEligibleForGpgAgent())
            assertEquals(
                listOf(
                    GpgAgentPublicKeyRow(
                        keygrip = "0123456789ABCDEF0123456789ABCDEF01234567",
                        fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
                        algorithm = "ED25519",
                        canSign = false,
                        canDecrypt = false,
                        publicKeyArmored = "public-key",
                        name = "Public only",
                    ),
                    GpgAgentPublicKeyRow(
                        keygrip = "1123456789ABCDEF0123456789ABCDEF01234567",
                        fingerprint = "ABCDEF0123456789ABCDEF0123456789ABCDEF02",
                        algorithm = "ED25519",
                        canSign = true,
                        canDecrypt = true,
                        publicKeyArmored = "public-private-key",
                        name = "Private backed",
                    ),
                ),
                repository.keys,
            )
        } finally {
            job.cancel()
        }
    }

    private fun createSyncer(
        repository: GpgAgentPublicKeyRepository,
        ciphers: Flow<List<DSecret>>,
        gpgAgentEnabled: Flow<Boolean>,
        displayKeyNames: Flow<Boolean>,
        filter: Flow<GpgAgentFilter>,
        defaultDispatcher: CoroutineDispatcher,
    ) = GpgAgentPublicKeySyncerImpl(
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
        gpgAgentPublicKeyRepository = repository,
        logRepository = NoOpLogRepository,
        defaultDispatcher = defaultDispatcher,
    )

    private class RecordingGpgAgentPublicKeyRepository : GpgAgentPublicKeyRepository {
        var keys: List<GpgAgentPublicKeyRow> = emptyList()

        override fun get(): IO<List<GpgAgentPublicKeyRow>> = {
            keys
        }

        override fun getByKeygrip(
            keygrip: String,
        ): IO<GpgAgentPublicKeyRow?> = {
            keys.firstOrNull { it.keygrip == keygrip }
        }

        override fun replaceAll(
            keys: List<GpgAgentPublicKeyRow>,
        ): IO<Unit> = {
            this.keys = keys
        }

        override fun clear(): IO<Unit> = {
            keys = emptyList()
        }

        override fun clearNames(): IO<Unit> = {
            keys = keys.map { it.copy(name = null) }
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
}
