package com.artemchep.keyguard.apple.model

import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.usecase.PrepareAttachmentContent
import com.artemchep.keyguard.feature.attachments.model.AttachmentDragPreparer
import com.artemchep.keyguard.feature.dragout.model.DragRepresentation
import com.artemchep.keyguard.feature.dragout.model.representations
import com.artemchep.keyguard.di.withKoin
import com.artemchep.keyguard.di.withVaultSession
import com.artemchep.keyguard.di.resolve
import com.artemchep.keyguard.platform.withTestAppleStorage
import com.artemchep.keyguard.util.io.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.Foundation.NSURL
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.lstat
import platform.posix.stat
import platform.posix.symlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real prepared-content service and Kotlin → NSData bridge, without a vault account. */
@OptIn(ExperimentalForeignApi::class)
class AttachmentDragContentSnapshotTest {
    @Test
    fun boundedDataLoadsAreRepeatable() = runTest {
        for (size in listOf(0, SMALL_BYTES, MAXIMUM_BYTES - 1, MAXIMUM_BYTES)) {
            withPrepared(size) { snapshot, expected, _, _ ->
                repeat(2) {
                    val result = withContext(Dispatchers.Default) {
                        snapshot.loadData(AttachmentDragReadCancellationSnapshot())
                    }
                    assertFalse(result.failed)
                    assertFalse(result.cancelled)
                    assertContentEquals(expected, assertNotNull(result.data).toByteArray())
                }
            }
        }
    }

    @Test
    fun actualBytesAboveTheDataCapAreRejectedBeforePublishingContent() = runTest {
        withPreparation(MAXIMUM_BYTES + 1) { prepare, request, _, _, _ ->
            val route = AttachmentDragPreparer(prepare)
            assertTrue(route.canPrepare(SMALL_BYTES.toLong()))
            assertFailsWith<IOException> { route(request) {} }
        }
    }

    @Test
    fun cancellationIsPerLoadAndConcurrentLoadsUseIndependentReaders() = runTest {
        withPrepared(SMALL_BYTES) { snapshot, expected, _, _ ->
            val cancelled = AttachmentDragReadCancellationSnapshot().apply { cancel() }
            val cancelledResult = withContext(Dispatchers.Default) { snapshot.loadData(cancelled) }
            assertTrue(cancelledResult.cancelled)
            assertNull(cancelledResult.data)
            coroutineScope {
                val first = async(Dispatchers.Default) { snapshot.loadData(AttachmentDragReadCancellationSnapshot()) }
                val second = async(Dispatchers.Default) { snapshot.loadData(AttachmentDragReadCancellationSnapshot()) }
                assertContentEquals(expected, assertNotNull(first.await().data).toByteArray())
                assertContentEquals(expected, assertNotNull(second.await().data).toByteArray())
            }
        }
    }

    @Test
    fun writesExactBytesAndEmptyFilesOwnerOnly() = runTest {
        // Spans several chunks, with a partial last one.
        for (size in listOf(0, 150_000)) {
            withPrepared(size) { snapshot, expected, _, directory ->
                repeat(2) { index ->
                    val path = "$directory/written-$index.bin"
                    assertEquals(AttachmentDragWriteResult.WRITTEN, snapshot.write(path))
                    assertContentEquals(expected, readFile(path))
                    assertEquals(OWNER_READ_WRITE, mode(path) and PERMISSION_BITS)
                }
                assertTrue(snapshot.isAvailable())
            }
        }
    }

    @Test
    fun writesNeverReplaceAnExistingFileOrFollowALink() = runTest {
        withPrepared(SMALL_BYTES) { snapshot, _, _, directory ->
            val existing = "$directory/existing"
            val link = "$directory/link"
            writeFile(existing, byteArrayOf(9))
            assertEquals(0, symlink(existing, link))
            for (path in listOf(existing, link)) {
                assertEquals(AttachmentDragWriteResult.FAILED, snapshot.write(path))
            }
            assertContentEquals(byteArrayOf(9), readFile(existing))
            assertEquals(S_IFLNK, mode(link) and S_IFMT)
        }
    }

    @Test
    fun cancelledRelativeOrExpiredWritesCreateNothing() = runTest {
        withPrepared(SMALL_BYTES) { snapshot, _, _, directory ->
            val cancelled = AttachmentDragReadCancellationSnapshot().apply { cancel() }
            val path = "$directory/attachment.bin"
            val result = withContext(Dispatchers.Default) { snapshot.writeTo(path, cancelled) }
            assertEquals(AttachmentDragWriteResult.CANCELLED, result)
            assertEquals(AttachmentDragWriteResult.FAILED, snapshot.write("attachment.bin"))
            snapshot.close()
            assertEquals(AttachmentDragWriteResult.FAILED, snapshot.write(path))
            assertFalse(SystemFileSystem.exists(Path(path)))
        }
    }

    @Test
    fun retirementRunsEachRegisteredActionOnceAndLateActionsAtOnce() = runTest {
        withPrepared(SMALL_BYTES) { snapshot, _, _, _ ->
            var retired = 0
            var unsubscribed = 0
            snapshot.invokeOnRetirement { retired += 1 }
            snapshot.invokeOnRetirement { unsubscribed += 1 }.cancel()
            snapshot.close()
            snapshot.close()
            assertEquals(1, retired)
            assertEquals(0, unsubscribed)
            snapshot.invokeOnRetirement { retired += 1 }
            assertEquals(2, retired)
        }
    }

    @Test
    fun lockingTheVaultRetiresTheOfferAndRejectsLaterWrites() = runTest {
        withPrepared(SMALL_BYTES) { snapshot, _, closeVault, directory ->
            var retired = 0
            snapshot.invokeOnRetirement { retired += 1 }
            closeVault()
            assertEquals(1, retired)
            assertFalse(snapshot.isAvailable())
            val path = "$directory/attachment.bin"
            assertEquals(AttachmentDragWriteResult.FAILED, snapshot.write(path))
            assertTrue(snapshot.loadData(AttachmentDragReadCancellationSnapshot()).failed)
            assertFalse(SystemFileSystem.exists(Path(path)))
        }
    }

    @Test
    fun anUncommittedDestinationRemovesOnlyItsOwnFile() = runTest {
        withTestAppleStorage { directories ->
            val directory = directories.root.value
            val partial = "$directory/partial"
            PromiseDestination.create(partial).use { it.write(byteArrayOf(1, 2), 2) }
            assertFalse(SystemFileSystem.exists(Path(partial)))

            val short = "$directory/short"
            PromiseDestination.create(short).use { destination ->
                destination.write(byteArrayOf(1), 1)
                assertFailsWith<IllegalStateException> { destination.commit(2) }
            }
            assertFalse(SystemFileSystem.exists(Path(short)))

            val replaced = "$directory/replaced"
            PromiseDestination.create(replaced).use { destination ->
                destination.write(byteArrayOf(1), 1)
                SystemFileSystem.delete(Path(replaced))
                writeFile(replaced, byteArrayOf(9))
            }
            assertContentEquals(byteArrayOf(9), readFile(replaced))
        }
    }

    private suspend fun AttachmentDragContentSnapshot.write(path: String) = withContext(Dispatchers.Default) {
        writeTo(path, AttachmentDragReadCancellationSnapshot())
    }

    private fun readFile(path: String): ByteArray =
        SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

    private fun writeFile(path: String, bytes: ByteArray) =
        SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }

    private fun mode(path: String): Int = memScoped {
        val info = alloc<stat>()
        check(lstat(path, info.ptr) == 0)
        info.st_mode.toInt()
    }

    private suspend fun withPrepared(
        size: Int,
        block: suspend (
            AttachmentDragContentSnapshot,
            ByteArray,
            closeVault: () -> Unit,
            directory: String,
        ) -> Unit,
    ) = withPreparation(size) { prepare, request, expected, closeVault, directory ->
        val route = AttachmentDragPreparer(prepare)
        val content = route(request) {}.representations<DragRepresentation.File>().single().content
        val snapshot = AttachmentDragContentSnapshot(content)
        try {
            snapshot.handoff()
            block(snapshot, expected, closeVault, directory)
        } finally {
            snapshot.close()
        }
    }

    private suspend fun withPreparation(
        size: Int,
        block: suspend (
            PrepareAttachmentContent,
            PrepareAttachmentContent.Request,
            ByteArray,
            () -> Unit,
            String,
        ) -> Unit,
    ) = withTestAppleStorage { directories ->
        val bytes = ByteArray(size) { ((it * 31 + 7) and 255).toByte() }
        val uri = assertNotNull(NSURL.fileURLWithPath("${directories.cache.value}/input.bin").absoluteString)
        withContext(Dispatchers.Default) {
            FileServiceImpl().writeToFile(uri).use { it.write(bytes) }
        }
        // Stands in for the folder a recipient chose.
        val destination = Path(directories.root.value, "destination")
        SystemFileSystem.createDirectories(destination)
        directories.withKoin { koin ->
            val masterKey = MasterKey(MasterKdfVersion.LATEST, ByteArray(MASTER_KEY_BYTES))
            koin.withVaultSession(masterKey) { session ->
                val prepare = assertNotNull(session.resolve { get<PrepareAttachmentContent>() })
                // The public use case copies this trusted local fixture into independent storage.
                val request = PrepareAttachmentContent.Request(
                    DownloadAttachmentRequest.ByLocalCipherAttachment("fixture", "fixture", "fixture"),
                    "probe.bin",
                    uri,
                )
                // Prepare uses the captured service; the backing store belongs to the application.
                block(prepare, request, bytes, session::close, destination.toString())
                assertFalse(SystemFileSystem.exists(Path(directories.cache.value, "attachment-drag")))
            }
        }
    }

    private companion object {
        const val SMALL_BYTES = 1024
        const val MAXIMUM_BYTES = 2 * 1024 * 1024
        const val MASTER_KEY_BYTES = 32
        const val PERMISSION_BITS = 0x1FF
        const val OWNER_READ_WRITE = 0x180
    }
}
