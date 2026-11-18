package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.CipherHistoryType
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.insertLocalCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CipherHistoryOpenedRepositoryImplTest {
    @Test
    fun `cleanup expires opens autofills and passkey uses strictly before the cutoff`() = runTest {
        val database = createUploadTestDatabase()
        val cipherId = "cipher-1"
        database.insertUploadTestAccount()
        insertLocalCipher(database, testBitwardenCipher(cipherId = cipherId))
        val repository = CipherHistoryOpenedRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(database),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val cutoff = Instant.parse("2026-01-01T00:00:00Z")
        val before = cutoff - 1.milliseconds
        val after = cutoff + 1.milliseconds
        with(database.cipherUsageHistoryQueries) {
            insert(cipherId, null, CipherHistoryType.OPENED, before)
            insert(cipherId, null, CipherHistoryType.USED_AUTOFILL, before)
            insert(cipherId, "old-key", CipherHistoryType.USED_PASSKEY, before)
            insert(cipherId, null, CipherHistoryType.USED_AUTOFILL, cutoff)
            insert(cipherId, "new-key", CipherHistoryType.USED_PASSKEY, after)
        }

        repository.removeOlderThan(cutoff).bind()

        val remaining = database.cipherUsageHistoryQueries.get(10).executeAsList()
        assertEquals(listOf(after, cutoff), remaining.map { it.createdAt })
        assertNull(repository.getCredentialLastUsed(cipherId, "old-key").first())
        assertEquals(after, repository.getCredentialLastUsed(cipherId, "new-key").first())
    }
}
