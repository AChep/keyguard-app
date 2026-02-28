package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for request processing logic in [SshAgentIpcServer].
 *
 * These tests exercise the routing, authentication, and error-handling
 * logic without requiring a real vault. Vault-dependent operations
 * (listKeys, signData) return "vault locked" when the session stub
 * returns null.
 */
class SshAgentRequestProcessingTest {
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

    /** A locked vault — `valueOrNull` returns null. */
    private val lockedVaultSession = object : GetVaultSession {
        override val valueOrNull: MasterSession? = null
        override fun invoke(): Flow<MasterSession> = flowOf()
    }

    private val sshAgentFilter = object : GetSshAgentFilter {
        override fun invoke(): Flow<SshAgentFilter> = flowOf(SshAgentFilter())
    }

    private fun createServer(
        vaultSession: GetVaultSession = lockedVaultSession,
        onApprovalRequest: suspend (
            caller: SshAgentMessages.CallerIdentity?,
            keyName: String,
            keyFingerprint: String,
        ) -> Boolean = { _, _, _ -> true },
        onGetListRequest: suspend (caller: SshAgentMessages.CallerIdentity?) -> Boolean = { _ -> false },
    ) = SshAgentIpcServer(
        logRepository = logRepository,
        getVaultSession = vaultSession,
        getSshAgentFilter = sshAgentFilter,
        authToken = authToken,
        scope = CoroutineScope(Dispatchers.Unconfined),
        onApprovalRequest = onApprovalRequest,
        onGetListRequest = onGetListRequest,
    )

    // ================================================================
    // Authentication enforcement
    // ================================================================

    @Test
    fun `processRequest rejects unauthenticated non-authenticate requests`() = runTest {
        val server = createServer()
        val request = SshAgentMessages.IpcRequest(
            id = 1L,
            listKeys = SshAgentMessages.ListKeysRequest(),
        )

        val response = server.processRequest(request, authenticated = false)

        assertEquals(1L, response.id)
        assertNotNull(response.error, "Should return an error")
        assertEquals(
            SshAgentMessages.ErrorCode.NOT_AUTHENTICATED,
            response.error!!.code,
        )
        assertNull(response.listKeys)
    }

    @Test
    fun `processRequest allows authenticate when not authenticated`() = runTest {
        val server = createServer()
        val request = SshAgentMessages.IpcRequest(
            id = 2L,
            authenticate = SshAgentMessages.AuthenticateRequest(token = authToken.copyOf()),
        )

        val response = server.processRequest(request, authenticated = false)

        assertEquals(2L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(response.authenticate!!.success)
        assertNull(response.error)
    }

    @Test
    fun `processRequest rejects unknown request type`() = runTest {
        val server = createServer()
        // Request with no variant set.
        val request = SshAgentMessages.IpcRequest(id = 3L)

        val response = server.processRequest(request, authenticated = true)

        assertEquals(3L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.UNSPECIFIED, response.error!!.code)
    }

    @Test
    fun `processRequest rejects malformed request with multiple variants`() = runTest {
        val server = createServer()
        val request = SshAgentMessages.IpcRequest(
            id = 4L,
            authenticate = SshAgentMessages.AuthenticateRequest(token = authToken.copyOf()),
            listKeys = SshAgentMessages.ListKeysRequest(),
        )

        val response = server.processRequest(request, authenticated = false)

        assertEquals(4L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.UNSPECIFIED, response.error!!.code)
        assertNull(response.authenticate)
        assertNull(response.listKeys)
        assertNull(response.signData)
    }

    @Test
    fun `processRequest with no variant is not authenticated when session is unauthenticated`() = runTest {
        val server = createServer()
        val request = SshAgentMessages.IpcRequest(id = 5L)

        val response = server.processRequest(request, authenticated = false)

        assertEquals(5L, response.id)
        assertNotNull(response.error)
        assertEquals(
            SshAgentMessages.ErrorCode.NOT_AUTHENTICATED,
            response.error!!.code,
        )
    }

    // ================================================================
    // handleAuthenticate
    // ================================================================

    @Test
    fun `handleAuthenticate succeeds with correct token`() {
        val server = createServer()
        val req = SshAgentMessages.AuthenticateRequest(token = authToken.copyOf())

        val response = server.handleAuthenticate(requestId = 10L, req = req)

        assertEquals(10L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(response.authenticate!!.success)
    }

    @Test
    fun `handleAuthenticate fails with wrong token`() {
        val server = createServer()
        val wrongToken = ByteArray(32) { 0xFF.toByte() }
        val req = SshAgentMessages.AuthenticateRequest(token = wrongToken)

        val response = server.handleAuthenticate(requestId = 11L, req = req)

        assertEquals(11L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(!response.authenticate!!.success)
    }

    @Test
    fun `handleAuthenticate fails with empty token`() {
        val server = createServer()
        val req = SshAgentMessages.AuthenticateRequest(token = byteArrayOf())

        val response = server.handleAuthenticate(requestId = 12L, req = req)

        assertEquals(12L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(!response.authenticate!!.success)
    }

    // ================================================================
    // handleListKeys with locked vault
    // ================================================================

    @Test
    fun `handleListKeys returns vault locked when vault is locked`() = runTest {
        var getListPromptCount = 0
        val server = createServer(
            onGetListRequest = {
                getListPromptCount++
                false
            },
        )

        val response = server.handleListKeys(
            requestId = 20L,
            req = SshAgentMessages.ListKeysRequest(),
        )

        assertEquals(20L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, response.error!!.code)
        assertNull(response.listKeys)
        assertEquals(1, getListPromptCount)
    }

    // ================================================================
    // handleSignData with locked vault
    // ================================================================

    @Test
    fun `handleSignData returns vault locked when vault is locked`() = runTest {
        val server = createServer()
        val req = SshAgentMessages.SignDataRequest(
            publicKey = "ssh-ed25519 AAAA...",
            data = byteArrayOf(1, 2, 3),
            flags = 0,
        )

        val response = server.handleSignData(requestId = 30L, req = req)

        assertEquals(30L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, response.error!!.code)
        assertNull(response.signData)
    }

    @Test
    fun `handleSignData returns user denied when locked vault sign approval is denied`() = runTest {
        var approvalPromptCount = 0
        val server = createServer(
            onApprovalRequest = { _, _, _ ->
                approvalPromptCount++
                false
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = "ssh-ed25519 AAAA...",
            data = byteArrayOf(1, 2, 3),
            flags = 0,
        )

        val response = server.handleSignData(requestId = 31L, req = req)

        assertEquals(31L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.USER_DENIED, response.error!!.code)
        assertNull(response.signData)
        assertEquals(1, approvalPromptCount)
    }

    @Test
    fun `handleSignData returns vault locked when approval is allowed but vault stays locked`() = runTest {
        var approvalPromptCount = 0
        val server = createServer(
            onApprovalRequest = { _, _, _ ->
                approvalPromptCount++
                true
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = "ssh-ed25519 AAAA...",
            data = byteArrayOf(1, 2, 3),
            flags = 0,
        )

        val response = server.handleSignData(requestId = 32L, req = req)

        assertEquals(32L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, response.error!!.code)
        assertNull(response.signData)
        assertEquals(1, approvalPromptCount)
    }

    // ================================================================
    // Response ID propagation
    // ================================================================

    @Test
    fun `response id matches request id across all handlers`() = runTest {
        val server = createServer()

        // Auth request
        val authResp = server.processRequest(
            SshAgentMessages.IpcRequest(
                id = 100L,
                authenticate = SshAgentMessages.AuthenticateRequest(token = authToken.copyOf()),
            ),
            authenticated = false,
        )
        assertEquals(100L, authResp.id)

        // List keys request (will return vault locked)
        val listResp = server.processRequest(
            SshAgentMessages.IpcRequest(id = 200L, listKeys = SshAgentMessages.ListKeysRequest()),
            authenticated = true,
        )
        assertEquals(200L, listResp.id)

        // Sign data request (will return vault locked)
        val signResp = server.processRequest(
            SshAgentMessages.IpcRequest(
                id = 300L,
                signData = SshAgentMessages.SignDataRequest(
                    publicKey = "ssh-ed25519 AAAA...",
                    data = byteArrayOf(1),
                    flags = 0,
                ),
            ),
            authenticated = true,
        )
        assertEquals(300L, signResp.id)
    }
}
