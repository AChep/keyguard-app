package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Component tests for [SshAgentIpcServer] using a real Unix domain socket.
 *
 * These tests start the IPC server on a temporary socket file, connect as
 * a client, and exchange actual length-prefixed protobuf messages to verify
 * the full I/O path works end-to-end.
 */
@OptIn(ExperimentalSerializationApi::class)
class SshAgentIpcComponentTest {
    private val protoBuf = ProtoBuf
    private val authToken = ByteArray(32) { it.toByte() }

    private val logRepository = object : LogRepository {
        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            // Do nothing
        }
    }

    private val lockedVaultSession = object : GetVaultSession {
        override val valueOrNull: MasterSession? = null
        override fun invoke(): Flow<MasterSession> = flowOf()
    }

    private val sshAgentFilter = object : GetSshAgentFilter {
        override fun invoke(): Flow<SshAgentFilter> = flowOf(SshAgentFilter())
    }

    // ================================================================
    // Unix socket integration
    // ================================================================

    @Test
    fun `server accepts connection and authenticates over unix socket`() = runBlocking {
        withTempSocket { socketPath ->
            val ready = CompletableDeferred<Unit>()
            val serverScope = CoroutineScope(Dispatchers.IO + Job())

            val server = SshAgentIpcServer(
                logRepository = logRepository,
                getVaultSession = lockedVaultSession,
                getSshAgentFilter = sshAgentFilter,
                authToken = authToken,
                scope = serverScope,
            )

            // Start server in background.
            val serverJob = serverScope.launch {
                server.start(socketPath, onReady = ready)
            }

            // Wait for server to be ready.
            awaitServerReady(ready, "authentication test")

            // Connect client.
            val client = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            try {
                // Send authenticate request.
                val authRequest = SshAgentMessages.IpcRequest(
                    id = 1L,
                    authenticate = SshAgentMessages.AuthenticateRequest(token = authToken.copyOf()),
                )
                sendMessage(client, authRequest)
                val authResponse = readResponseWithTimeout(client, "authenticate response")

                assertEquals(1L, authResponse.id)
                assertNotNull(authResponse.authenticate)
                assertTrue(authResponse.authenticate!!.success)
            } finally {
                client.close()
                server.stop()
                serverScope.cancel()
                awaitServerStopped(serverJob)
            }
        }
    }

    @Test
    fun `server returns vault locked for list keys over unix socket`() = runBlocking {
        withTempSocket { socketPath ->
            val ready = CompletableDeferred<Unit>()
            val serverScope = CoroutineScope(Dispatchers.IO + Job())

            val server = SshAgentIpcServer(
                logRepository = logRepository,
                getVaultSession = lockedVaultSession,
                getSshAgentFilter = sshAgentFilter,
                authToken = authToken,
                scope = serverScope,
            )

            val serverJob = serverScope.launch {
                server.start(socketPath, onReady = ready)
            }

            awaitServerReady(ready, "list keys test")

            val client = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            try {
                // Authenticate first.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
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
                assertNotNull(listResponse.error)
                assertEquals(
                    SshAgentMessages.ErrorCode.VAULT_LOCKED,
                    listResponse.error!!.code,
                )
            } finally {
                client.close()
                server.stop()
                serverScope.cancel()
                awaitServerStopped(serverJob)
            }
        }
    }

    @Test
    fun `server rejects bad token over unix socket`() = runBlocking {
        withTempSocket { socketPath ->
            val ready = CompletableDeferred<Unit>()
            val serverScope = CoroutineScope(Dispatchers.IO + Job())

            val server = SshAgentIpcServer(
                logRepository = logRepository,
                getVaultSession = lockedVaultSession,
                getSshAgentFilter = sshAgentFilter,
                authToken = authToken,
                scope = serverScope,
            )

            val serverJob = serverScope.launch {
                server.start(socketPath, onReady = ready)
            }

            awaitServerReady(ready, "bad token test")

            val client = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            try {
                // Send authenticate with wrong token.
                val badToken = ByteArray(32) { 0xFF.toByte() }
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(token = badToken),
                    ),
                )
                val authResponse = readResponseWithTimeout(client, "authenticate response")

                assertEquals(1L, authResponse.id)
                assertNotNull(authResponse.authenticate)
                assertTrue(!authResponse.authenticate!!.success)
            } finally {
                client.close()
                server.stop()
                serverScope.cancel()
                awaitServerStopped(serverJob)
            }
        }
    }

    @Test
    fun `server rejects unauthenticated request over unix socket`() = runBlocking {
        withTempSocket { socketPath ->
            val ready = CompletableDeferred<Unit>()
            val serverScope = CoroutineScope(Dispatchers.IO + Job())

            val server = SshAgentIpcServer(
                logRepository = logRepository,
                getVaultSession = lockedVaultSession,
                getSshAgentFilter = sshAgentFilter,
                authToken = authToken,
                scope = serverScope,
            )

            val serverJob = serverScope.launch {
                server.start(socketPath, onReady = ready)
            }

            awaitServerReady(ready, "unauthenticated request test")

            val client = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            try {
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
            } finally {
                client.close()
                server.stop()
                serverScope.cancel()
                awaitServerStopped(serverJob)
            }
        }
    }

    @Test
    fun `server handles multiple sequential requests over unix socket`() = runBlocking {
        withTempSocket { socketPath ->
            val ready = CompletableDeferred<Unit>()
            val serverScope = CoroutineScope(Dispatchers.IO + Job())

            val server = SshAgentIpcServer(
                logRepository = logRepository,
                getVaultSession = lockedVaultSession,
                getSshAgentFilter = sshAgentFilter,
                authToken = authToken,
                scope = serverScope,
            )

            val serverJob = serverScope.launch {
                server.start(socketPath, onReady = ready)
            }

            awaitServerReady(ready, "sequential requests test")

            val client = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            try {
                // Authenticate.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
                        ),
                    ),
                )
                val r1 = readResponseWithTimeout(client, "first authenticate response")
                assertTrue(r1.authenticate!!.success)

                // List keys (should get vault locked).
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 2L,
                        listKeys = SshAgentMessages.ListKeysRequest(),
                    ),
                )
                val r2 = readResponseWithTimeout(client, "list keys response")
                assertEquals(2L, r2.id)
                assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, r2.error!!.code)

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
            } finally {
                client.close()
                server.stop()
                serverScope.cancel()
                awaitServerStopped(serverJob)
            }
        }
    }

    @Test
    fun `server returns user denied for sign data when approval is denied`() = runBlocking {
        withTempSocket { socketPath ->
            val ready = CompletableDeferred<Unit>()
            val serverScope = CoroutineScope(Dispatchers.IO + Job())

            val server = SshAgentIpcServer(
                logRepository = logRepository,
                getVaultSession = lockedVaultSession,
                getSshAgentFilter = sshAgentFilter,
                authToken = authToken,
                scope = serverScope,
                onApprovalRequest = { _, _, _ -> false },
            )

            val serverJob = serverScope.launch {
                server.start(socketPath, onReady = ready)
            }

            awaitServerReady(ready, "user denied sign data test")

            val client = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            try {
                // Authenticate.
                sendMessage(
                    client,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
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
            } finally {
                client.close()
                server.stop()
                serverScope.cancel()
                awaitServerStopped(serverJob)
            }
        }
    }

    @Test
    fun `server rejects connection when max concurrent limit is reached`() = runBlocking {
        withTempSocket { socketPath ->
            val ready = CompletableDeferred<Unit>()
            val serverScope = CoroutineScope(Dispatchers.IO + Job())

            val server = SshAgentIpcServer(
                logRepository = logRepository,
                getVaultSession = lockedVaultSession,
                getSshAgentFilter = sshAgentFilter,
                authToken = authToken,
                scope = serverScope,
                maxConcurrentConnections = 1,
            )

            val serverJob = serverScope.launch {
                server.start(socketPath, onReady = ready)
            }

            awaitServerReady(ready, "max concurrent connections test")

            val firstClient = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            val secondClient = withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).also {
                    it.connect(UnixDomainSocketAddress.of(socketPath))
                }
            }

            try {
                // Keep the first connection active and occupying the single slot.
                sendMessage(
                    firstClient,
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = authToken.copyOf(),
                        ),
                    ),
                )
                val firstAuth = readResponseWithTimeout(firstClient, "first client authenticate response")
                assertTrue(firstAuth.authenticate?.success == true)

                val secondRejected = try {
                    withTimeout(5_000L) {
                        withContext(Dispatchers.IO) {
                            secondClient.configureBlocking(true)
                            val probe = ByteBuffer.allocate(1)
                            secondClient.read(probe) < 0
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
            } finally {
                secondClient.close()
                firstClient.close()
                server.stop()
                serverScope.cancel()
                awaitServerStopped(serverJob)
            }
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    /**
     * Creates a temporary directory and socket path, runs the block,
     * and cleans up afterward.
     */
    private suspend fun withTempSocket(block: suspend (Path) -> Unit) {
        val tempDir = withContext(Dispatchers.IO) {
            Files.createTempDirectory("sshagent-test-")
        }
        val socketPath = tempDir.resolve("test-agent.sock")
        try {
            block(socketPath)
        } finally {
            withContext(Dispatchers.IO) {
                Files.deleteIfExists(socketPath)
                Files.deleteIfExists(tempDir)
            }
        }
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
        channel: SocketChannel,
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
     * Sends a length-prefixed protobuf IpcRequest over a SocketChannel.
     */
    private suspend fun sendMessage(
        channel: SocketChannel,
        request: SshAgentMessages.IpcRequest,
    ) {
        withContext(Dispatchers.IO) {
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
     * Reads a length-prefixed protobuf IpcResponse from a SocketChannel.
     */
    private suspend fun readResponse(channel: SocketChannel): SshAgentMessages.IpcResponse {
        return withContext(Dispatchers.IO) {
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
