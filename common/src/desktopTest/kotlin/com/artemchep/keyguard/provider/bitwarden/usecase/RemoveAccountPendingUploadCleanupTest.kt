package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.database.vault.VaultDatabaseManager
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestMarkBackupAsDirty
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestUnusedFileService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestWatchdog
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.insertAccount
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadGarbageCollector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class RemoveAccountPendingUploadCleanupTest {
    @Test
    fun `single account removal purges only after its row is deleted`() = runTest {
        val database = createUploadTestDatabase()
        insertAccount(database, accountId = "account-1")
        insertAccount(database, accountId = "account-2")
        val garbageCollector = RecordingPendingUploadGarbageCollector(database)
        val removeAccount = RemoveAccountByIdImpl(
            db = UploadTestVaultDatabaseManager(database),
            fileService = UploadTestUnusedFileService,
            watchdog = UploadTestWatchdog,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
            pendingUploadGarbageCollector = garbageCollector,
        )

        removeAccount(setOf(AccountId("account-1"))).bind()

        assertNull(database.findAccount("account-1"))
        assertNotNull(database.findAccount("account-2"))
        assertEquals(listOf("account-1"), garbageCollector.purgeAccountIds)
        assertEquals(listOf(false), garbageCollector.accountExistedAtPurge)
    }

    @Test
    fun `bulk account removal purges every captured account after rows are deleted`() = runTest {
        val database = createUploadTestDatabase()
        insertAccount(database, accountId = "account-1")
        insertAccount(database, accountId = "account-2")
        val garbageCollector = RecordingPendingUploadGarbageCollector(database)
        val removeAccounts = RemoveAccountsImpl(
            db = UploadTestVaultDatabaseManager(database),
            fileService = UploadTestUnusedFileService,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
            pendingUploadGarbageCollector = garbageCollector,
        )

        removeAccounts().bind()

        assertEquals(emptyList(), database.accountQueries.get().executeAsList())
        assertEquals(
            setOf("account-1", "account-2"),
            garbageCollector.purgeAccountIds.toSet(),
        )
        assertEquals(listOf(false, false), garbageCollector.accountExistedAtPurge)
    }

    @Test
    fun `single account removal finishes purge after caller cancellation`() = runTest {
        val database = createUploadTestDatabase()
        insertAccount(database, accountId = "account-1")
        val garbageCollector = SuspendingPendingUploadGarbageCollector()
        val removeAccount = RemoveAccountByIdImpl(
            db = UploadTestVaultDatabaseManager(database),
            fileService = UploadTestUnusedFileService,
            watchdog = UploadTestWatchdog,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
            pendingUploadGarbageCollector = garbageCollector,
        )
        val removalJob = launch {
            removeAccount(setOf(AccountId("account-1"))).bind()
        }
        garbageCollector.firstPurgeStarted.await()

        assertNull(database.findAccount("account-1"))
        removalJob.cancel()
        garbageCollector.allowFirstPurgeToFinish.complete(Unit)
        removalJob.join()

        assertEquals(
            listOf("account-1"),
            garbageCollector.completedPurgeAccountIds,
        )
    }

    @Test
    fun `bulk account removal finishes every purge after caller cancellation`() = runTest {
        val database = createUploadTestDatabase()
        insertAccount(database, accountId = "account-1")
        insertAccount(database, accountId = "account-2")
        val garbageCollector = SuspendingPendingUploadGarbageCollector()
        val removeAccounts = RemoveAccountsImpl(
            db = UploadTestVaultDatabaseManager(database),
            fileService = UploadTestUnusedFileService,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
            pendingUploadGarbageCollector = garbageCollector,
        )
        val removalJob = launch {
            removeAccounts().bind()
        }
        garbageCollector.firstPurgeStarted.await()

        assertEquals(emptyList(), database.accountQueries.get().executeAsList())
        removalJob.cancel()
        garbageCollector.allowFirstPurgeToFinish.complete(Unit)
        removalJob.join()

        assertEquals(
            setOf("account-1", "account-2"),
            garbageCollector.completedPurgeAccountIds.toSet(),
        )
    }

    @Test
    fun `failed account deletion does not purge staged uploads`() = runTest {
        val database = createUploadTestDatabase()
        insertAccount(database, accountId = "account-1")
        val databaseManager = UploadTestVaultDatabaseManager(database)
        val failingDatabaseManager = object : VaultDatabaseManager by databaseManager {
            override fun <T> mutate(
                tag: String,
                block: suspend (Database) -> T,
            ): IO<T> = {
                error("delete failed")
            }
        }
        val garbageCollector = RecordingPendingUploadGarbageCollector(database)
        val removeAccount = RemoveAccountByIdImpl(
            db = failingDatabaseManager,
            fileService = UploadTestUnusedFileService,
            watchdog = UploadTestWatchdog,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
            pendingUploadGarbageCollector = garbageCollector,
        )

        assertFailsWith<IllegalStateException> {
            removeAccount(setOf(AccountId("account-1"))).bind()
        }

        assertNotNull(database.findAccount("account-1"))
        assertEquals(emptyList(), garbageCollector.purgeAccountIds)
    }
}

private class SuspendingPendingUploadGarbageCollector : PendingUploadGarbageCollector {
    val firstPurgeStarted = CompletableDeferred<Unit>()
    val allowFirstPurgeToFinish = CompletableDeferred<Unit>()
    val completedPurgeAccountIds = mutableListOf<String>()
    private var firstPurge = true

    override fun invoke(
        accountId: String,
    ): IO<Unit> = error("Not used by this test")

    override fun purge(
        accountId: String,
    ): IO<Unit> = {
        if (firstPurge) {
            firstPurge = false
            firstPurgeStarted.complete(Unit)
            allowFirstPurgeToFinish.await()
        }
        completedPurgeAccountIds += accountId
    }
}

private class RecordingPendingUploadGarbageCollector(
    private val database: Database,
) : PendingUploadGarbageCollector {
    val purgeAccountIds = mutableListOf<String>()
    val accountExistedAtPurge = mutableListOf<Boolean>()

    override fun invoke(
        accountId: String,
    ): IO<Unit> = error("Not used by this test")

    override fun purge(
        accountId: String,
    ): IO<Unit> = {
        purgeAccountIds += accountId
        accountExistedAtPurge += database.findAccount(accountId) != null
    }
}


private fun Database.findAccount(
    accountId: String,
) = accountQueries
    .getByAccountId(accountId)
    .executeAsOneOrNull()
