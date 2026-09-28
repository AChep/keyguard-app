package com.artemchep.keyguard.apple.gpgagent

import platform.posix.SHUT_WR
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.AF_UNIX
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.accept
import platform.posix.bind
import platform.posix.chmod
import platform.posix.close
import platform.posix.connect
import platform.posix.fcntl
import platform.posix.getpid
import platform.posix.listen
import platform.posix.mkdir
import platform.posix.read
import platform.posix.shutdown
import platform.posix.socket
import platform.posix.socketpair
import platform.posix.symlink
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalForeignApi::class)
class GpgAgentRuntimeMacosTest {
    @Test
    fun socketPathsUseEncodedDarwinLimit() {
        requireSocketPath("/" + "a".repeat(102))
        assertFailsWith<IllegalArgumentException> { requireSocketPath("/" + "a".repeat(103)) }
        assertFailsWith<IllegalArgumentException> { requireSocketPath("/" + "é".repeat(52)) }
        assertFailsWith<IllegalArgumentException> { requireSocketPath("relative/socket") }
        assertFailsWith<IllegalArgumentException> { requireSocketPath("/path\u0000/socket") }
        val encoded = socketAddress("/tmp/gpg")
        assertEquals(encoded.size, encoded[0].toInt())
        assertEquals(AF_UNIX, encoded[1].toInt())
        assertEquals(0, encoded.last().toInt())
    }

    @Test
    fun privateDirectoryRefusesSymlinksFilesAndUnsafePermissions() = inTemporaryDirectory { root ->
        ensurePrivateDirectory("$root/valid")
        ensurePrivateDirectory("$root/valid")
        assertEquals(0, symlink("$root/valid", "$root/link"))
        assertFailsWith<IllegalStateException> { ensurePrivateDirectory("$root/link") }
        writeText("$root/file", "keep")
        assertFailsWith<IllegalStateException> { ensurePrivateDirectory("$root/file") }
        assertEquals(0, mkdir("$root/shared", 0x1c0u))
        assertEquals(0, chmod("$root/shared", 0x1ffu))
        assertFailsWith<IllegalStateException> { ensurePrivateDirectory("$root/shared") }
    }

    @Test
    fun kernelPeerMustMatchLiveExpectedProcess() = inTemporaryDirectory { root ->
        val listener = socket(AF_UNIX, SOCK_STREAM, 0)
        val client = socket(AF_UNIX, SOCK_STREAM, 0)
        assertTrue(listener >= 0 && client >= 0)
        try {
            val address = socketAddress("$root/s")
            assertEquals(0, address.usePinned { bind(listener, it.addressOf(0).reinterpret(), address.size.convert()) })
            assertEquals(0, listen(listener, 1))
            assertEquals(
                0,
                address.usePinned { connect(client, it.addressOf(0).reinterpret(), address.size.convert()) },
            )
            val accepted = accept(listener, null, null)
            assertTrue(accepted >= 0)
            try {
                assertTrue(verifiedChildPeer(accepted, getpid()))
                assertFalse(verifiedChildPeer(accepted, null))
                assertFalse(verifiedChildPeer(accepted, -1))
                assertFalse(verifiedChildPeer(accepted, getpid() + 1))
            } finally {
                close(accepted)
            }
        } finally {
            close(client)
            close(listener)
        }
    }

    @Test
    fun framingHandlesFragmentationTruncationAndOversizedPackets() = runBlocking {
        socketPair { reader, writer ->
            val expected = byteArrayOf(1, 2, 3)
            val writing = launch {
                for (byte in byteArrayOf(0, 0, 0, 3, 1, 2, 3)) {
                    sendBytes(writer, byteArrayOf(byte))
                    delay(2)
                }
            }
            assertContentEquals(expected, withTimeout(2_000) { readPacket(reader) })
            writing.join()
            sendBytes(writer, byteArrayOf(1, 0, 0, 1)) // 16 MiB + 1
            assertNull(readPacket(reader))
            sendBytes(writer, byteArrayOf(0, 0, 0, 0))
            assertNull(readPacket(reader))
            sendBytes(writer, byteArrayOf(0, 0, 0, 3, 1))
            shutdown(writer, SHUT_WR)
            assertNull(readPacket(reader))
        }
    }

    @Test
    fun cancellationInterruptsAnIdleFrameRead() = runBlocking {
        socketPair { reader, _ ->
            val read = async { readPacket(reader) }
            delay(30)
            withTimeout(1_000) { read.cancelAndJoin() }
        }
    }

    @Test
    fun cancellationBeforeConnectionDispatchClosesTheAcceptedDescriptor() = inTemporaryDirectory { root ->
        runBlocking {
            val dispatcher = QueuedDispatcher()
            val server = MacosGpgIpcServer("$root/i", config(), ByteArray(32)) { getpid() }
            val client = socket(AF_UNIX, SOCK_STREAM, 0)
            assertTrue(client >= 0)
            var serving: Job? = null
            try {
                val address = socketAddress(server.path)
                assertEquals(
                    0,
                    address.usePinned { connect(client, it.addressOf(0).reinterpret(), address.size.convert()) },
                )
                assertEquals(0, fcntl(client, F_SETFL, O_NONBLOCK))
                serving = launch(dispatcher) { server.serve() }
                // Run the accept loop once, then cancel before any dispatched
                // connection coroutine can begin. Its descriptor must still close.
                dispatcher.runNext()
                serving.cancel()
                dispatcher.runAll()
                assertTrue(serving.isCompleted)
                val buffer = ByteArray(1)
                assertEquals(0L, buffer.usePinned { read(client, it.addressOf(0), 1u) })
            } finally {
                serving?.cancel()
                dispatcher.runAll()
                serving?.join()
                close(client)
                server.close()
            }
        }
    }

    @Test
    fun launchFailureAndCancellationRemoveOnlyPrivateIpc() = inTemporaryDirectory { root ->
        runBlocking {
            ensurePrivateDirectory("$root/gpg")
            writeText("$root/gpg/s", "unrelated socket-path occupant")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                assertFailsWith<IllegalStateException> {
                    MacosGpgAgentRuntime({ "/usr/bin/false" }, { root }).start(scope, config())
                }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
            val children = NSFileManager.defaultManager.contentsOfDirectoryAtPath("$root/gpg", null)
            assertEquals(setOf("s", "locks"), children?.toSet())
        }
    }

    @Test
    fun readinessStopAndRestartHaveCompleteCleanup() = inTemporaryDirectory { root ->
        runBlocking {
            val binary = "$root/helper"
            // Emulates the lifecycle contract, without cryptography or a vault.
            writeText(
                binary,
                "#!/bin/sh\nIFS= read -r token\n" +
                    "printf 'KEYGUARD_AGENT_READY 1\\n'\nwhile IFS= read -r line; do :; done\n",
            )
            assertEquals(0, chmod(binary, 0x1c0u))
            val runtime = MacosGpgAgentRuntime({ binary }, { root })
            repeat(2) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val handle = withTimeout(5_000) { runtime.start(scope, config()) }
                    assertTrue(handle.isRunning)
                    assertEquals("$root/gpg/s", handle.agentSocket)
                    assertEquals("~/.keyguard/gnupg", handle.gpgHome)
                    handle.stop()
                } finally {
                    withTimeout(5_000) { scope.coroutineContext.job.cancelAndJoin() }
                }
                assertEquals(listOf("locks"), NSFileManager.defaultManager.contentsOfDirectoryAtPath("$root/gpg", null))
            }
        }
    }

    private fun config() = GpgAgentRuntimeConfig(
        authToken = ByteArray(32) { it.toByte() },
        processor = object : GpgAgentRequestProcessor {
            override suspend fun listKeys(
                caller: GpgAgentMessages.CallerIdentity?,
            ) = GpgAgentRequestProcessor.ListKeysResult.VaultLocked
            override suspend fun signHash(
                request: GpgAgentMessages.SignHashRequest,
            ) = GpgAgentRequestProcessor.GpgAgentOperationResult.VaultLocked
            override suspend fun decrypt(
                request: GpgAgentMessages.PkdecryptRequest,
            ) = GpgAgentRequestProcessor.GpgAgentOperationResult.VaultLocked
        },
        onTerminated = {},
        log = {},
    )

    private fun inTemporaryDirectory(block: (String) -> Unit) {
        val path = "/private/tmp/kg-${NSUUID().UUIDString.take(8)}"
        assertEquals(0, mkdir(path, 0x1c0u))
        try { block(path) } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    private fun writeText(path: String, text: String) {
        assertTrue(NSString.create(string = text).writeToFile(path, true, NSUTF8StringEncoding, null))
    }

    private suspend fun socketPair(block: suspend (Int, Int) -> Unit) {
        val pair = IntArray(2)
        assertEquals(0, pair.usePinned { socketpair(AF_UNIX, SOCK_STREAM, 0, it.addressOf(0)) })
        try {
            assertEquals(0, fcntl(pair[0], F_SETFL, O_NONBLOCK))
            block(pair[0], pair[1])
        } finally {
            close(pair[0])
            close(pair[1])
        }
    }

    private fun sendBytes(fd: Int, bytes: ByteArray) {
        assertEquals(bytes.size.toLong(), bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) })
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = Channel<Runnable>(Channel.UNLIMITED)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            check(queue.trySend(block).isSuccess)
        }

        fun runNext() { queue.tryReceive().getOrThrow().run() }

        fun runAll() {
            while (true) (queue.tryReceive().getOrNull() ?: return).run()
        }
    }
}
