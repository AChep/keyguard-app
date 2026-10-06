package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.agent.AgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcApi
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcPeer
import com.artemchep.keyguard.common.service.agent.cleanupAgentIpcEndpoint
import com.artemchep.keyguard.common.service.agent.createAgentIpcEndpoint
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.vault.testDomainSessionAccess
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Component tests for [SshAgentIpcServer] using the platform IPC transport.
 *
 * These tests start the IPC server on a Unix socket or Windows named pipe, connect as
 * a client, and exchange actual length-prefixed protobuf messages to verify
 * the full I/O path works end-to-end.
 */
@OptIn(
    ExperimentalSerializationApi::class,
    TestOnlyUnverifiedAgentIpcApi::class,
)
class SshAgentIpcComponentTest {
    private val protoBuf = ProtoBuf
    private val authToken = ByteArray(32) { it.toByte() }

    private val logRepository = LogRepositoryBridge(emptyList())

    private val lockedVaultSession = object : GetVaultSession {
        override val valueOrNull: MasterSession? = null
        override fun invoke(): Flow<MasterSession> = flowOf()
    }

    private val sshAgentFilter = object : GetSshAgentFilter {
        override fun invoke(): Flow<SshAgentFilter> = flowOf(SshAgentFilter())
    }

    @Test
    fun `server accepts connection and authenticates over platform IPC`() = runBlocking {
        withTestServer { endpoint ->
            connect(endpoint).use { client ->
                // Send authenticate request.
                val authRequest = SshAgentMessages.IpcRequest(
                    id = 1L,
                    authenticate = SshAgentMessages.AuthenticateRequest(
                        token = authToken.copyOf(),
                        protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                    ),
                )
                sendMessage(client, authRequest)
                val authResponse = readResponseWithTimeout(client, "authenticate response")

                assertEquals(1L, authResponse.id)
                assertNotNull(authResponse.authenticate)
                assertTrue(authResponse.authenticate!!.success)
            }
        }
    }

    @Test
    fun `server returns empty list keys when locked cache is empty over platform IPC`() = runBlocking {
        withTestServer { endpoint ->
            connect(endpoint).use { client ->
                // Authenticate first.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
                            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                        ),
                    ),
                )
                val authResponse = readResponseWithTimeout(client, "authenticate response")
                assertTrue(authResponse.authenticate!!.success)

                // Now request list keys.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 2L,
                        listKeys = SshAgentMessages.ListKeysRequest(),
                    ),
                )
                val listResponse = readResponseWithTimeout(client, "list keys response")

                assertEquals(2L, listResponse.id)
                assertNull(listResponse.error)
                assertEquals(emptyList(), listResponse.listKeys?.keys)
            }
        }
    }

    @Test
    fun `server rejects bad token over platform IPC`() = runBlocking {
        withTestServer { endpoint ->
            connect(endpoint).use { client ->
                // Send authenticate with wrong token.
                val badToken = ByteArray(32) { 0xFF.toByte() }
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = badToken,
                            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                        ),
                    ),
                )
                val authResponse = readResponseWithTimeout(client, "authenticate response")

                assertEquals(1L, authResponse.id)
                assertNotNull(authResponse.authenticate)
                assertTrue(!authResponse.authenticate!!.success)
            }
        }
    }

    @Test
    fun `server rejects unauthenticated request over platform IPC`() = runBlocking {
        withTestServer { endpoint ->
            connect(endpoint).use { client ->
                // Send list keys without authenticating first.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        listKeys = SshAgentMessages.ListKeysRequest(),
                    ),
                )
                val response = readResponseWithTimeout(client, "unauthenticated response")

                assertEquals(1L, response.id)
                assertNotNull(response.error)
                assertEquals(
                    SshAgentMessages.ErrorCode.NOT_AUTHENTICATED,
                    response.error!!.code,
                )
            }
        }
    }

    @Test
    fun `server handles multiple sequential requests over platform IPC`() = runBlocking {
        withTestServer { endpoint ->
            connect(endpoint).use { client ->
                // Authenticate.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
                            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                        ),
                    ),
                )
                val r1 = readResponseWithTimeout(client, "first authenticate response")
                assertTrue(r1.authenticate!!.success)

                // List keys (should return an empty locked-cache success).
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 2L,
                        listKeys = SshAgentMessages.ListKeysRequest(),
                    ),
                )
                val r2 = readResponseWithTimeout(client, "list keys response")
                assertEquals(2L, r2.id)
                assertNull(r2.error)
                assertEquals(emptyList(), r2.listKeys?.keys)

                // Sign data (should get vault locked).
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 3L,
                        signData = SshAgentMessages.SignDataRequest(
                            publicKey = "ssh-ed25519 AAAA...",
                            data = byteArrayOf(1, 2, 3),
                            flags = 0,
                        ),
                    ),
                )
                val r3 = readResponseWithTimeout(client, "sign data response")
                assertEquals(3L, r3.id)
                assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, r3.error!!.code)
            }
        }
    }

    @Test
    fun `server returns user denied for sign data when approval is denied`() = runBlocking {
        withTestServer(onApprovalRequest = { false }) { endpoint ->
            connect(endpoint).use { client ->
                // Authenticate.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
                            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                        ),
                    ),
                )
                val r1 = readResponseWithTimeout(client, "authenticate response")
                assertTrue(r1.authenticate!!.success)

                // Sign data should be denied by approval callback.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 2L,
                        signData = SshAgentMessages.SignDataRequest(
                            publicKey = "ssh-ed25519 AAAA...",
                            data = byteArrayOf(1, 2, 3),
                            flags = 0,
                        ),
                    ),
                )
                val r2 = readResponseWithTimeout(client, "sign data response")
                assertEquals(2L, r2.id)
                assertEquals(SshAgentMessages.ErrorCode.USER_DENIED, r2.error!!.code)
            }
        }
    }

    @Test
    fun `server rejects connection when max concurrent limit is reached`() = runBlocking {
        withTestServer(maxConcurrentConnections = 1) { endpoint ->
            connect(endpoint).use { firstClient ->
                // Authenticate before opening the second client so the single slot is occupied.
                sendMessage(
                    firstClient,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
                            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                        ),
                    ),
                )
                val firstAuth = readResponseWithTimeout(firstClient, "first client authenticate response")
                assertTrue(firstAuth.authenticate?.success == true)

                connect(endpoint).use { secondClient ->
                    val secondRejected = try {
                        withTimeout(5_000L) {
                            runInterruptible(Dispatchers.IO) {
                                secondClient.read(ByteBuffer.allocate(1)) < 0
                            }
                        }
                    } catch (_: IOException) {
                        true
                    } catch (e: TimeoutCancellationException) {
                        throw AssertionError(
                            "Timed out waiting for second connection rejection within 5000 ms",
                            e,
                        )
                    }
                    assertTrue(secondRejected, "Second connection should be rejected when at capacity")
                }
            }
        }
    }

    private suspend fun withTestServer(
        maxConcurrentConnections: Int = 8,
        onApprovalRequest: suspend (SshAgentApprovalPrompt) -> Boolean = { true },
        block: suspend (AgentIpcEndpoint) -> Unit,
    ) {
        val endpoint = withContext(Dispatchers.IO) {
            createAgentIpcEndpoint("sshagent-test")
        }
        val ready = CompletableDeferred<Unit>()
        val serverScope = CoroutineScope(Dispatchers.IO + Job())
        val server = SshAgentIpcServer(
            logRepository = logRepository,
            getVaultSession = lockedVaultSession,
            sessionAccess = testDomainSessionAccess(),
            getSshAgentFilter = sshAgentFilter,
            authToken = authToken,
            scope = serverScope,
            testOnlyUnverifiedPeer = TestOnlyUnverifiedAgentIpcPeer,
            maxConcurrentConnections = maxConcurrentConnections,
            onApprovalRequest = onApprovalRequest,
        )
        val serverJob = serverScope.async {
            server.start(endpoint, onReady = ready)
        }
        serverJob.invokeOnCompletion { failure ->
            if (failure != null) ready.completeExceptionally(failure)
        }
        try {
            awaitServerReady(ready, endpoint.displayName)
            block(endpoint)
        } finally {
            withContext(NonCancellable) {
                server.stop()
                serverScope.cancel()
                try {
                    awaitServerStopped(serverJob)
                } finally {
                    withContext(Dispatchers.IO) {
                        cleanupAgentIpcEndpoint(endpoint)
                    }
                }
            }
        }
    }

    private suspend fun connect(endpoint: AgentIpcEndpoint): ByteChannel =
        withTimeout(5_000L) {
            var channel: ByteChannel? = null
            while (channel == null) {
                try {
                    channel = runInterruptible(Dispatchers.IO) { openConnection(endpoint) }
                } catch (e: FileNotFoundException) {
                    if (endpoint !is AgentIpcEndpoint.WindowsPipe) throw e
                    // A connected instance stays busy until the accept loop creates the next one.
                    delay(10L)
                }
            }
            channel
        }

    private fun openConnection(endpoint: AgentIpcEndpoint): ByteChannel =
        when (endpoint) {
            is AgentIpcEndpoint.UnixSocket -> {
                val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
                try {
                    channel.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
                    channel
                } catch (e: IOException) {
                    channel.close()
                    throw e
                }
            }

            // Closing the FileChannel also closes its owning RandomAccessFile.
            is AgentIpcEndpoint.WindowsPipe -> RandomAccessFile(endpoint.pipeName, "rw").channel
        }

    private suspend fun awaitServerReady(
        ready: CompletableDeferred<Unit>,
        operation: String,
    ) {
        try {
            withTimeout(5_000L) {
                ready.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for server readiness in $operation within 5000 ms",
                e,
            )
        }
    }

    private suspend fun readResponseWithTimeout(
        channel: ByteChannel,
        operation: String,
    ): SshAgentMessages.IpcResponse {
        return try {
            withTimeout(5_000L) {
                readResponse(channel)
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for $operation within 5000 ms",
                e,
            )
        }
    }

    private suspend fun awaitServerStopped(serverJob: Job) {
        try {
            withTimeout(5_000L) {
                serverJob.join()
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError(
                "Timed out waiting for server job to stop within 5000 ms",
                e,
            )
        }
    }

    /**
     * Sends a length-prefixed protobuf IpcRequest over the platform channel.
     */
    private suspend fun sendMessage(
        channel: ByteChannel,
        request: SshAgentMessages.IpcRequest,
    ) {
        runInterruptible(Dispatchers.IO) {
            val bytes = protoBuf.encodeToByteArray(request)
            val buf = ByteBuffer.allocate(4 + bytes.size)
            buf.putInt(bytes.size)
            buf.put(bytes)
            buf.flip()
            while (buf.hasRemaining()) {
                channel.write(buf)
            }
        }
    }

    /**
     * Reads a length-prefixed protobuf IpcResponse from the platform channel.
     */
    private suspend fun readResponse(channel: ByteChannel): SshAgentMessages.IpcResponse {
        return runInterruptible(Dispatchers.IO) {
            // Read 4-byte length prefix.
            val lenBuf = ByteBuffer.allocate(4)
            while (lenBuf.hasRemaining()) {
                val n = channel.read(lenBuf)
                if (n < 0) throw java.io.EOFException("Unexpected EOF reading length")
            }
            lenBuf.flip()
            val len = lenBuf.int

            // Read message body.
            val msgBuf = ByteBuffer.allocate(len)
            while (msgBuf.hasRemaining()) {
                val n = channel.read(msgBuf)
                if (n < 0) throw java.io.EOFException("Unexpected EOF reading body")
            }
            msgBuf.flip()

            val bytes = ByteArray(len)
            msgBuf.get(bytes)
            protoBuf.decodeFromByteArray(bytes)
        }
    }
}
