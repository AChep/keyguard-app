package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentIpcMessages
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.service.sshagent.SshAgentTcpProtocol
import com.artemchep.keyguard.common.service.sshagent.androidBridgeAuthorization
import com.artemchep.keyguard.common.service.sshagent.buildAndroidSshAgentCallerIdentity
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalSerializationApi::class)
class AndroidGpgAgentSecureBridgeTest {
    private val sessionId = ByteArray(SshAgentTcpProtocol.SESSION_ID_LENGTH) { 0x11 }
    private val sessionSecret = ByteArray(SshAgentTcpProtocol.SESSION_SECRET_LENGTH) { 0x22 }

    private val helperCaller = CallerIdentity(
        pid = 123,
        uid = 456,
        gid = 789,
        processName = "gpg",
        executablePath = "/data/data/com.termux/files/usr/bin/gpg",
        appName = "Spoofed",
        appBundlePath = "com.spoofed",
    )

    @Test
    fun `sign request is attributed to the Android sender app`() = runBlocking {
        var capturedCaller: CallerIdentity? = null
        val processor = FakeGpgAgentRequestProcessor(
            signHash = { request ->
                capturedCaller = request.caller
                GpgAgentRequestProcessor.GpgAgentOperationResult.Success(
                    GpgAgentMessages.SignHashResponse(sexp = "(sig-val)"),
                )
            },
        )
        val senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
            authorization = androidBridgeAuthorization(
                principalFingerprint = ByteArray(32) { 0x33 },
                connectionFingerprint = ByteArray(32) { 0x44 },
            ),
        )

        val response = exchange(
            processor = processor,
            senderAppInfo = senderAppInfo,
            request = GpgAgentMessages.IpcRequest(
                id = 7L,
                signHash = GpgAgentMessages.SignHashRequest(
                    keygrip = "A".repeat(40),
                    hashAlgorithm = "sha256",
                    hash = ByteArray(32),
                    caller = helperCaller,
                ),
            ),
        )

        assertEquals(7L, response.id)
        assertEquals("(sig-val)", response.signHash?.sexp)
        val caller = assertNotNull(capturedCaller)
        assertEquals(123, caller.pid)
        assertEquals("/data/data/com.termux/files/usr/bin/gpg", caller.executablePath)
        assertEquals("Termux", caller.appName)
        assertEquals("com.termux", caller.appBundlePath)
        assertContentEquals(
            ByteArray(32) { 0x33 },
            caller.authorization?.subjects?.single()?.fingerprint,
        )
    }

    @Test
    fun `helper metadata is discarded without a verified sender app`() = runBlocking {
        var capturedCaller: CallerIdentity? = helperCaller
        val processor = FakeGpgAgentRequestProcessor(
            listKeys = { caller ->
                capturedCaller = caller
                GpgAgentRequestProcessor.ListKeysResult.Success(
                    GpgAgentMessages.ListKeysResponse(),
                )
            },
        )

        val response = exchange(
            processor = processor,
            senderAppInfo = null,
            request = GpgAgentMessages.IpcRequest(
                id = 8L,
                listKeys = GpgAgentMessages.ListKeysRequest(caller = helperCaller),
            ),
        )

        assertNotNull(response.listKeys)
        assertNull(capturedCaller)
    }

    @Test
    fun `the helper cannot authenticate with a token`() = runBlocking {
        val response = exchange(
            processor = FakeGpgAgentRequestProcessor(),
            senderAppInfo = null,
            request = GpgAgentMessages.IpcRequest(
                id = 9L,
                authenticate = AgentIpcMessages.AuthenticateRequest(
                    token = ByteArray(32),
                    protocolRevision = AgentIpcMessages.PROTOCOL_REVISION,
                ),
            ),
        )

        assertNull(response.authenticate)
        assertEquals(AgentIpcMessages.ErrorCode.UNSPECIFIED, response.error?.code)
    }

    @Test
    fun `idle bridge is closed at the packet read deadline`() = runBlocking {
        withTimeout(5_000) {
            val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                ServerSocket().use { server ->
                    server.bind(InetSocketAddress("127.0.0.1", 0))
                    val bridgeJob = bridgeScope.launch {
                        runGpgAgentProxyBridge(
                            requestProcessor = FakeGpgAgentRequestProcessor(),
                            proxyPort = server.localPort,
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                            connectHostCandidates = listOf("127.0.0.1"),
                            packetReadTimeoutMs = 75,
                        )
                    }
                    val toolJob = async(Dispatchers.IO) {
                        server.accept().use { socket ->
                            val toolChannel = SshAgentTcpProtocol.openAsTool(
                                input = socket.getInputStream(),
                                output = socket.getOutputStream(),
                                sessionId = sessionId,
                                sessionSecret = sessionSecret,
                            )
                            toolChannel.readPacket()
                        }
                    }

                    bridgeJob.join()
                    assertFalse(bridgeJob.isCancelled)
                    assertNull(toolJob.await())
                }
            } finally {
                bridgeScope.cancel()
            }
        }
    }

    /** Sends [request] as the Termux helper and returns the app's response. */
    private suspend fun exchange(
        processor: GpgAgentRequestProcessor,
        senderAppInfo: CallerIdentity?,
        request: GpgAgentMessages.IpcRequest,
    ): GpgAgentMessages.IpcResponse = withTimeout(5_000) {
        val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            ServerSocket().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                val bridgeJob = bridgeScope.launch {
                    runGpgAgentProxyBridge(
                        requestProcessor = processor,
                        proxyPort = server.localPort,
                        sessionId = sessionId,
                        sessionSecret = sessionSecret,
                        senderAppInfo = senderAppInfo,
                    )
                }
                val responsePayload = async(Dispatchers.IO) {
                    server.accept().use { socket ->
                        val toolChannel = SshAgentTcpProtocol.openAsTool(
                            input = socket.getInputStream(),
                            output = socket.getOutputStream(),
                            sessionId = sessionId,
                            sessionSecret = sessionSecret,
                        )
                        toolChannel.writePacket(ProtoBuf.encodeToByteArray(request))
                        requireNotNull(toolChannel.readPacket())
                    }
                }.await()
                bridgeJob.join()
                ProtoBuf.decodeFromByteArray<GpgAgentMessages.IpcResponse>(responsePayload)
            }
        } finally {
            bridgeScope.cancel()
        }
    }
}

private class FakeGpgAgentRequestProcessor(
    private val listKeys: (CallerIdentity?) -> GpgAgentRequestProcessor.ListKeysResult = {
        GpgAgentRequestProcessor.ListKeysResult.VaultLocked
    },
    private val signHash: (
        GpgAgentMessages.SignHashRequest,
    ) -> GpgAgentRequestProcessor.GpgAgentOperationResult<GpgAgentMessages.SignHashResponse> = {
        GpgAgentRequestProcessor.GpgAgentOperationResult.VaultLocked
    },
) : GpgAgentRequestProcessor {
    override suspend fun listKeys(
        caller: CallerIdentity?,
    ): GpgAgentRequestProcessor.ListKeysResult = listKeys.invoke(caller)

    override suspend fun signHash(
        request: GpgAgentMessages.SignHashRequest,
    ): GpgAgentRequestProcessor.GpgAgentOperationResult<GpgAgentMessages.SignHashResponse> =
        signHash.invoke(request)

    override suspend fun decrypt(
        request: GpgAgentMessages.PkdecryptRequest,
    ): GpgAgentRequestProcessor.GpgAgentOperationResult<GpgAgentMessages.PkdecryptResponse> =
        GpgAgentRequestProcessor.GpgAgentOperationResult.VaultLocked
}
