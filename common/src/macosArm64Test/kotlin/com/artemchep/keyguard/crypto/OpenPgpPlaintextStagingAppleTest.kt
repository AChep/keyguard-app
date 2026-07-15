package com.artemchep.keyguard.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalForeignApi::class)
class OpenPgpPlaintextStagingAppleTest {
    @Test
    fun provisionalPlaintextIsDiscardedWhenFinalizationFails() {
        val output = Buffer()
        val provisional = ByteArray(64 * 1024 + 17) { index ->
            (index % 251).toByte()
        }

        val failure = assertFailsWith<AuthenticationFailure> {
            withStagedOpenPgpPlaintext(output) { staging ->
                staging.write(provisional)
                throw AuthenticationFailure()
            }
        }

        assertEquals("authentication failed", failure.message)
        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun plaintextIsPublishedFromUnlinkedDescriptorAfterSuccessfulFinalization() {
        val output = Buffer()
        val plaintext = ByteArray(2 * 64 * 1024 + 31) { index ->
            (index % 251).toByte()
        }
        val entriesBefore = privateTemporaryStorageEntries()

        val result = withStagedOpenPgpPlaintext(
            output = output,
            memoryLimitBytes = 0L,
        ) { staging ->
            assertEquals(entriesBefore, privateTemporaryStorageEntries())
            staging.write(plaintext)
            "finished"
        }

        assertEquals(entriesBefore, privateTemporaryStorageEntries())
        assertEquals("finished", result)
        assertContentEquals(plaintext, output.readByteArray())
    }

    @Test
    fun oversizedPlaintextIsDiscarded() {
        val output = Buffer()

        assertFailsWith<kotlinx.io.IOException> {
            withStagedOpenPgpPlaintext(
                output = output,
                maxPlaintextBytes = 4L,
            ) { staging ->
                staging.write(byteArrayOf(1, 2, 3, 4, 5))
            }
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun privateTemporaryStorageEnforcesWriteSealReadLifecycle() {
        createPrivateTemporaryStorage().use { storage ->
            val sink = storage.sink()
            assertFailsWith<IllegalStateException> { storage.sink() }
            assertFailsWith<IllegalStateException> { storage.source() }
            assertFailsWith<IllegalStateException> { storage.rewind() }

            val plaintext = byteArrayOf(1, 2, 3)
            sink.write(Buffer().apply { write(plaintext) }, plaintext.size.toLong())
            sink.close()
            storage.sealForReading()

            assertFailsWith<IllegalStateException> { storage.sealForReading() }
            assertFailsWith<IllegalStateException> { storage.sink() }
            assertFailsWith<IllegalStateException> {
                sink.write(Buffer().apply { writeByte(4) }, 1L)
            }

            storage.rewind()
            val actual = storage.source().buffered().use { source ->
                source.readByteArray()
            }
            assertContentEquals(plaintext, actual)
        }
    }

    private fun privateTemporaryStorageEntries(): Set<String> = NSFileManager.defaultManager
        .contentsOfDirectoryAtPath(
            path = NSTemporaryDirectory(),
            error = null,
        )
        .orEmpty()
        .filterIsInstance<String>()
        .filterTo(mutableSetOf()) { name ->
            name.startsWith("keyguard-private-")
        }

    private class AuthenticationFailure : RuntimeException("authentication failed")
}
