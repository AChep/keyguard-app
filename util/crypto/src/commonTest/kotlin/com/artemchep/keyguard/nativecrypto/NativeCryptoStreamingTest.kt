package com.artemchep.keyguard.nativecrypto

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeCryptoStreamingTest {
    @Test
    fun feedsArbitraryChunkBoundariesAndStagesOutput() {
        val returnedBuffers = mutableListOf<ByteArray>()
        val updateLengths = mutableListOf<Int>()
        val session = FakeSession(
            onUpdate = { data, offset, length ->
                updateLengths += length
                data.copyOfRange(offset, offset + length).also(returnedBuffers::add)
            },
            onFinish = {
                byteArrayOf(99).also(returnedBuffers::add)
            },
        )

        val actual = collectNativeStream(
            session = session,
            input = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            chunkSize = 3,
        )

        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 99), actual)
        assertEquals(listOf(3, 3, 2), updateLengths)
        assertEquals(1, session.finishCalls)
        assertEquals(1, session.closeCalls)
        assertTrue(returnedBuffers.all { buffer -> buffer.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun clearsStagedOutputWhenAuthenticationFinalizationFails() {
        val returnedBuffers = mutableListOf<ByteArray>()
        val session = FakeSession(
            onUpdate = { _, _, _ ->
                byteArrayOf(7, 8, 9).also(returnedBuffers::add)
            },
            onFinish = {
                throw NativeCryptoException(
                    operation = "stream.finish",
                    code = NativeCryptoErrorCode.AUTHENTICATION_FAILED,
                )
            },
        )

        val exception = assertFailsWith<NativeCryptoException> {
            collectNativeStream(session, ByteArray(4), chunkSize = 2)
        }

        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, exception.code)
        assertEquals(1, session.finishCalls)
        assertEquals(1, session.closeCalls)
        assertTrue(returnedBuffers.all { buffer -> buffer.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun preservesPrimaryFailureWhenCleanupAlsoFails() {
        val session = FakeSession(
            onUpdate = { _, _, _ ->
                throw NativeCryptoException(
                    operation = "stream.update",
                    code = NativeCryptoErrorCode.INTERNAL,
                )
            },
            onClose = {
                throw NativeCryptoException(
                    operation = "stream.close",
                    code = NativeCryptoErrorCode.INVALID_SESSION,
                )
            },
        )

        val exception = assertFailsWith<NativeCryptoException> {
            collectNativeStream(session, ByteArray(1))
        }

        assertEquals(NativeCryptoErrorCode.INTERNAL, exception.code)
        assertEquals(1, exception.suppressedExceptions.size)
        assertEquals(
            NativeCryptoErrorCode.INVALID_SESSION,
            (exception.suppressedExceptions.single() as NativeCryptoException).code,
        )
    }

    @Test
    fun cancellationClosesSessionWithoutFinalizing() {
        val cancellation = CancellationException("cancelled")
        val session = FakeSession(
            onUpdate = { _, _, _ -> throw cancellation },
        )

        val actual = assertFailsWith<CancellationException> {
            collectNativeStream(session, ByteArray(1))
        }

        assertSame(cancellation, actual)
        assertEquals(0, session.finishCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun collectsKnownSizeWithoutRetainingNativeChunks() {
        val returnedBuffers = mutableListOf<ByteArray>()
        val session = FakeSession(
            onUpdate = { data, offset, length ->
                data.copyOfRange(offset, offset + length).also(returnedBuffers::add)
            },
            onFinish = {
                byteArrayOf(9, 9).also(returnedBuffers::add)
            },
        )

        val actual = collectNativeStreamToExpectedSize(
            session = session,
            input = byteArrayOf(1, 2, 3, 4),
            expectedOutputSize = 6,
            operation = "test.stream",
            chunkSize = 3,
        )

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 9, 9), actual)
        assertEquals(1, session.finishCalls)
        assertEquals(1, session.closeCalls)
        assertTrue(returnedBuffers.all { buffer -> buffer.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun rejectsAndClearsUnexpectedFixedSizeOutputs() {
        val oneShotOutput = byteArrayOf(1, 2, 3)
        val oneShotFailure = assertFailsWith<NativeCryptoException> {
            oneShotOutput.requireNativeCryptoOutputSize(
                operation = "test.one_shot",
                expectedSize = 4,
            )
        }
        assertEquals("test.one_shot", oneShotFailure.operation)
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, oneShotFailure.code)
        assertTrue(oneShotOutput.all { byte -> byte == 0.toByte() })

        val finalOutput = byteArrayOf(4, 5, 6)
        val delegate = FakeSession(onFinish = { finalOutput })
        val session = delegate.withExpectedFinalOutputSize(
            operation = "test.stream_finish",
            expectedSize = 4,
        )
        val streamFailure = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals("test.stream_finish", streamFailure.operation)
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, streamFailure.code)
        assertEquals(1, delegate.finishCalls)
        assertTrue(finalOutput.all { byte -> byte == 0.toByte() })
    }

    @Test
    fun rejectsAndClearsMalformedCbcOutputShapes() {
        val encryptedOutput = ByteArray(16) { 1 }
        val encryptionFailure = assertFailsWith<NativeCryptoException> {
            encryptedOutput.requireNativeCryptoCbcOutputShape(
                operation = "test.cbc.encrypt",
                direction = CipherDirectionProto.ENCRYPT,
                inputSize = 16,
                blockSize = 16,
            )
        }
        assertEquals("test.cbc.encrypt", encryptionFailure.operation)
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, encryptionFailure.code)
        assertTrue(encryptedOutput.all { byte -> byte == 0.toByte() })

        val decryptedOutput = ByteArray(16) { 2 }
        val decryptionFailure = assertFailsWith<NativeCryptoException> {
            decryptedOutput.requireNativeCryptoCbcOutputShape(
                operation = "test.cbc.decrypt",
                direction = CipherDirectionProto.DECRYPT,
                inputSize = 16,
                blockSize = 16,
            )
        }
        assertEquals("test.cbc.decrypt", decryptionFailure.operation)
        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, decryptionFailure.code)
        assertTrue(decryptedOutput.all { byte -> byte == 0.toByte() })
    }

    private class FakeSession(
        private val onUpdate: (ByteArray, Int, Int) -> ByteArray = { _, _, _ -> ByteArray(0) },
        private val onFinish: () -> ByteArray = { ByteArray(0) },
        private val onClose: () -> Unit = {},
    ) : NativeCryptoSession {
        var finishCalls: Int = 0
        var closeCalls: Int = 0

        override fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ): ByteArray = onUpdate(data, offset, length)

        override fun finish(): ByteArray {
            finishCalls += 1
            return onFinish()
        }

        override fun close() {
            closeCalls += 1
            onClose()
        }
    }
}
