package com.artemchep.keyguard.common.service.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.write

class BackupObjectStoreTest {
    @Test
    fun `object keys reject Windows filesystem path forms`() {
        listOf(
            "\\absolute\\object.zip",
            "snapshots\\..\\repo.zip",
            "C:/backup/repo.zip",
            "C:\\backup\\repo.zip",
            "C:backup/repo.zip",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                BackupObjectKey(value)
            }
        }
    }

    @Test
    fun `object key prefixes reject Windows filesystem path forms`() {
        listOf(
            "\\absolute\\",
            "snapshots\\..\\",
            "C:/backup/",
            "C:\\backup\\",
            "C:backup/",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                BackupObjectKeyPrefix(value)
            }
        }
    }

    @Test
    fun `test writes reads lists range reads and deletes probe`() = runTest {
        val store = ProbeFakeBackupObjectStore()

        val result = store.test()

        assertTrue(result.probeKey.value.startsWith("health-check/"))
        assertEquals(result.bytesWritten, result.bytesRead)
        assertEquals(true, result.listed)
        assertEquals(true, result.deleted)
        assertEquals(true, result.rangeRead)
        assertEquals(result.probeKey, store.writtenKeys.single())
        assertEquals(result.probeKey, store.deletedKeys.last())
        assertTrue(store.objects.isEmpty())
    }

    @Test
    fun `test reports verification failure when read bytes differ`() = runTest {
        val store = ProbeFakeBackupObjectStore(
            corruptFullRead = true,
        )

        val error = assertFailsWith<BackupObjectStoreException.VerificationFailed> {
            store.test()
        }

        assertEquals(BackupObjectStoreOperation.Test, error.operation)
        assertTrue(error.reason.contains("different bytes"))
        assertTrue(store.objects.isEmpty())
    }

    @Test
    fun `test cleans up probe after later verification failure`() = runTest {
        val store = ProbeFakeBackupObjectStore(
            omitProbeFromList = true,
        )

        val error = assertFailsWith<BackupObjectStoreException.VerificationFailed> {
            store.test()
        }

        val key = assertNotNull(error.key)
        assertTrue(error.reason.contains("list"))
        assertTrue(store.deletedKeys.contains(key))
        assertTrue(store.objects.isEmpty())
    }

    @Test
    fun `factory test closes opened store`() = runTest {
        val factory = ProbeFakeBackupObjectStoreFactory()

        factory.test(BackupStoreConfig.Local())

        assertEquals(1, factory.store.closeCalls)
    }
}

private class ProbeFakeBackupObjectStoreFactory : BackupObjectStoreFactory {
    val store = ProbeFakeBackupObjectStore()

    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore = this.store
}

private class ProbeFakeBackupObjectStore(
    private val corruptFullRead: Boolean = false,
    private val omitProbeFromList: Boolean = false,
) : BackupObjectStore {
    val objects = mutableMapOf<BackupObjectKey, ByteArray>()
    val writtenKeys = mutableListOf<BackupObjectKey>()
    val deletedKeys = mutableListOf<BackupObjectKey>()
    var closeCalls: Int = 0

    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = true,
        atomicReplace = true,
        rangeRead = true,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )

    override suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo? = objects[key]?.let { data ->
        BackupObjectInfo(
            key = key,
            size = data.size.toLong(),
            updatedAt = null,
        )
    }

    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source {
        val data = objects[key]
            ?: throw BackupObjectStoreException.NotFound(
                key = key,
                operation = BackupObjectStoreOperation.Read,
            )
        val bytes = if (range != null) {
            val start = range.offset.toInt()
            val end = range.length
                ?.let { length -> start + length.toInt() }
                ?: data.size
            if (start > data.size || end > data.size) {
                throw BackupObjectStoreException.InvalidRange(
                    key = key,
                    range = range,
                )
            }
            data.copyOfRange(start, end)
        } else if (corruptFullRead) {
            data + 0
        } else {
            data
        }
        return Buffer().apply {
            write(bytes)
        }
    }

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (Sink) -> Unit,
    ): BackupObjectInfo {
        if (mode == BackupWriteMode.Create && key in objects) {
            throw BackupObjectStoreException.AlreadyExists(key)
        }

        val buffer = Buffer()
        write(buffer)
        val data = buffer.readByteArray()
        objects[key] = data
        writtenKeys += key
        return BackupObjectInfo(
            key = key,
            size = data.size.toLong(),
            updatedAt = null,
        )
    }

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage {
        val items = objects
            .filterKeys { key ->
                key.value.startsWith(prefix.value) &&
                        !(omitProbeFromList && key.value.startsWith("health-check/"))
            }
            .map { (key, data) ->
                BackupObjectInfo(
                    key = key,
                    size = data.size.toLong(),
                    updatedAt = null,
                )
            }
            .sortedBy { it.key.value }
        return BackupObjectListPage(items)
    }

    override suspend fun delete(
        key: BackupObjectKey,
    ) {
        deletedKeys += key
        objects.remove(key)
    }

    override suspend fun close() {
        closeCalls += 1
    }
}
