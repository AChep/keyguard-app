package com.artemchep.keyguard.common.service.sshagent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshAgentPacketSessionTest {
    @Test
    fun `ipc session authenticates then handles subsequent requests`() = runTest {
        val channel = FakePacketChannel(
            requests = listOf(
                SshAgentProtoCodec.encodeRequest(
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = byteArrayOf(1, 2, 3),
                        ),
                    ),
                ),
                SshAgentProtoCodec.encodeRequest(
                    SshAgentMessages.IpcRequest(
                        id = 2L,
                        listKeys = SshAgentMessages.ListKeysRequest(),
                    ),
                ),
            ),
        )
        val rpcHandler = SshAgentRpcHandler(
            requestProcessor = requestProcessor(
                listKeys = {
                    SshAgentRequestProcessor.ListKeysResult.Success(
                        SshAgentMessages.ListKeysResponse(keys = emptyList()),
                    )
                },
            ),
            authenticate = { it.token.contentEquals(byteArrayOf(1, 2, 3)) },
        )

        runSshAgentPacketSession(
            channel = channel,
            rpcHandler = rpcHandler,
            initialContext = SshAgentRpcRequestContext(
                authenticated = false,
                allowAuthenticate = true,
            ),
        )

        assertEquals(2, channel.responses.size)
        val authResponse = SshAgentProtoCodec.decodeResponse(channel.responses[0])
        val listKeysResponse = SshAgentProtoCodec.decodeResponse(channel.responses[1])
        assertTrue(authResponse.authenticate?.success == true)
        assertNotNull(listKeysResponse.listKeys)
    }

    @Test
    fun `ipc session stops after failed authenticate`() = runTest {
        val channel = FakePacketChannel(
            requests = listOf(
                SshAgentProtoCodec.encodeRequest(
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = byteArrayOf(9, 9, 9),
                        ),
                    ),
                ),
                SshAgentProtoCodec.encodeRequest(
                    SshAgentMessages.IpcRequest(
                        id = 2L,
                        listKeys = SshAgentMessages.ListKeysRequest(),
                    ),
                ),
            ),
        )
        val rpcHandler = SshAgentRpcHandler(
            requestProcessor = requestProcessor(),
            authenticate = { false },
        )

        runSshAgentPacketSession(
            channel = channel,
            rpcHandler = rpcHandler,
            initialContext = SshAgentRpcRequestContext(
                authenticated = false,
                allowAuthenticate = true,
            ),
        )

        assertEquals(1, channel.responses.size)
        val authResponse = SshAgentProtoCodec.decodeResponse(channel.responses.single())
        assertFalse(authResponse.authenticate?.success == true)
    }

    @Test
    fun `tcp session rejects authenticate request but continues serving requests`() = runTest {
        val channel = FakePacketChannel(
            requests = listOf(
                SshAgentProtoCodec.encodeRequest(
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        authenticate = SshAgentMessages.AuthenticateRequest(
                            token = byteArrayOf(1, 2, 3),
                        ),
                    ),
                ),
                SshAgentProtoCodec.encodeRequest(
                    SshAgentMessages.IpcRequest(
                        id = 2L,
                        listKeys = SshAgentMessages.ListKeysRequest(),
                    ),
                ),
            ),
        )
        val rpcHandler = SshAgentRpcHandler(
            requestProcessor = requestProcessor(
                listKeys = {
                    SshAgentRequestProcessor.ListKeysResult.Success(
                        SshAgentMessages.ListKeysResponse(keys = emptyList()),
                    )
                },
            ),
        )

        runSshAgentPacketSession(
            channel = channel,
            rpcHandler = rpcHandler,
            initialContext = SshAgentRpcRequestContext(
                authenticated = true,
                allowAuthenticate = false,
            ),
        )

        assertEquals(2, channel.responses.size)
        val authResponse = SshAgentProtoCodec.decodeResponse(channel.responses[0])
        val listKeysResponse = SshAgentProtoCodec.decodeResponse(channel.responses[1])
        assertEquals(
            "AuthenticateRequest is not supported on this transport",
            authResponse.error?.message,
        )
        assertNotNull(listKeysResponse.listKeys)
    }

    @Test
    fun `tcp session augments caller identity when configured`() = runTest {
        var capturedCaller: SshAgentMessages.CallerIdentity? = null
        val channel = FakePacketChannel(
            requests = listOf(
                SshAgentProtoCodec.encodeRequest(
                    SshAgentMessages.IpcRequest(
                        id = 1L,
                        listKeys = SshAgentMessages.ListKeysRequest(
                            caller = SshAgentMessages.CallerIdentity(
                                pid = 7,
                                processName = "ssh",
                                executablePath = "/usr/bin/ssh",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val rpcHandler = SshAgentRpcHandler(
            requestProcessor = requestProcessor(
                listKeys = { caller ->
                    capturedCaller = caller
                    SshAgentRequestProcessor.ListKeysResult.Success(
                        SshAgentMessages.ListKeysResponse(keys = emptyList()),
                    )
                },
            ),
        )

        runSshAgentPacketSession(
            channel = channel,
            rpcHandler = rpcHandler,
            initialContext = SshAgentRpcRequestContext(
                authenticated = true,
                allowAuthenticate = false,
                callerAugmentation = buildAndroidSshAgentCallerIdentity(
                    appName = "Termux",
                    appBundlePath = "com.termux",
                ),
            ),
        )

        assertEquals(1, channel.responses.size)
        assertEquals(7, capturedCaller?.pid)
        assertEquals("ssh", capturedCaller?.processName)
        assertEquals("/usr/bin/ssh", capturedCaller?.executablePath)
        assertEquals("Termux", capturedCaller?.appName)
        assertEquals("com.termux", capturedCaller?.appBundlePath)
    }

    private fun requestProcessor(
        listKeys: suspend (SshAgentMessages.CallerIdentity?) -> SshAgentRequestProcessor.ListKeysResult =
            { SshAgentRequestProcessor.ListKeysResult.VaultLocked },
    ) = object : SshAgentRequestProcessor {
        override suspend fun listKeys(
            caller: SshAgentMessages.CallerIdentity?,
        ): SshAgentRequestProcessor.ListKeysResult = listKeys(caller)

        override suspend fun signData(
            request: SshAgentMessages.SignDataRequest,
        ): SshAgentRequestProcessor.SignDataResult = SshAgentRequestProcessor.SignDataResult.UserDenied
    }

    private class FakePacketChannel(
        requests: List<ByteArray>,
    ) : SshAgentPacketChannel {
        private val requests = ArrayDeque(requests)

        val responses = mutableListOf<ByteArray>()

        override fun readPacket(): ByteArray? = requests.removeFirstOrNull()

        override fun writePacket(
            packet: ByteArray,
        ) {
            responses += packet
        }
    }
}
