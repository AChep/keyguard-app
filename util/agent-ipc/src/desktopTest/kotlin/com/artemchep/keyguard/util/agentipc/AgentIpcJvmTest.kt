package com.artemchep.keyguard.util.agentipc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * JVM scenarios with JDK clients: a `SocketChannel` on Linux and macOS, a
 * `RandomAccessFile` on Windows. Ported from the transport tests of `:common`
 * (`WindowsNamedPipeLifecycleTest`, `AgentIpcDisconnectObserverTest`,
 * `AgentIpcPeerVerificationTest`), and run on every OS.
 */
class AgentIpcJvmTest {
    @Test
    fun closeIsIdempotentAndReleasesTheEndpoint() = runBlocking {
        val name = agentIpcTestName()
        val listener = AgentIpcListener.bind(agentIpcTestLocation(name))
        if (isWindows) {
            assertEquals("\\\\.\\pipe\\$name", listener.endpoint)
        } else {
            val socket = Paths.get(listener.endpoint)
            assertEquals(Paths.get("/tmp", name, "i"), socket)
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.parent)))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)))
        }
        listener.close()
        listener.close()
        val error = assertFailsWith<AgentIpcException> { listener.accept() }
        assertEquals(AgentIpcFailureKind.CLOSED, error.kind)
        assertNull(tryConnectAgentIpcTestClient(listener.endpoint))
        if (!isWindows) assertFalse(Files.exists(Paths.get(listener.endpoint).parent))
        // The name is free again.
        AgentIpcListener.bind(agentIpcTestLocation(name)).close()
    }

    @Test
    fun randomNamesHaveTheExpectedShape() {
        AgentIpcListener.bind(agentIpcTestLocation()).use { listener ->
            val pattern = if (isWindows) {
                Regex("""\\\\\.\\pipe\\kg-ipc-test-[0-9a-f]{32}""")
            } else {
                Regex("""/tmp/kg-ipc-test-[0-9a-f]{12}/i""")
            }
            assertTrue(pattern.matches(listener.endpoint), listener.endpoint)
        }
    }

    @Test
    fun theEndpointIsImmediatelyConnectableAndAccepted() = runBlocking {
        AgentIpcListener.bind(agentIpcTestLocation()).use { listener ->
            // One attempt, before any accept runs.
            assertNotNull(tryConnectAgentIpcTestClient(listener.endpoint)).use {
                withTimeout(5_000) { listener.accept() }.use { connection ->
                    assertEquals(ProcessHandle.current().pid(), connection.peerPid)
                }
            }
        }
    }

    @Test
    fun observerAllowsResponsesAndDetectsClientClosure() = withJvmConnection { connection, client ->
        val observer = async { connection.awaitInputOrDisconnect() }
        delay(100)
        assertFalse(observer.isCompleted)

        val response = byteArrayOf(1, 2, 3)
        withTimeout(5_000) { connection.write(response) }
        assertContentEquals(response, client.readExactly(response.size))
        assertFalse(observer.isCompleted)

        client.close()
        withTimeout(5_000) { observer.await() }
    }

    /** D-04: a pending observer never blocks a write on the same connection. */
    @Test
    fun writeCompletesWhileObserverIsPending() = withJvmConnection { connection, client ->
        val observer = async { connection.awaitInputOrDisconnect() }
        delay(100)
        assertFalse(observer.isCompleted)

        val payload = pattern(1 shl 20)
        val received = async { client.readExactly(payload.size) }
        withTimeout(5_000) { connection.write(payload) }
        assertContentEquals(payload, received.await())
        assertFalse(observer.isCompleted)

        // The client writes only once its read is done: a pipe client is synchronous.
        client.write(byteArrayOf(33))
        withTimeout(5_000) { observer.await() }
        assertContentEquals(byteArrayOf(33), withTimeout(5_000) { connection.readExactly(1) })
    }

    @Test
    fun observerTreatsALocallyClosedConnectionAsDisconnected() = withJvmConnection { connection, _ ->
        val observer = async { connection.awaitInputOrDisconnect() }
        delay(100)
        assertFalse(observer.isCompleted)

        // The session closes its end right after the response, while the observer
        // still waits. That must end the observer quietly.
        connection.close()
        withTimeout(5_000) { observer.await() }
        withTimeout(5_000) { connection.awaitInputOrDisconnect() }
    }

    @Test
    fun observerDetectsPartialUnexpectedInputWithoutConsumingIt() = withJvmConnection { connection, client ->
        client.write(byteArrayOf(42))
        withTimeout(5_000) { connection.awaitInputOrDisconnect() }
        assertContentEquals(byteArrayOf(42), withTimeout(5_000) { connection.readExactly(1) })
    }

    @Test
    fun observerReturnsOnAMalformedFrame() = withJvmConnection { connection, client ->
        // A frame header announcing zero bytes is still input.
        client.write(ByteArray(4))
        withTimeout(5_000) { connection.awaitInputOrDisconnect() }
        assertContentEquals(ByteArray(4), withTimeout(5_000) { connection.readExactly(4) })
    }

    @Test
    fun cancellationClosesWhatTheCallBlocksOn() = withJvmConnection { connection, client ->
        val reading = launch { connection.read(ByteArray(4)) }
        delay(100)
        withTimeout(5_000) {
            reading.cancel()
            reading.join()
        }
        assertEquals(0, client.readToEnd().size)
        val error = assertFailsWith<AgentIpcException> { connection.write(byteArrayOf(1)) }
        assertEquals(AgentIpcFailureKind.CLOSED, error.kind)
    }

    @Test
    fun aChildProcessIsServedAndThisProcessIsRejected() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<AgentIpcEvent>())
        val host = AgentIpcHost(AgentIpcListener.bind(agentIpcTestLocation()), 8) { events += it }
        val child = CompletableDeferred<Process>()
        val serving = launch(Dispatchers.Default) {
            host.serve({ child.await().asAgentIpcExpectedPeer() }) { connection ->
                assertEquals("ping", connection.readExactly(4).decodeToString())
                connection.write("pong".encodeToByteArray())
                connection.awaitInputOrDisconnect()
            }
        }
        val process = AgentIpcProcessFixture.start("client", host.endpoint)
        try {
            // Published after the start, as the agent manager does; the child may
            // connect before.
            child.complete(process)
            val output = process.inputStream.bufferedReader()
            val reply = blocking(30.seconds) {
                // The JVM may print its own lines first.
                generateSequence { output.readLine() }.firstOrNull { it.startsWith("REPLY") }
            }
            assertEquals("REPLY pong", reply)

            // This process is not the child, which still runs.
            connectAgentIpcTestClient(host.endpoint).use { client ->
                assertEquals(0, client.readToEnd().size)
            }
            process.outputStream.close()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The client did not exit")
            assertEquals(0, process.exitValue())
            assertTrue(AgentIpcEvent.Rejected(AgentIpcEvent.Reason.PEER_NOT_EXPECTED) in events, "$events")
            assertFalse(events.any { it is AgentIpcEvent.SessionFailed }, "$events")

            // Once the child exited, nothing is served.
            connectAgentIpcTestClient(host.endpoint).use { client ->
                assertEquals(0, client.readToEnd().size)
            }
            assertTrue(AgentIpcEvent.Rejected(AgentIpcEvent.Reason.PEER_NOT_RUNNING) in events, "$events")
        } finally {
            process.destroyForcibly()
            host.stop()
            withTimeout(5_000) { serving.join() }
        }
    }

    @Test
    fun aProcessPeerIsItsIdOnlyWhileItIsAlive() {
        val process = AgentIpcProcessFixture.start("wait")
        try {
            val peer = process.asAgentIpcExpectedPeer()
            assertEquals(process.pid(), peer.currentPid())
            process.outputStream.close()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The fixture did not exit")
            assertNull(peer.currentPid())
        } finally {
            process.destroyForcibly()
        }
    }
}

/** Runs [block] with an accepted connection and its JDK client. */
private fun withJvmConnection(
    block: suspend CoroutineScope.(AgentIpcConnection, AgentIpcTestClient) -> Unit,
) = runBlocking(Dispatchers.Default) {
    AgentIpcListener.bind(agentIpcTestLocation()).use { listener ->
        connectAgentIpcTestClient(listener.endpoint).use { client ->
            withTimeout(5_000) { listener.accept() }.use { connection ->
                block(connection, client)
            }
        }
    }
}
