package com.artemchep.keyguard.common.service.sshagent

import java.io.Closeable
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SshAgentTcpProtocolTest {
    companion object {
        private const val FRAME_HEADER_LENGTH = 4 + 1 + 1 + 8 + 4
        private const val AEAD_TAG_LENGTH = 16
        private const val TOOL_HELLO_FRAME_LENGTH =
            FRAME_HEADER_LENGTH +
                SshAgentTcpProtocol.SESSION_ID_LENGTH +
                SshAgentTcpProtocol.CHALLENGE_LENGTH +
                AEAD_TAG_LENGTH
        private const val FIRST_PACKET_HEADER_OFFSET = TOOL_HELLO_FRAME_LENGTH
    }

    @Test
    fun `tool and app exchange packets after secure handshake`() {
        runBlocking {
            withTimeout(5_000) {
                val streams = createDuplexStreams()
                try {
                    val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                    val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }

                    val appJob = async(Dispatchers.IO) {
                        val appChannel = SshAgentTcpProtocol.openAsApp(
                            input = streams.appInput,
                            output = streams.appOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        val packet = requireNotNull(appChannel.readPacket())
                        assertContentEquals("hello".encodeToByteArray(), packet)
                        appChannel.writePacket("world".encodeToByteArray())
                    }

                    val toolJob = async(Dispatchers.IO) {
                        val toolChannel = SshAgentTcpProtocol.openAsTool(
                            input = streams.toolInput,
                            output = streams.toolOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        toolChannel.writePacket("hello".encodeToByteArray())
                        requireNotNull(toolChannel.readPacket())
                    }

                    val response = toolJob.await()
                    appJob.await()
                    assertContentEquals("world".encodeToByteArray(), response)
                } finally {
                    closeQuietly(streams.toolInput, streams.toolOutput, streams.appInput, streams.appOutput)
                }
            }
        }
    }

    @Test
    fun `app handshake rejects mismatched session secret`() {
        runBlocking {
            withTimeout(5_000) {
                val streams = createDuplexStreams()
                try {
                    supervisorScope {
                        val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                        val toolSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }
                        val appSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x33 }

                        val appJob = async(Dispatchers.IO) {
                            assertFailsWith<IllegalArgumentException> {
                                SshAgentTcpProtocol.openAsApp(
                                    input = streams.appInput,
                                    output = streams.appOutput,
                                    sessionId = sessionId,
                                    sessionSecret = appSecret,
                                )
                            }
                        }

                        val toolJob = async(Dispatchers.IO) {
                            SshAgentTcpProtocol.openAsTool(
                                input = streams.toolInput,
                                output = streams.toolOutput,
                                sessionId = sessionId,
                                sessionSecret = toolSecret,
                            )
                        }

                        appJob.await()
                        closeQuietly(streams.appOutput, streams.appInput)
                        assertFailsWith<EOFException> {
                            toolJob.await()
                        }
                    }
                } finally {
                    closeQuietly(streams.toolInput, streams.toolOutput, streams.appInput, streams.appOutput)
                }
            }
        }
    }

    @Test
    fun `app rejects tampered packet payload`() {
        runBlocking {
            withTimeout(5_000) {
                val baseStreams = createDuplexStreams()
                val streams = DuplexStreams(
                    toolInput = baseStreams.toolInput,
                    toolOutput = TamperingOutputStream(baseStreams.toolOutput),
                    appInput = baseStreams.appInput,
                    appOutput = baseStreams.appOutput,
                )
                try {
                    val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                    val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }

                    val appJob = async(Dispatchers.IO) {
                        val appChannel = SshAgentTcpProtocol.openAsApp(
                            input = streams.appInput,
                            output = streams.appOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        assertFailsWith<IllegalArgumentException> {
                            appChannel.readPacket()
                        }
                    }

                    val toolJob = async(Dispatchers.IO) {
                        val toolChannel = SshAgentTcpProtocol.openAsTool(
                            input = streams.toolInput,
                            output = streams.toolOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        toolChannel.writePacket("tamper-me".encodeToByteArray())
                    }

                    toolJob.await()
                    appJob.await()
                } finally {
                    closeQuietly(streams.toolInput, streams.toolOutput, streams.appInput, streams.appOutput)
                }
            }
        }
    }

    @Test
    fun `app rejects packet with wrong version`() {
        runBlocking {
            withTimeout(5_000) {
                val baseStreams = createDuplexStreams()
                val streams = DuplexStreams(
                    toolInput = baseStreams.toolInput,
                    toolOutput = ByteMutatingOutputStream(
                        baseStreams.toolOutput,
                        mapOf(FIRST_PACKET_HEADER_OFFSET + 4 to 3.toByte()),
                    ),
                    appInput = baseStreams.appInput,
                    appOutput = baseStreams.appOutput,
                )
                try {
                    val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                    val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }

                    val appJob = async(Dispatchers.IO) {
                        val appChannel = SshAgentTcpProtocol.openAsApp(
                            input = streams.appInput,
                            output = streams.appOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        val error = assertFailsWith<IllegalArgumentException> {
                            appChannel.readPacket()
                        }
                        assertEquals("Unsupported SSH agent version=3", error.message)
                    }

                    val toolJob = async(Dispatchers.IO) {
                        val toolChannel = SshAgentTcpProtocol.openAsTool(
                            input = streams.toolInput,
                            output = streams.toolOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        toolChannel.writePacket("hello".encodeToByteArray())
                    }

                    toolJob.await()
                    appJob.await()
                } finally {
                    closeQuietly(streams.toolInput, streams.toolOutput, streams.appInput, streams.appOutput)
                }
            }
        }
    }

    @Test
    fun `app rejects packet with wrong frame type`() {
        runBlocking {
            withTimeout(5_000) {
                val baseStreams = createDuplexStreams()
                val streams = DuplexStreams(
                    toolInput = baseStreams.toolInput,
                    toolOutput = ByteMutatingOutputStream(
                        baseStreams.toolOutput,
                        mapOf(
                            FIRST_PACKET_HEADER_OFFSET + 5 to
                                SshAgentTcpProtocol.FRAME_TYPE_APP_HELLO.toByte(),
                        ),
                    ),
                    appInput = baseStreams.appInput,
                    appOutput = baseStreams.appOutput,
                )
                try {
                    val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                    val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }

                    val appJob = async(Dispatchers.IO) {
                        val appChannel = SshAgentTcpProtocol.openAsApp(
                            input = streams.appInput,
                            output = streams.appOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        val error = assertFailsWith<IllegalArgumentException> {
                            appChannel.readPacket()
                        }
                        assertEquals(
                            "Unexpected SSH agent frame type=2 expected=3",
                            error.message,
                        )
                    }

                    val toolJob = async(Dispatchers.IO) {
                        val toolChannel = SshAgentTcpProtocol.openAsTool(
                            input = streams.toolInput,
                            output = streams.toolOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        toolChannel.writePacket("hello".encodeToByteArray())
                    }

                    toolJob.await()
                    appJob.await()
                } finally {
                    closeQuietly(streams.toolInput, streams.toolOutput, streams.appInput, streams.appOutput)
                }
            }
        }
    }

    @Test
    fun `app rejects packet with wrong counter`() {
        runBlocking {
            withTimeout(5_000) {
                val baseStreams = createDuplexStreams()
                val streams = DuplexStreams(
                    toolInput = baseStreams.toolInput,
                    toolOutput = ByteMutatingOutputStream(
                        baseStreams.toolOutput,
                        mapOf(FIRST_PACKET_HEADER_OFFSET + 13 to 0x02.toByte()),
                    ),
                    appInput = baseStreams.appInput,
                    appOutput = baseStreams.appOutput,
                )
                try {
                    val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                    val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }

                    val appJob = async(Dispatchers.IO) {
                        val appChannel = SshAgentTcpProtocol.openAsApp(
                            input = streams.appInput,
                            output = streams.appOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        val error = assertFailsWith<IllegalArgumentException> {
                            appChannel.readPacket()
                        }
                        assertEquals("Invalid SSH agent counter=2 expected=1", error.message)
                    }

                    val toolJob = async(Dispatchers.IO) {
                        val toolChannel = SshAgentTcpProtocol.openAsTool(
                            input = streams.toolInput,
                            output = streams.toolOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        toolChannel.writePacket("hello".encodeToByteArray())
                    }

                    toolJob.await()
                    appJob.await()
                } finally {
                    closeQuietly(streams.toolInput, streams.toolOutput, streams.appInput, streams.appOutput)
                }
            }
        }
    }

    @Test
    fun `app rejects packet with oversize payload length`() {
        runBlocking {
            withTimeout(5_000) {
                val invalidLength =
                    SshAgentTcpProtocol.MAX_FRAME_PAYLOAD_SIZE + AEAD_TAG_LENGTH + 1
                val baseStreams = createDuplexStreams()
                val streams = DuplexStreams(
                    toolInput = baseStreams.toolInput,
                    toolOutput = ByteMutatingOutputStream(
                        baseStreams.toolOutput,
                        indexedByteMap(
                            FIRST_PACKET_HEADER_OFFSET + 14,
                            invalidLength.toByteArray(),
                        ),
                    ),
                    appInput = baseStreams.appInput,
                    appOutput = baseStreams.appOutput,
                )
                try {
                    val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                    val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }

                    val appJob = async(Dispatchers.IO) {
                        val appChannel = SshAgentTcpProtocol.openAsApp(
                            input = streams.appInput,
                            output = streams.appOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        val error = assertFailsWith<IllegalArgumentException> {
                            appChannel.readPacket()
                        }
                        assertEquals(
                            "Invalid SSH agent payload length=$invalidLength",
                            error.message,
                        )
                    }

                    val toolJob = async(Dispatchers.IO) {
                        val toolChannel = SshAgentTcpProtocol.openAsTool(
                            input = streams.toolInput,
                            output = streams.toolOutput,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        toolChannel.writePacket("hello".encodeToByteArray())
                    }

                    toolJob.await()
                    appJob.await()
                } finally {
                    closeQuietly(streams.toolInput, streams.toolOutput, streams.appInput, streams.appOutput)
                }
            }
        }
    }

    private fun closeQuietly(
        vararg closeables: Closeable,
    ) {
        closeables.forEach { closeable ->
            runCatching { closeable.close() }
        }
    }

    private data class DuplexStreams(
        val toolInput: PipedInputStream,
        val toolOutput: OutputStream,
        val appInput: PipedInputStream,
        val appOutput: PipedOutputStream,
    )

    private fun createDuplexStreams(): DuplexStreams {
        val toolInput = PipedInputStream(64 * 1024)
        val toolOutput = PipedOutputStream()
        val appInput = PipedInputStream(64 * 1024)
        val appOutput = PipedOutputStream()

        toolOutput.connect(appInput)
        appOutput.connect(toolInput)

        return DuplexStreams(
            toolInput = toolInput,
            toolOutput = toolOutput,
            appInput = appInput,
            appOutput = appOutput,
        )
    }

    private class TamperingOutputStream(
        output: OutputStream,
    ) : FilterOutputStream(output) {
        private val tamperStartOffset = FIRST_PACKET_HEADER_OFFSET + FRAME_HEADER_LENGTH
        private var bytesWritten = 0
        private var tampered = false

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            val copy = b.copyOfRange(off, off + len)
            for (index in copy.indices) {
                if (!tampered && bytesWritten >= tamperStartOffset) {
                    copy[index] = (copy[index].toInt() xor 0x01).toByte()
                    tampered = true
                }
                bytesWritten += 1
            }
            out.write(copy, 0, copy.size)
        }
    }

    private class ByteMutatingOutputStream(
        output: OutputStream,
        private val mutations: Map<Int, Byte>,
    ) : FilterOutputStream(output) {
        private var bytesWritten = 0

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            val copy = b.copyOfRange(off, off + len)
            for (index in copy.indices) {
                val offset = bytesWritten
                copy[index] = mutations[offset] ?: copy[index]
                bytesWritten += 1
            }
            out.write(copy, 0, copy.size)
        }
    }

    private fun indexedByteMap(
        startOffset: Int,
        bytes: ByteArray,
    ): Map<Int, Byte> = buildMap {
        bytes.forEachIndexed { index, byte ->
            put(startOffset + index, byte)
        }
    }

    private fun Int.toByteArray(): ByteArray = byteArrayOf(
        (this shr 24).toByte(),
        (this shr 16).toByte(),
        (this shr 8).toByte(),
        this.toByte(),
    )
}
