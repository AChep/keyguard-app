@file:OptIn(ExperimentalForeignApi::class, AgentIpcOwnProcessTestApi::class)

package com.artemchep.keyguard.util.agentipc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTask
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.waitUntilExit
import platform.Foundation.writeToFile
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.geteuid
import platform.posix.getpid
import platform.posix.lstat
import platform.posix.memcpy
import platform.posix.mkdir
import platform.posix.stat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** macOS specifics: the private run directory, the kernel peer and `NSTask` peers. */
class AgentIpcMacosTest {
    @Test
    fun theEndpointIsAPrivateSocketInAPrivateDirectory() {
        val listener = AgentIpcListener.bind(agentIpcTestLocation())
        val socket = listener.endpoint
        val directory = socket.substringBeforeLast('/')
        try {
            assertTrue(Regex("/tmp/kg-ipc-test-[0-9a-f]{12}/i").matches(socket), socket)
            assertEquals(S_IFDIR to PRIVATE_DIRECTORY_MODE, modeOf(directory))
            assertEquals(S_IFSOCK to PRIVATE_SOCKET_MODE, modeOf(socket))
        } finally {
            listener.close()
        }
        // Close removes both, as they are still the ones bind created.
        assertNull(modeOf(socket))
        assertNull(modeOf(directory))
    }

    @Test
    fun theKernelPeerIsThisProcess() = runBlocking {
        AgentIpcListener.bind(agentIpcTestLocation()).use { listener ->
            connectAgentIpcTestClient(listener.endpoint).use {
                withTimeout(5_000) { listener.accept() }.use { connection ->
                    assertEquals(getpid().toLong(), connection.peerPid)
                    assertEquals(currentProcessId(), connection.peerPid)
                }
            }
        }
    }

    @Test
    fun aTaskPeerIsItsIdOnlyWhileItRuns() {
        val task = NSTask()
        task.setExecutableURL(NSURL.fileURLWithPath("/bin/sleep"))
        task.setArguments(listOf("30"))
        assertTrue(task.launchAndReturnError(null))
        try {
            val peer = task.asAgentIpcExpectedPeer()
            assertEquals(task.processIdentifier.toLong(), peer.currentPid())
            task.terminate()
            task.waitUntilExit()
            assertNull(peer.currentPid())
        } finally {
            if (task.isRunning()) task.terminate()
        }
    }

    @Test
    fun aChildTaskIsServedAndThisProcessIsRejected() = inTemporaryDirectory { root ->
        runBlocking {
            writeBytes("$root/request", "ping".encodeToByteArray())
            val events = Channel<AgentIpcEvent>(Channel.UNLIMITED)
            val host = AgentIpcHost(AgentIpcListener.bind(agentIpcTestLocation()), 8) { events.trySend(it) }
            val child = CompletableDeferred<NSTask>()
            val releaseChild = CompletableDeferred<Unit>()
            val serving = launch {
                host.serve({ child.await().asAgentIpcExpectedPeer() }) { connection ->
                    assertEquals("ping", connection.readExactly(4).decodeToString())
                    connection.write("pong".encodeToByteArray())
                    // Netcat exits on socket EOF. Keep it alive for the foreign-PID check.
                    withTimeout(5_000) { releaseChild.await() }
                }
            }
            // `exec` keeps the shell's process ID, so the client is the task itself.
            val task = NSTask()
            task.setExecutableURL(NSURL.fileURLWithPath("/bin/sh"))
            task.setArguments(
                listOf(
                    "-c",
                    "exec /usr/bin/nc -U \"\$1\" < \"\$2\" > \"\$3\"",
                    "sh",
                    host.endpoint,
                    "$root/request",
                    "$root/reply",
                ),
            )
            try {
                assertTrue(task.launchAndReturnError(null))
                child.complete(task)
                withTimeout(5_000) {
                    while (readBytes("$root/reply").size < 4) delay(20)
                }
                assertEquals("pong", readBytes("$root/reply").decodeToString())

                // This process is not the child, which still runs.
                assertTrue(task.isRunning())
                connectAgentIpcTestClient(host.endpoint).use { client ->
                    assertEquals(0, client.readToEnd().size)
                }
                assertEquals(AgentIpcEvent.Listening, withTimeout(5_000) { events.receive() })
                assertEquals(
                    AgentIpcEvent.Rejected(AgentIpcEvent.Reason.PEER_NOT_EXPECTED),
                    withTimeout(5_000) { events.receive() },
                )
            } finally {
                releaseChild.complete(Unit)
                if (task.isRunning()) task.terminate()
                host.stop()
                withTimeout(5_000) { serving.join() }
            }
        }
    }

    @Test
    fun cancellingAnIdleReadReturnsWithinASecond() = runBlocking {
        AgentIpcListener.bind(agentIpcTestLocation()).use { listener ->
            connectAgentIpcTestClient(listener.endpoint).use { client ->
                val connection = withTimeout(5_000) { listener.accept() }
                val reading = launch { connection.read(ByteArray(4)) }
                delay(30)
                withTimeout(1_000) { reading.cancelAndJoin() }
                assertEquals(0, client.readToEnd().size)
            }
        }
    }

    @Test
    fun theOwnProcessPeerIsThisProcess() {
        assertEquals(getpid().toLong(), AgentIpcExpectedPeer.currentProcessForTests().currentPid())
        assertFalse(currentProcessId() <= 0L)
    }

    /** The file type and permission bits of [path], without following a link. */
    private fun modeOf(path: String): Pair<Int, Int>? = memScoped {
        val info = alloc<stat>()
        if (lstat(path, info.ptr) != 0) return@memScoped null
        assertEquals(geteuid(), info.st_uid)
        val mode = info.st_mode.toInt()
        (mode and S_IFMT) to (mode and PERMISSION_MASK)
    }

    private fun inTemporaryDirectory(block: (String) -> Unit) {
        val path = "/private/tmp/kg-${NSUUID().UUIDString.take(8)}"
        assertEquals(0, mkdir(path, 0x1c0u))
        try {
            block(path)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    private fun writeBytes(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        assertTrue(data.writeToFile(path, true))
    }

    private fun readBytes(path: String): ByteArray {
        val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return ByteArray(0)
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }
}

private const val S_IFSOCK = 0xC000

private const val PERMISSION_MASK = 0x1ff

private const val PRIVATE_DIRECTORY_MODE = 0x1c0

private const val PRIVATE_SOCKET_MODE = 0x180
