package com.artemchep.keyguard.apple.gpgtools

import com.artemchep.keyguard.common.service.file.FileServiceImpl
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class)
class GpgToolsFilesTest {
    private val fileService = FileServiceImpl()

    @Test
    fun publishesBinaryAndEmptyOutputsWithAccurateMetadata() = runTest {
        withFiles { files ->
            val binary = ByteArray(1024) { it.toByte() }
            for (bytes in listOf(binary, byteArrayOf())) {
                val result = files.write("output.bin", incognito = true) { uri ->
                    fileService.writeToFile(uri).use { it.write(bytes) }
                }
                val file = assertNotNull(files.get(result.id))
                assertEquals("output.bin", result.name)
                assertEquals(bytes.size.toLong(), result.size)
                assertTrue(result.incognito)
                assertContentEquals(bytes, fileService.readFromFile(file.uri).use { it.readByteArray() })
            }
        }
    }

    @Test
    fun failedWriteRemovesPartialFileAndDoesNotPublishResult() = runTest {
        withFiles { files ->
            var partialUri: String? = null
            val failure = IllegalStateException("writer failed")
            val caught = assertFailsWith<IllegalStateException> {
                files.write("partial.bin", incognito = false) { uri ->
                    partialUri = uri
                    fileService.writeToFile(uri).use { it.write(byteArrayOf(1, 2, 3)) }
                    throw failure
                }
            }
            assertSame(failure, caught)
            assertFalse(fileService.exists(assertNotNull(partialUri)))
            val retry = files.write("retry.bin", incognito = false) {}
            assertNotNull(files.get(retry.id))
            assertEquals(0L, retry.size)
        }
    }

    @Test
    fun cancelledWriteRemovesPartialOutput() = runTest {
        withFiles { files ->
            val started = CompletableDeferred<String>()
            val writer = launch {
                files.write("partial.bin", incognito = true) { uri ->
                    fileService.writeToFile(uri).use { it.write(byteArrayOf(42)) }
                    started.complete(uri)
                    awaitCancellation()
                }
                error("Cancelled output must not be published")
            }
            val uri = started.await()
            assertTrue(fileService.exists(uri))
            writer.cancelAndJoin()
            assertTrue(writer.isCancelled)
            assertFalse(fileService.exists(uri))
        }
    }

    @Test
    fun retainedExportSurvivesCloseUntilItsLeaseIsReleased() = runTest {
        withFiles { files ->
            val result = files.write("export.bin", incognito = true) { uri ->
                fileService.writeToFile(uri).use { it.write(byteArrayOf(9, 8, 7)) }
            }
            val file = assertNotNull(files.get(result.id))
            files.retain()
            try {
                files.close()
                assertNull(files.get(result.id))
                assertTrue(fileService.exists(file.uri))
                assertContentEquals(
                    byteArrayOf(9, 8, 7),
                    fileService.readFromFile(file.uri).use { it.readByteArray() },
                )
                assertFailsWith<IllegalStateException> { files.retain() }
            } finally {
                files.release()
            }
            assertFalse(fileService.exists(file.uri))
        }
    }

    @Test
    fun discardingInputWaitsForRunningOperationToReleaseIt() = runTest {
        withFiles { files ->
            val input = files.create("input.bin")
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                files.run {
                    started.complete(Unit)
                    finish.await()
                    assertTrue(fileService.exists(input.uri))
                }
            }
            try {
                // run() must acquire its lease before launch returns, even before
                // its Default-dispatched body begins reading the selected file.
                files.discard(input.id)
                assertNull(files.get(input.id))
                assertTrue(fileService.exists(input.uri))
                started.await()
                finish.complete(Unit)
                operation.join()
                assertFalse(fileService.exists(input.uri))
            } finally {
                operation.cancelAndJoin()
            }
        }
    }

    @Test
    fun untrustedNamesRemainInsideOwnedStorage() = runTest {
        withFiles { files ->
            val file = files.create("../../outside\\payload.bin")
            assertEquals("payload.bin", file.name)
            assertEquals(file.name, NSURL.fileURLWithPath(file.path).lastPathComponent)
            assertTrue(fileService.exists(file.uri))
            val fallback = files.create("..")
            assertEquals("gpg-output", fallback.name)
        }
    }

    private suspend fun withFiles(block: suspend (GpgToolsFiles) -> Unit) {
        val root = NSTemporaryDirectory().trimEnd('/') + "/keyguard-gpg-test-${Uuid.random()}"
        val files = GpgToolsFiles(fileService, rootDirectory = root)
        try {
            block(files)
        } finally {
            files.close()
            // Never initialize or clean the production GPG staging root.
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }
}
