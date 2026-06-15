package com.artemchep.keyguard.common.service.sshagent

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSshAgentSecureBridgeTest {
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `launch augments protobuf identities request with Android sender app info`() = runBlocking {
        withTimeout(5_000) {
            val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val publicKeyBlob = byteArrayOf(1, 2, 3, 4)
                val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicKeyBlob)} secure@example"
                var capturedCaller: SshAgentMessages.CallerIdentity? = null
                val requestProcessor = createRequestProcessor(
                    listKeys = { caller ->
                        capturedCaller = caller
                        SshAgentRequestProcessor.ListKeysResult.Success(
                            SshAgentMessages.ListKeysResponse(
                                keys = listOf(
                                    SshAgentMessages.SshKey(
                                        name = "Secure key",
                                        publicKey = publicKey,
                                        keyType = "ssh-ed25519",
                                        fingerprint = "SHA256:secure",
                                    ),
                                ),
                            ),
                        )
                    },
                )
                val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }
                val senderAppInfo = buildAndroidSshAgentCallerIdentity(
                    appName = "Termux",
                    appBundlePath = "com.termux",
                )
                val requestPayload = ProtoBuf.encodeToByteArray(
                    SshAgentMessages.IpcRequest(
                        id = 41L,
                        listKeys = SshAgentMessages.ListKeysRequest(
                            caller = SshAgentMessages.CallerIdentity(
                                pid = 123,
                                uid = 456,
                                gid = 789,
                                processName = "ssh",
                                executablePath = "/data/data/com.termux/files/usr/bin/ssh",
                            ),
                        ),
                    ),
                )

                ServerSocket().use { server ->
                    server.bind(InetSocketAddress("127.0.0.1", 0))

                    val bridgeJob = bridgeScope.launchSshAgentProxyBridge(
                        requestProcessor = requestProcessor,
                        proxyPort = server.localPort,
                        sessionId = sessionId,
                        sessionSecret = sessionSecret,
                        senderAppInfo = senderAppInfo,
                        connectHostCandidates = listOf("127.0.0.1"),
                    )
                    val toolJob = async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            val toolChannel = SshAgentTcpProtocol.openAsTool(
                                input = socket.getInputStream(),
                                output = socket.getOutputStream(),
                                sessionId = sessionId,
                                sessionSecret = sessionSecret,
                            )
                            toolChannel.writePacket(requestPayload)
                            requireNotNull(toolChannel.readPacket())
                        }
                    }

                    val responsePayload = toolJob.await()
                    val response = ProtoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(responsePayload)
                    val listKeys = requireNotNull(response.listKeys)
                    assertEquals(41L, response.id)
                    assertEquals(1, listKeys.keys.size)
                    assertEquals(123, capturedCaller?.pid)
                    assertEquals(456, capturedCaller?.uid)
                    assertEquals(789, capturedCaller?.gid)
                    assertEquals("ssh", capturedCaller?.processName)
                    assertEquals("/data/data/com.termux/files/usr/bin/ssh", capturedCaller?.executablePath)
                    assertEquals("Termux", capturedCaller?.appName)
                    assertEquals("com.termux", capturedCaller?.appBundlePath)
                    bridgeJob.join()
                    assertFalse(bridgeJob.isCancelled)
                }
            } finally {
                bridgeScope.cancel()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `launch preserves protobuf caller when Android sender app info is unavailable`() = runBlocking {
        withTimeout(5_000) {
            val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val publicKeyBlob = byteArrayOf(5, 4, 3, 2, 1)
                val publicKey = "ssh-ed25519\t${Base64.getEncoder().encodeToString(publicKeyBlob)} secure@example"
                var capturedRequest: SshAgentMessages.SignDataRequest? = null
                val requestProcessor = createRequestProcessor(
                    signData = { request ->
                        capturedRequest = request
                        SshAgentRequestProcessor.SignDataResult.Success(
                            SshAgentMessages.SignDataResponse(
                                signature = byteArrayOf(9, 8, 7),
                                algorithm = "ssh-ed25519",
                            ),
                        )
                    },
                )
                val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }
                val requestPayload = ProtoBuf.encodeToByteArray(
                    SshAgentMessages.IpcRequest(
                        id = 42L,
                        signData = SshAgentMessages.SignDataRequest(
                            publicKey = publicKey,
                            data = byteArrayOf(1, 2, 3, 4),
                            flags = 0,
                            caller = SshAgentMessages.CallerIdentity(
                                pid = 222,
                                uid = 333,
                                gid = 444,
                                processName = "ssh",
                                executablePath = "/data/data/com.termux/files/usr/bin/ssh",
                            ),
                        ),
                    ),
                )

                ServerSocket().use { server ->
                    server.bind(InetSocketAddress("127.0.0.1", 0))

                    val bridgeJob = bridgeScope.launchSshAgentProxyBridge(
                        requestProcessor = requestProcessor,
                        proxyPort = server.localPort,
                        sessionId = sessionId,
                        sessionSecret = sessionSecret,
                        connectHostCandidates = listOf("127.0.0.1"),
                    )
                    val toolJob = async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            val toolChannel = SshAgentTcpProtocol.openAsTool(
                                input = socket.getInputStream(),
                                output = socket.getOutputStream(),
                                sessionId = sessionId,
                                sessionSecret = sessionSecret,
                            )
                            toolChannel.writePacket(requestPayload)
                            requireNotNull(toolChannel.readPacket())
                        }
                    }

                    val responsePayload = toolJob.await()
                    val response = ProtoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(responsePayload)
                    assertEquals(42L, response.id)
                    assertEquals(
                        SshAgentMessages.SignDataResponse(
                            signature = byteArrayOf(9, 8, 7),
                            algorithm = "ssh-ed25519",
                        ),
                        response.signData,
                    )
                    assertEquals(222, capturedRequest?.caller?.pid)
                    assertEquals(333, capturedRequest?.caller?.uid)
                    assertEquals(444, capturedRequest?.caller?.gid)
                    assertEquals("ssh", capturedRequest?.caller?.processName)
                    assertEquals("/data/data/com.termux/files/usr/bin/ssh", capturedRequest?.caller?.executablePath)
                    assertEquals("", capturedRequest?.caller?.appName)
                    assertEquals("", capturedRequest?.caller?.appBundlePath)
                    assertEquals(publicKey, capturedRequest?.publicKey)
                    assertContentEquals(byteArrayOf(1, 2, 3, 4), capturedRequest?.data)
                    assertEquals(0, capturedRequest?.flags)
                    bridgeJob.join()
                    assertFalse(bridgeJob.isCancelled)
                }
            } finally {
                bridgeScope.cancel()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `launch returns protobuf error responses for unsupported authenticate requests`() = runBlocking {
        withTimeout(5_000) {
            val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val requestProcessor = createRequestProcessor(
                    listKeys = { _ ->
                        SshAgentRequestProcessor.ListKeysResult.VaultLocked
                    },
                )
                val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
                val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }
                val requestPayload = ProtoBuf.encodeToByteArray(
                    SshAgentMessages.IpcRequest(
                        id = 43L,
                        authenticate = SshAgentMessages.AuthenticateRequest(token = byteArrayOf(1, 2, 3)),
                    ),
                )

                ServerSocket().use { server ->
                    server.bind(InetSocketAddress("127.0.0.1", 0))

                    val bridgeJob = bridgeScope.launchSshAgentProxyBridge(
                        requestProcessor = requestProcessor,
                        proxyPort = server.localPort,
                        sessionId = sessionId,
                        sessionSecret = sessionSecret,
                        connectHostCandidates = listOf("127.0.0.1"),
                    )
                    val toolJob = async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            val toolChannel = SshAgentTcpProtocol.openAsTool(
                                input = socket.getInputStream(),
                                output = socket.getOutputStream(),
                                sessionId = sessionId,
                                sessionSecret = sessionSecret,
                            )
                            toolChannel.writePacket(requestPayload)
                            requireNotNull(toolChannel.readPacket())
                        }
                    }

                    val responsePayload = toolJob.await()
                    val response = ProtoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(responsePayload)
                    val error = requireNotNull(response.error)
                    assertEquals(43L, response.id)
                    assertEquals(SshAgentMessages.ErrorCode.UNSPECIFIED, error.code)
                    assertEquals(
                        "AuthenticateRequest is not supported on this transport",
                        error.message,
                    )
                    bridgeJob.join()
                    assertFalse(bridgeJob.isCancelled)
                }
            } finally {
                bridgeScope.cancel()
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `launch cancellation aborts a blocked proxy connect attempt`() = runBlocking {
        withTimeout(5_000) {
            val connectStarted = CompletableDeferred<Unit>()
            val connectClosed = CompletableDeferred<Unit>()
            val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val socket = object : Socket() {
                    override fun connect(endpoint: SocketAddress?, timeout: Int) {
                        connectStarted.complete(Unit)
                        runBlocking {
                            connectClosed.await()
                        }
                        throw CancellationException("connect aborted")
                    }

                    override fun close() {
                        connectClosed.complete(Unit)
                        super.close()
                    }
                }
                val bridgeJob = bridgeScope.launchSshAgentProxyBridge(
                    requestProcessor = createRequestProcessor(),
                    proxyPort = 1,
                    sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 },
                    sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 },
                    connectHostCandidates = listOf("127.0.0.1"),
                    socketFactory = { socket },
                )

                connectStarted.await()
                bridgeScope.cancel()
                bridgeJob.join()
                assertTrue(bridgeJob.isCancelled)
            } finally {
                bridgeScope.cancel()
            }
        }
    }

    @Test
    fun `launch cancellation closes an active proxy socket`() = runBlocking {
        withTimeout(5_000) {
            val uncaughtErrors = CopyOnWriteArrayList<Throwable>()
            val exceptionHandler = CoroutineExceptionHandler { _, error ->
                uncaughtErrors += error
            }
            val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
            val sessionEstablished = CompletableDeferred<Unit>()
            try {
                ServerSocket().use { server ->
                    server.bind(InetSocketAddress("127.0.0.1", 0))

                    val bridgeJob = bridgeScope.launchSshAgentProxyBridge(
                        requestProcessor = createRequestProcessor(),
                        proxyPort = server.localPort,
                        sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 },
                        sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 },
                        connectHostCandidates = listOf("127.0.0.1"),
                    )
                    val bridgeCompletion = CompletableDeferred<Throwable?>()
                    bridgeJob.invokeOnCompletion { error ->
                        bridgeCompletion.complete(error)
                    }
                    val toolJob = async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            val toolChannel = SshAgentTcpProtocol.openAsTool(
                                input = socket.getInputStream(),
                                output = socket.getOutputStream(),
                                sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 },
                                sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 },
                            )
                            sessionEstablished.complete(Unit)
                            toolChannel.readPacket()
                        }
                    }

                    sessionEstablished.await()
                    bridgeScope.cancel()
                    bridgeJob.join()
                    assertTrue(bridgeJob.isCancelled)
                    assertNull(toolJob.await())
                    assertTrue(bridgeCompletion.await() is CancellationException)
                    assertTrue(uncaughtErrors.isEmpty(), uncaughtErrors.joinToString())
                }
            } finally {
                bridgeScope.cancel()
            }
        }
    }

    private fun createRequestProcessor(
        listKeys: suspend (SshAgentMessages.CallerIdentity?) -> SshAgentRequestProcessor.ListKeysResult =
            { SshAgentRequestProcessor.ListKeysResult.VaultLocked },
        signData: suspend (SshAgentMessages.SignDataRequest) -> SshAgentRequestProcessor.SignDataResult =
            { SshAgentRequestProcessor.SignDataResult.UserDenied },
    ) = object : SshAgentRequestProcessor {
        override suspend fun listKeys(
            caller: SshAgentMessages.CallerIdentity?,
        ): SshAgentRequestProcessor.ListKeysResult = listKeys(caller)

        override suspend fun signData(
            request: SshAgentMessages.SignDataRequest,
        ): SshAgentRequestProcessor.SignDataResult = signData(request)
    }
}
