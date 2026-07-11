package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcApi
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcPeer
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(TestOnlyUnverifiedAgentIpcApi::class)
class GpgAgentIpcAuthenticationTest {
    private val authToken = ByteArray(32) { it.toByte() }

    @Test
    fun `authentication requires exact current protocol revision`() {
        val server = createServer()
        val current = server.handleAuthenticate(
            requestId = 1L,
            request = GpgAgentMessages.AuthenticateRequest(
                token = authToken.copyOf(),
                protocolRevision = GpgAgentMessages.PROTOCOL_REVISION,
            ),
        )
        val missing = server.handleAuthenticate(
            requestId = 2L,
            request = GpgAgentMessages.AuthenticateRequest(
                token = authToken.copyOf(),
            ),
        )
        val future = server.handleAuthenticate(
            requestId = 3L,
            request = GpgAgentMessages.AuthenticateRequest(
                token = authToken.copyOf(),
                protocolRevision = GpgAgentMessages.PROTOCOL_REVISION + 1,
            ),
        )

        assertTrue(current.authenticate?.success == true)
        assertFalse(missing.authenticate?.success == true)
        assertFalse(future.authenticate?.success == true)
        listOf(current, missing, future).forEach { response ->
            assertEquals(
                GpgAgentMessages.PROTOCOL_REVISION,
                response.authenticate?.protocolRevision,
            )
        }
    }

    @Test
    fun `matching revision does not weaken token authentication`() {
        val response = createServer().handleAuthenticate(
            requestId = 4L,
            request = GpgAgentMessages.AuthenticateRequest(
                token = ByteArray(32) { 0x7f },
                protocolRevision = GpgAgentMessages.PROTOCOL_REVISION,
            ),
        )

        assertFalse(response.authenticate?.success == true)
        assertEquals(
            GpgAgentMessages.PROTOCOL_REVISION,
            response.authenticate?.protocolRevision,
        )
    }

    private fun createServer() = GpgAgentIpcServer(
        logRepository = object : LogRepository {
            override suspend fun add(
                tag: String,
                message: String,
                level: LogLevel,
            ) = Unit
        },
        authToken = authToken,
        scope = CoroutineScope(Dispatchers.Unconfined),
        requestProcessor = NoOpRequestProcessor,
        testOnlyUnverifiedPeer = TestOnlyUnverifiedAgentIpcPeer,
    )

    private object NoOpRequestProcessor : GpgAgentRequestProcessor {
        override suspend fun listKeys(
            caller: GpgAgentMessages.CallerIdentity?,
        ) = GpgAgentRequestProcessor.ListKeysResult.VaultLocked

        override suspend fun signHash(
            request: GpgAgentMessages.SignHashRequest,
        ) = GpgAgentRequestProcessor.GpgAgentOperationResult.Unsupported

        override suspend fun decrypt(
            request: GpgAgentMessages.PkdecryptRequest,
        ) = GpgAgentRequestProcessor.GpgAgentOperationResult.Unsupported
    }
}
