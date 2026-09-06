package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.LocalItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ServerItemMeta
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy.KeePassCipherSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class KeePassTreeSyncExecutorFailureHandlingTest {
    @Test
    fun `ordinary folder failure is isolated and cipher phase continues`() = runTest {
        val failure = IllegalStateException("folder metadata failed")

        val result = executor().execute(
            folders = folderInputs(failure),
            cipherInputs = ::emptyCipherInputs,
        )

        val folderOutcome = assertIs<EntityTypeOutcome.Failed>(result.outcomes.getValue("folder"))
        assertTrue(folderOutcome.error === failure)
        assertIs<EntityTypeOutcome.Completed>(result.outcomes.getValue("cipher"))
    }

    @Test
    fun `fatal folder failure propagates`() = runTest {
        val failure = AssertionError("folder runtime is broken")

        val actual = assertFailsWith<AssertionError> {
            executor().execute(
                folders = folderInputs(failure),
                cipherInputs = { error("fatal folder failure must stop before cipher inputs") },
            )
        }

        assertTrue(actual === failure)
    }

    @Test
    fun `folder cancellation propagates`() = runTest {
        val failure = CancellationException("folder sync cancelled")

        val actual = assertFailsWith<CancellationException> {
            executor().execute(
                folders = folderInputs(failure),
                cipherInputs = { error("cancellation must stop before cipher inputs") },
            )
        }

        assertTrue(actual === failure)
    }

    private fun executor() = KeePassTreeSyncExecutor(
        diagnostics = KeePassSyncDiagnostics(logRepository = null, enabled = false),
    )

    private fun folderInputs(failure: Throwable): KeePassTreeSyncExecutor.FolderInputs =
        KeePassTreeSyncExecutor.FolderInputs(
            localFolders = listOf(
                BitwardenFolder(
                    accountId = "account",
                    folderId = "folder",
                    revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
                    service = BitwardenService(version = BitwardenService.VERSION),
                    name = "Folder",
                ),
            ),
            remoteFolders = emptyList(),
            strategy = ThrowingFolderStrategy(failure),
            ops = UnusedEntitySyncOps(),
        )

    private fun emptyCipherInputs() = KeePassTreeSyncExecutor.CipherInputs(
        localCiphers = emptyList(),
        remoteCiphers = emptyList(),
        strategy = KeePassCipherSyncStrategy(remoteFolderIdToLocalId = { null }),
        ops = UnusedEntitySyncOps(),
    )
}

private class ThrowingFolderStrategy(
    private val failure: Throwable,
) : EntitySyncStrategy<BitwardenFolder, KeePassFolder> {
    override fun toLocalItemMeta(entity: BitwardenFolder): LocalItemMeta = throw failure

    override fun toServerItemMeta(entity: KeePassFolder): ServerItemMeta =
        error("No server folder is expected in this test.")
}

private class UnusedEntitySyncOps<Local : BitwardenService.Has<Local>, Server : Any> :
    EntitySyncOps<Local, Server> {
    override suspend fun readLocal(localId: String): Local? =
        error("No entity operation is expected in this test.")

    override suspend fun insertOrUpdateLocally(entries: List<Pair<Server, Local?>>) =
        error("No entity operation is expected in this test.")

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<Server, Local>>,
    ): LocalUpdateResult = error("No entity operation is expected in this test.")

    override suspend fun deleteLocally(localIds: List<String>) =
        error("No entity operation is expected in this test.")

    override suspend fun saveLocal(local: Local, previousLocal: Local?) =
        error("No entity operation is expected in this test.")

    override suspend fun pushToServer(
        local: Local,
        server: Server?,
        force: Boolean,
    ): RemoteWriteOutcome<Local> = error("No entity operation is expected in this test.")

    override suspend fun deleteOnServer(
        local: Local,
        serverId: String,
    ): RemoteWriteOutcome<Local> = error("No entity operation is expected in this test.")

    override suspend fun mergeConflict(
        local: Local,
        server: Server,
    ): RemoteWriteOutcome<Local> = error("No entity operation is expected in this test.")
}
