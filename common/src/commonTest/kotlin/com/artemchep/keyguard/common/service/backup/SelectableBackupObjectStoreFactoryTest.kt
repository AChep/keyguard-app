package com.artemchep.keyguard.common.service.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlinx.io.Source

class SelectableBackupObjectStoreFactoryTest {
    @Test
    fun `routes local configs to local factory`() = runTest {
        val localFactory = RecordingBackupObjectStoreFactory()
        val webDavFactory = RecordingBackupObjectStoreFactory()
        val factory = SelectableBackupObjectStoreFactory(
            localFactory = localFactory,
            webDavFactory = webDavFactory,
        )
        val store = BackupStoreConfig.Local(
            path = "/tmp/keyguard-backups",
        )

        val result = factory.test(store)

        assertEquals(listOf<BackupStoreConfig>(store), localFactory.openedStores)
        assertEquals(emptyList<BackupStoreConfig>(), webDavFactory.openedStores)
        assertEquals(localFactory.store.result, result)
        assertEquals(1, localFactory.store.closeCalls)
        assertEquals(0, webDavFactory.store.closeCalls)
    }

    @Test
    fun `routes web dav configs to web dav factory`() = runTest {
        val localFactory = RecordingBackupObjectStoreFactory()
        val webDavFactory = RecordingBackupObjectStoreFactory()
        val factory = SelectableBackupObjectStoreFactory(
            localFactory = localFactory,
            webDavFactory = webDavFactory,
        )
        val store = BackupStoreConfig.WebDav(
            url = "https://example.com/dav/",
        )

        val result = factory.test(store)

        assertEquals(emptyList<BackupStoreConfig>(), localFactory.openedStores)
        assertEquals(listOf<BackupStoreConfig>(store), webDavFactory.openedStores)
        assertEquals(webDavFactory.store.result, result)
        assertEquals(0, localFactory.store.closeCalls)
        assertEquals(1, webDavFactory.store.closeCalls)
    }
}

private class RecordingBackupObjectStoreFactory : BackupObjectStoreFactory {
    val store = RecordingBackupObjectStore()
    val openedStores = mutableListOf<BackupStoreConfig>()

    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore {
        openedStores += store
        return this.store
    }
}

private class RecordingBackupObjectStore : BackupObjectStore {
    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = true,
        rangeRead = true,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )
    val result = BackupObjectStoreTestResult(
        probeKey = BackupObjectKey("health-check/test.probe"),
        bytesWritten = 1L,
        bytesRead = 1L,
        listed = true,
        deleted = true,
        rangeRead = true,
        capabilities = capabilities,
    )
    var closeCalls: Int = 0

    override suspend fun test(): BackupObjectStoreTestResult = result

    override suspend fun close() {
        closeCalls += 1
    }

    override suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo? = error("Not used by this test.")

    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source = error("Not used by this test.")

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (Sink) -> Unit,
    ): BackupObjectInfo = error("Not used by this test.")

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage = error("Not used by this test.")

    override suspend fun delete(
        key: BackupObjectKey,
    ) {
        error("Not used by this test.")
    }
}
