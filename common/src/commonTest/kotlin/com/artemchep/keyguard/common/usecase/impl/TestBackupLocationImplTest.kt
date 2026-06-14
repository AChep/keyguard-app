package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.backup.BackupByteRange
import com.artemchep.keyguard.common.service.backup.BackupConfig
import com.artemchep.keyguard.common.service.backup.BackupListCursor
import com.artemchep.keyguard.common.service.backup.BackupObjectInfo
import com.artemchep.keyguard.common.service.backup.BackupObjectKey
import com.artemchep.keyguard.common.service.backup.BackupObjectKeyPrefix
import com.artemchep.keyguard.common.service.backup.BackupObjectListPage
import com.artemchep.keyguard.common.service.backup.BackupObjectStore
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreCapabilities
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreFactory
import com.artemchep.keyguard.common.service.backup.BackupObjectStoreTestResult
import com.artemchep.keyguard.common.service.backup.BackupStoreConfig
import com.artemchep.keyguard.common.service.backup.BackupWriteMode
import com.artemchep.keyguard.common.model.Password
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlinx.io.Source

class TestBackupLocationImplTest {
    @Test
    fun `tests selected repository path through object store factory`() = runTest {
        val factory = FakeBackupObjectStoreFactory()
        val useCase = TestBackupLocationImpl(factory)
        val config = BackupConfig(
            enabled = true,
            store = BackupStoreConfig.WebDav(
                url = "https://example.com/dav/",
                username = "alice",
                password = Password("secret"),
            ),
        )

        val result = useCase(config).bind()

        assertEquals(config.store, factory.openedStore)
        assertEquals(factory.store.result, result)
        assertEquals(1, factory.store.closeCalls)
    }
}

private class FakeBackupObjectStoreFactory : BackupObjectStoreFactory {
    val store = FakeBackupObjectStore()
    var openedStore: BackupStoreConfig? = null

    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore {
        openedStore = store
        return this.store
    }
}

private class FakeBackupObjectStore : BackupObjectStore {
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
