package com.artemchep.keyguard.common.io

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaIoExtJvmTest {
    @Test
    fun `useBufferedSink flushes and closes owned output stream`() = runBlocking {
        val outputStream = RecordingOutputStream()

        outputStream.useBufferedSink { sink ->
            sink.write("payload".encodeToByteArray())
        }

        assertContentEquals("payload".encodeToByteArray(), outputStream.toByteArray())
        assertTrue(outputStream.closed)
    }

    @Test
    fun `withBufferedSink flushes without closing borrowed output stream`() = runBlocking {
        val outputStream = RecordingOutputStream()

        outputStream.withBufferedSink { sink ->
            sink.write("payload".encodeToByteArray())
        }

        assertContentEquals("payload".encodeToByteArray(), outputStream.toByteArray())
        assertFalse(outputStream.closed)
    }
}

private class RecordingOutputStream : ByteArrayOutputStream() {
    var closed = false
        private set

    override fun close() {
        closed = true
        super.close()
    }
}
