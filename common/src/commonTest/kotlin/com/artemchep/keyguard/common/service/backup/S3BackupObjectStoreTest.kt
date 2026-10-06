package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.s3.InMemoryS3Client
import com.artemchep.keyguard.common.service.s3.InMemoryS3ClientFactory
import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.util.io.readByteArrayAndClose
import com.artemchep.keyguard.util.io.spool.AdaptiveSpool
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Operation
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3BackupObjectStoreTest {
    private val stagingSpoolFactory = MemoryStagingSpoolFactory()

    @Test
    fun `objects live under the prefix`() = runTest {
        val client = InMemoryS3Client()
        val store = store(client)

        store.write(BackupObjectKey("indexes/a.zip")) { sink -> sink.write(byteArrayOf(1, 2, 3)) }

        assertContentEquals(byteArrayOf(1, 2, 3), client.objects["keyguard/indexes/a.zip"])
        val info = assertNotNull(store.stat(BackupObjectKey("indexes/a.zip")))
        assertEquals(3L, info.size)
        assertContentEquals(
            byteArrayOf(2, 3),
            store.read(BackupObjectKey("indexes/a.zip"), BackupByteRange(offset = 1)).readByteArrayAndClose(),
        )
        assertEquals(listOf(StagingPurpose.BackupObjectUpload), stagingSpoolFactory.purposes)
    }

    @Test
    fun `create writes are conditional`() = runTest {
        val client = InMemoryS3Client()
        val store = store(client)

        store.write(BackupObjectKey("repo.zip"), BackupWriteMode.Create) { it.write(byteArrayOf(1)) }
        store.write(BackupObjectKey("repo.zip"), BackupWriteMode.CreateOrReplace) { it.write(byteArrayOf(2)) }

        assertEquals(
            listOf<S3WritePrecondition?>(S3WritePrecondition.IfNoneMatch, null),
            client.preconditions.map { it.second },
        )
        assertFailsWith<BackupObjectStoreException.AlreadyExists> {
            store.write(BackupObjectKey("repo.zip"), BackupWriteMode.Create) { it.write(byteArrayOf(3)) }
        }
    }

    @Test
    fun `list strips the prefix pages and skips foreign keys`() = runTest {
        val client = InMemoryS3Client(pageSize = 2)
        client.objects["keyguard/blobs/a.zip"] = byteArrayOf(1)
        client.objects["keyguard/blobs/b.zip"] = byteArrayOf(1)
        client.objects["keyguard/blobs/c.zip"] = byteArrayOf(1)
        client.objects["keyguard/blobs/folder/"] = byteArrayOf()
        client.objects["other/blobs/d.zip"] = byteArrayOf(1)
        val store = store(client)

        val keys = mutableListOf<String>()
        var cursor: BackupListCursor? = null
        do {
            val page = store.list(BackupObjectKeyPrefix("blobs/"), cursor)
            keys += page.items.map { it.key.value }
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(listOf("blobs/a.zip", "blobs/b.zip", "blobs/c.zip"), keys)
    }

    @Test
    fun `delete and close reach the client`() = runTest {
        val client = InMemoryS3Client()
        client.objects["keyguard/a.zip"] = byteArrayOf(1)
        val store = store(client)

        store.delete(BackupObjectKey("a.zip"))
        store.close()

        assertNull(client.objects["keyguard/a.zip"])
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun `exceptions are translated`() = runTest {
        val client = InMemoryS3Client()
        val store = store(client)

        assertFailsWith<BackupObjectStoreException.NotFound> {
            store.read(BackupObjectKey("missing.zip"), null)
        }
        client.getError = S3Exception.AuthenticationFailed(S3Operation.Get)
        assertFailsWith<BackupObjectStoreException.AuthenticationFailed> {
            store.read(BackupObjectKey("a.zip"), null)
        }
        client.getError = S3Exception.Transient(S3Operation.Get, "a.zip")
        assertFailsWith<BackupObjectStoreException.Transient> {
            store.read(BackupObjectKey("a.zip"), null)
        }
        client.getError = S3Exception.WrongRegion(S3Operation.Get, expectedRegion = "eu-west-1")
        val e = assertFailsWith<BackupObjectStoreException.PermissionDenied> {
            store.read(BackupObjectKey("a.zip"), null)
        }
        assertTrue(e.cause is S3Exception.WrongRegion)
    }

    @Test
    fun `an invalid range error without a requested range maps like other rejections`() = runTest {
        val client = InMemoryS3Client()
        val store = store(client)

        client.getError = S3Exception.InvalidRange(S3Operation.Get, "keyguard/a.zip", statusCode = 416)
        val e = assertFailsWith<BackupObjectStoreException.PermissionDenied> {
            store.read(BackupObjectKey("a.zip"), null)
        }
        assertTrue(e.cause is S3Exception.InvalidRange)
        assertFailsWith<BackupObjectStoreException.InvalidRange> {
            store.read(BackupObjectKey("a.zip"), BackupByteRange(offset = 1))
        }
    }

    @Test
    fun `the default store test passes`() = runTest {
        val client = InMemoryS3Client()
        val result = store(client).test()

        assertTrue(result.listed)
        assertTrue(result.deleted)
        // The server must enforce the condition of a replacement too.
        assertTrue(client.preconditions.any { (_, precondition) -> precondition is S3WritePrecondition.IfMatch })
        assertTrue(client.objects.isEmpty())
    }

    @Test
    fun `the store test rejects a server that ignores conditional writes`() = runTest {
        val client = InMemoryS3Client(ignoresPreconditions = true)

        val e = assertFailsWith<BackupObjectStoreException.VerificationFailed> { store(client).test() }

        assertEquals(BackupObjectStoreOperation.Test, e.operation)
        assertTrue(client.objects.isEmpty())
    }

    @Test
    fun `factory probes the bucket and maps open failures`() = runTest {
        val factory = InMemoryS3ClientFactory()
        val s3Factory = S3BackupObjectStoreFactory(factory, stagingSpoolFactory)
        val config = BackupStoreConfig.S3(
            bucket = "backups",
            prefix = "keyguard/",
            accessKeyId = "AKID",
            secretAccessKey = Password("secret"),
        )

        s3Factory.open(config)
        assertEquals("backups", factory.configs.single().bucket)

        factory.client.listError = S3Exception.AuthenticationFailed(S3Operation.List)
        assertFailsWith<BackupObjectStoreException.AuthenticationFailed> { s3Factory.open(config) }
        factory.client.listError = S3Exception.BucketNotFound(S3Operation.List)
        assertFailsWith<BackupObjectStoreException.PermissionDenied> { s3Factory.open(config) }
        factory.client.listError = S3Exception.Transient(S3Operation.List, null)
        assertFailsWith<BackupObjectStoreException.Transient> { s3Factory.open(config) }
        assertFailsWith<IllegalArgumentException> { s3Factory.open(config.copy(secretAccessKey = null)) }
        assertEquals(3, factory.client.closeCalls)
    }

    @Test
    fun `factory closes the client when the probe fails for any reason`() = runTest {
        val factory = InMemoryS3ClientFactory()
        val s3Factory = S3BackupObjectStoreFactory(factory, stagingSpoolFactory)
        val config = BackupStoreConfig.S3(
            bucket = "backups",
            accessKeyId = "AKID",
            secretAccessKey = Password("secret"),
        )

        factory.client.listError = IllegalStateException("broken")
        assertFailsWith<IllegalStateException> { s3Factory.open(config) }
        factory.client.listError = CancellationException("cancelled")
        assertFailsWith<CancellationException> { s3Factory.open(config) }

        assertEquals(2, factory.client.closeCalls)
    }

    private fun store(
        client: InMemoryS3Client,
    ) = S3BackupObjectStore(
        client = client,
        prefix = "keyguard/",
        stagingSpoolFactory = stagingSpoolFactory,
    )
}

private class MemoryStagingSpoolFactory : StagingSpoolFactory {
    val purposes = mutableListOf<StagingPurpose>()

    override fun create(
        purpose: StagingPurpose,
        limits: SpoolLimits,
        checkCancellation: () -> Unit,
        limitExceeded: (maximumBytes: Long) -> Throwable,
    ): ByteStoreWriter {
        purposes += purpose
        return AdaptiveSpool(
            memoryLimitBytes = limits.maximumBytes,
            maximumBytes = limits.maximumBytes,
            spillFactory = { error("Tests never spill.") },
            limitExceeded = limitExceeded,
        )
    }
}
