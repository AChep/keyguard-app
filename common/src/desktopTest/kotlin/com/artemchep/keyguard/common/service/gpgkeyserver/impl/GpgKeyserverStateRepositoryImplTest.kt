package com.artemchep.keyguard.common.service.gpgkeyserver.impl

import com.artemchep.keyguard.common.model.DGpgKeyserverState
import com.artemchep.keyguard.common.model.GpgKeyserverVerificationStatus
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GpgKeyserverStateRepositoryImplTest {
    @Test
    fun `put normalizes fingerprint and looks up state`() = runTest {
        val repository = createRepository()

        repository.put(
            model(
                fingerprint = "ab cd:ef 01",
                cipherId = "cipher-a",
                status = GpgKeyserverVerificationStatus.VERIFIED,
            ),
        )()

        val byFingerprint = repository.getByFingerprint("AB:CD EF01").first()
        val byCipher = repository.getByCipherId("cipher-a").first()

        assertEquals("ABCDEF01", byFingerprint?.fingerprint)
        assertEquals(GpgKeyserverVerificationStatus.VERIFIED, byFingerprint?.verificationStatus)
        assertEquals(listOf("ABCDEF01"), byCipher.map { it.fingerprint })
    }

    @Test
    fun `put replaces existing state by normalized fingerprint`() = runTest {
        val repository = createRepository()
        repository.put(
            model(
                fingerprint = "ab cd ef 01",
                status = GpgKeyserverVerificationStatus.NOT_FOUND,
                sourceKeyserver = "https://old.example",
            ),
        )()

        repository.put(
            model(
                fingerprint = "ABCDEF01",
                status = GpgKeyserverVerificationStatus.FOUND_UNVERIFIED,
                sourceKeyserver = "https://new.example",
            ),
        )()

        val state = repository.getAll().first().single()

        assertEquals("ABCDEF01", state.fingerprint)
        assertEquals(GpgKeyserverVerificationStatus.FOUND_UNVERIFIED, state.verificationStatus)
        assertEquals("https://new.example", state.sourceKeyserver)
    }

    @Test
    fun `remove by fingerprint and remove all clear state`() = runTest {
        val repository = createRepository()
        repository.put(model(fingerprint = "ab cd ef 01"))()
        repository.put(model(fingerprint = "12 34", cipherId = "cipher-b"))()

        repository.removeByFingerprint("AB:CD EF01")()

        assertNull(repository.getByFingerprint("abcdef01").first())
        assertEquals(listOf("1234"), repository.getAll().first().map { it.fingerprint })

        repository.removeAll()()

        assertEquals(emptyList(), repository.getAll().first())
    }

    private fun createRepository(): GpgKeyserverStateRepositoryImpl {
        val database = createUploadTestDatabase()
        return GpgKeyserverStateRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(database),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun model(
        fingerprint: String,
        cipherId: String? = null,
        status: GpgKeyserverVerificationStatus = GpgKeyserverVerificationStatus.UNKNOWN,
        checkedAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        refreshedAt: Instant? = null,
        sourceKeyserver: String? = "https://keys.openpgp.org",
    ) = DGpgKeyserverState(
        fingerprint = fingerprint,
        cipherId = cipherId,
        verificationStatus = status,
        lastCheckedAt = checkedAt,
        lastRefreshedAt = refreshedAt,
        sourceKeyserver = sourceKeyserver,
    )
}
