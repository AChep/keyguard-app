package com.artemchep.keyguard.common.service.sshagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRow
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetSshAgentDisplayKeyNames
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
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
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SshAgentPublicKeySyncerImplTest {
    private val cryptoGenerator = CryptoGeneratorJvm()
    private val base64Service = Base64ServiceJvm()

    @Test
    fun `syncer applies SSH eligibility and filters before caching`() = runTest {
        val repository = RecordingSshAgentPublicKeyRepository()
        val includedPublicKey = buildOpenSshPublicKey("ssh-ed25519", seed = 1)
        val excludedPublicKey = buildOpenSshPublicKey("ssh-ed25519", seed = 2)
        val ciphers = MutableStateFlow(
            listOf(
                createSshSecret(
                    id = "include",
                    name = "Included",
                    publicKey = "$includedPublicKey raw-comment",
                    fingerprint = "SHA256:included",
                ),
                createSshSecret(
                    id = "exclude",
                    name = "Excluded",
                    publicKey = excludedPublicKey,
                    fingerprint = "SHA256:excluded",
                ),
                createSshSecret(
                    id = "deleted",
                    name = "Deleted",
                    publicKey = buildOpenSshPublicKey("ssh-ed25519", seed = 3),
                    fingerprint = "SHA256:deleted",
                    deletedDate = Instant.parse("2024-02-01T00:00:00Z"),
                ),
                createLoginSecret(id = "login"),
                createSshSecret(
                    id = "blank",
                    name = "Blank",
                    publicKey = "",
                    fingerprint = "SHA256:blank",
                ),
                createSshSecret(
                    id = "invalid",
                    name = "Invalid",
                    publicKey = "ssh-ed25519 AAAA...",
                    fingerprint = "SHA256:invalid",
                ),
            ),
        )
        val syncer = createSyncer(
            repository = repository,
            ciphers = ciphers,
            sshAgentEnabled = MutableStateFlow(true),
            displayKeyNames = MutableStateFlow(true),
            filter = MutableStateFlow(
                SshAgentFilter(
                    state = mapOf(
                        "only-included" to setOf(
                            DFilter.ById(
                                id = "include",
                                what = DFilter.ById.What.CIPHER,
                            ),
                        ),
                    ),
                ),
            ),
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )

        val job = syncer.launch(this)
        try {
            advanceUntilIdle()

            val key = repository.keys.single()
            assertEquals("Included", key.name)
            assertEquals(includedPublicKey, key.publicKey)
            assertEquals("ssh-ed25519", key.keyType)
            assertEquals("SHA256:included", key.fingerprint)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `syncer clears cache when SSH agent is disabled`() = runTest {
        val repository = RecordingSshAgentPublicKeyRepository(
            initialKeys = listOf(
                SshAgentPublicKeyRow(
                    publicKeyBlobSha256 = "existing",
                    publicKey = "ssh-ed25519 cached",
                    keyType = "ssh-ed25519",
                    fingerprint = "SHA256:cached",
                    name = "Cached",
                ),
            ),
        )
        val syncer = createSyncer(
            repository = repository,
            ciphers = MutableStateFlow(emptyList()),
            sshAgentEnabled = MutableStateFlow(false),
            displayKeyNames = MutableStateFlow(true),
            filter = MutableStateFlow(SshAgentFilter()),
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )

        val job = syncer.launch(this)
        try {
            advanceUntilIdle()

            assertEquals(emptyList(), repository.keys)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `syncer stores null names when display key names is disabled`() = runTest {
        val repository = RecordingSshAgentPublicKeyRepository()
        val syncer = createSyncer(
            repository = repository,
            ciphers = MutableStateFlow(
                listOf(
                    createSshSecret(
                        id = "key",
                        name = "Private item name",
                        publicKey = buildOpenSshPublicKey("ssh-ed25519", seed = 1),
                        fingerprint = "SHA256:key",
                    ),
                ),
            ),
            sshAgentEnabled = MutableStateFlow(true),
            displayKeyNames = MutableStateFlow(false),
            filter = MutableStateFlow(SshAgentFilter()),
            defaultDispatcher = StandardTestDispatcher(testScheduler),
        )

        val job = syncer.launch(this)
        try {
            advanceUntilIdle()

            assertNull(repository.keys.single().name)
        } finally {
            job.cancel()
        }
    }

    private fun createSyncer(
        repository: SshAgentPublicKeyRepository,
        ciphers: Flow<List<DSecret>>,
        sshAgentEnabled: Flow<Boolean>,
        displayKeyNames: Flow<Boolean>,
        filter: Flow<SshAgentFilter>,
        defaultDispatcher: CoroutineDispatcher,
    ) = SshAgentPublicKeySyncerImpl(
        directDI = DI {}.direct,
        getCiphers = object : GetCiphers {
            override fun invoke(): Flow<List<DSecret>> = ciphers
        },
        getSshAgent = object : GetSshAgent {
            override fun invoke(): Flow<Boolean> = sshAgentEnabled
        },
        getSshAgentFilter = object : GetSshAgentFilter {
            override fun invoke(): Flow<SshAgentFilter> = filter
        },
        getSshAgentDisplayKeyNames = object : GetSshAgentDisplayKeyNames {
            override fun invoke(): Flow<Boolean> = displayKeyNames
        },
        sshAgentPublicKeyRepository = repository,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        logRepository = NoOpLogRepository,
        defaultDispatcher = defaultDispatcher,
    )

    private class RecordingSshAgentPublicKeyRepository(
        initialKeys: List<SshAgentPublicKeyRow> = emptyList(),
    ) : SshAgentPublicKeyRepository {
        var keys: List<SshAgentPublicKeyRow> = initialKeys

        override fun get(): IO<List<SshAgentPublicKeyRow>> = {
            keys
        }

        override fun getByPublicKeyBlobSha256(
            publicKeyBlobSha256: String,
        ): IO<SshAgentPublicKeyRow?> = {
            keys.firstOrNull { it.publicKeyBlobSha256 == publicKeyBlobSha256 }
        }

        override fun getByPublicKey(
            publicKey: String,
        ): IO<SshAgentPublicKeyRow?> = {
            keys.firstOrNull { it.publicKey == publicKey }
        }

        override fun replaceAll(
            keys: List<SshAgentPublicKeyRow>,
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

    private fun createSshSecret(
        id: String,
        name: String,
        publicKey: String,
        fingerprint: String,
        deletedDate: Instant? = null,
    ): DSecret = createSecret(
        id = id,
        name = name,
        type = DSecret.Type.SshKey,
        deletedDate = deletedDate,
        sshKey = DSecret.SshKey(
            privateKey = "private-key-placeholder",
            publicKey = publicKey,
            fingerprint = fingerprint,
        ),
    )

    private fun createLoginSecret(
        id: String,
    ): DSecret = createSecret(
        id = id,
        name = "Login",
        type = DSecret.Type.Login,
        deletedDate = null,
        sshKey = null,
    )

    private fun createSecret(
        id: String,
        name: String,
        type: DSecret.Type,
        deletedDate: Instant?,
        sshKey: DSecret.SshKey?,
    ): DSecret = DSecret(
        id = id,
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
        createdDate = Instant.parse("2024-01-01T00:00:00Z"),
        archivedDate = null,
        deletedDate = deletedDate,
        service = BitwardenService(),
        name = name,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = type,
        sshKey = sshKey,
    )

    private companion object {
        fun buildOpenSshPublicKey(
            keyType: String,
            seed: Int,
        ): String {
            val blob = buildOpenSshPublicKeyBlob(
                keyType = keyType,
                seed = seed,
            )
            return "$keyType ${Base64.getEncoder().encodeToString(blob)}"
        }

        fun buildOpenSshPublicKeyBlob(
            keyType: String,
            seed: Int,
        ): ByteArray = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { dataOutput ->
                val keyTypeBytes = keyType.toByteArray(Charsets.US_ASCII)
                dataOutput.writeInt(keyTypeBytes.size)
                dataOutput.write(keyTypeBytes)

                val keyBytes = ByteArray(32) { (seed + it).toByte() }
                dataOutput.writeInt(keyBytes.size)
                dataOutput.write(keyBytes)
                dataOutput.flush()
            }
            output.toByteArray()
        }
    }
}
