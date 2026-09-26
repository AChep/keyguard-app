package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AddSshUsageHistoryRequest
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigState
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.ApprovalCacheInvalidation
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcApi
import com.artemchep.keyguard.common.service.agent.TestOnlyUnverifiedAgentIpcPeer
import com.artemchep.keyguard.common.service.agent.finishAfterBlockedAgentRead
import com.artemchep.keyguard.common.service.agent.finishAfterBlockedApprovalCacheAccess
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.pendinghistory.RecordingPendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.vault.testDomainSessionAccess
import com.artemchep.keyguard.common.service.vault.testVaultSession
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests for request processing logic in [SshAgentIpcServer].
 *
 * These tests exercise the routing, authentication, and error-handling
 * logic without requiring a real vault. Vault-dependent operations
 * (listKeys, signData) return "vault locked" when the session stub
 * returns null.
 */
@OptIn(TestOnlyUnverifiedAgentIpcApi::class, ExperimentalCoroutinesApi::class)
class SshAgentRequestProcessingTest {
    private val authToken = ByteArray(32) { it.toByte() }
    private val loggedMessages = mutableListOf<String>()

    private val logRepository = object : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            loggedMessages += message
        }

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) {
            loggedMessages += message
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
        approvalWindow: Duration = Duration.ZERO,
        approvalWindowFlow: Flow<Duration> = flowOf(approvalWindow),
        approvalCachePolicyFlow: Flow<AgentApprovalCachePolicy> =
            flowOf(AgentApprovalCachePolicy.Default),
        approvalCacheConfigState: AgentApprovalCacheConfigState<AgentApprovalCachePolicy>? = null,
        sessionId: String = "test-session",
        onApprovalRequest: suspend (SshAgentApprovalPrompt) -> Boolean = { true },
        sshAgentPublicKeyRepository: SshAgentPublicKeyRepository = SshAgentPublicKeyRepositoryEmpty,
        onGetListRequest: suspend (caller: SshAgentMessages.CallerIdentity?) -> Boolean = { _ -> false },
        agentFilter: GetSshAgentFilter = sshAgentFilter,
        pendingUsageHistoryQueue: PendingUsageHistoryQueue? = null,
    ) = SshAgentIpcServer(
        logRepository = logRepository,
        getVaultSession = vaultSession,
        sessionAccess = testDomainSessionAccess(),
        getSshAgentApprovalWindow = object : GetSshAgentApprovalWindow {
            override fun invoke(): Flow<Duration> = approvalCacheConfigState
                ?.approvalWindow()
                ?: approvalWindowFlow
        },
        getSshAgentApprovalCachePolicy = object : GetSshAgentApprovalCachePolicy {
            override val approvalCacheConfig = approvalCacheConfigState

            override fun invoke(): Flow<AgentApprovalCachePolicy> = approvalCacheConfigState
                ?.cachePolicy()
                ?: approvalCachePolicyFlow
        },
        getSshAgentFilter = agentFilter,
        sshAgentPublicKeyRepository = sshAgentPublicKeyRepository,
        authToken = authToken,
        scope = CoroutineScope(Dispatchers.Unconfined),
        testOnlyUnverifiedPeer = TestOnlyUnverifiedAgentIpcPeer,
        sessionId = sessionId,
        onApprovalRequest = onApprovalRequest,
        onGetListRequest = onGetListRequest,
        pendingUsageHistoryQueue = pendingUsageHistoryQueue,
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
            authenticate = SshAgentMessages.AuthenticateRequest(
                token = authToken.copyOf(),
                protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
            ),
        )

        val response = server.processRequest(request, authenticated = false)

        assertEquals(2L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(response.authenticate!!.success)
        assertEquals(
            SshAgentMessages.PROTOCOL_REVISION,
            response.authenticate?.protocolRevision,
        )
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
        val req = SshAgentMessages.AuthenticateRequest(
            token = authToken.copyOf(),
            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
        )

        val response = server.handleAuthenticate(requestId = 10L, req = req)

        assertEquals(10L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(response.authenticate!!.success)
        assertEquals(
            SshAgentMessages.PROTOCOL_REVISION,
            response.authenticate?.protocolRevision,
        )
    }

    @Test
    fun `handleAuthenticate rejects missing and mismatched protocol revisions`() {
        val server = createServer()
        val missingRevision = server.handleAuthenticate(
            requestId = 11L,
            req = SshAgentMessages.AuthenticateRequest(token = authToken.copyOf()),
        )
        val futureRevision = server.handleAuthenticate(
            requestId = 12L,
            req = SshAgentMessages.AuthenticateRequest(
                token = authToken.copyOf(),
                protocolRevision = SshAgentMessages.PROTOCOL_REVISION + 1,
            ),
        )

        assertTrue(missingRevision.authenticate?.success == false)
        assertTrue(futureRevision.authenticate?.success == false)
        assertEquals(
            SshAgentMessages.PROTOCOL_REVISION,
            missingRevision.authenticate?.protocolRevision,
        )
        assertEquals(
            SshAgentMessages.PROTOCOL_REVISION,
            futureRevision.authenticate?.protocolRevision,
        )
    }

    @Test
    fun `handleAuthenticate fails with wrong token`() {
        val server = createServer()
        val wrongToken = ByteArray(32) { 0xFF.toByte() }
        val req = SshAgentMessages.AuthenticateRequest(
            token = wrongToken,
            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
        )

        val response = server.handleAuthenticate(requestId = 11L, req = req)

        assertEquals(11L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(!response.authenticate!!.success)
    }

    @Test
    fun `handleAuthenticate fails with empty token`() {
        val server = createServer()
        val req = SshAgentMessages.AuthenticateRequest(
            token = byteArrayOf(),
            protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
        )

        val response = server.handleAuthenticate(requestId = 12L, req = req)

        assertEquals(12L, response.id)
        assertNotNull(response.authenticate)
        assertTrue(!response.authenticate!!.success)
    }

    // ================================================================
    // handleListKeys with locked vault
    // ================================================================

    @Test
    fun `handleListKeys returns empty success without prompt when locked cache is empty`() = runTest {
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
        assertNull(response.error)
        assertEquals(emptyList(), response.listKeys?.keys)
        assertEquals(0, getListPromptCount)
    }

    @Test
    fun `handleListKeys returns exposed cached keys without unlock prompt when vault is locked`() = runTest {
        var getListPromptCount = 0
        val publicKey = buildOpenSshPublicKey("ssh-ed25519")
        val server = createServer(
            sshAgentPublicKeyRepository = FakeSshAgentPublicKeyRepository(
                listOf(
                    SshAgentPublicKeyRow(
                        accountId = "account",
                        cipherId = "cipher",
                        canSign = true,
                        publicKeyBlobSha256 = "cached-hash",
                        publicKey = publicKey,
                        keyType = "ssh-ed25519",
                        fingerprint = "SHA256:cached",
                        name = "Cached key",
                    ),
                ),
            ),
            onGetListRequest = {
                getListPromptCount++
                false
            },
        )

        val response = server.handleListKeys(
            requestId = 25L,
            req = SshAgentMessages.ListKeysRequest(),
        )

        val payload = requireNotNull(response.listKeys)
        val key = payload.keys.single()
        assertEquals(25L, response.id)
        assertNull(response.error)
        assertEquals("Cached key", key.name)
        assertEquals(publicKey, key.publicKey)
        assertEquals("ssh-ed25519", key.keyType)
        assertEquals("SHA256:cached", key.fingerprint)
        assertEquals(0, getListPromptCount)
    }

    @Test
    fun `handleListKeys returns keys without unlock prompt when vault is already unlocked`() = runTest {
        var getListPromptCount = 0
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Primary key",
                        publicKey = "ssh-ed25519 AAAA... primary@example",
                        fingerprint = "SHA256:primary",
                    ),
                ),
            ),
            onGetListRequest = {
                getListPromptCount++
                true
            },
        )

        val response = server.handleListKeys(
            requestId = 21L,
            req = SshAgentMessages.ListKeysRequest(),
        )

        val payload = requireNotNull(response.listKeys)
        assertEquals(21L, response.id)
        assertNull(response.error)
        assertEquals(1, payload.keys.size)
        assertEquals("Primary key", payload.keys.single().name)
        assertEquals(0, getListPromptCount)
    }

    @Test
    fun `handleListKeys prefers unlocked vault keys over exposed cache`() = runTest {
        val cachedPublicKey = buildOpenSshPublicKey("ssh-ed25519")
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Vault key",
                        publicKey = "ssh-ed25519 AAAA... vault@example",
                        fingerprint = "SHA256:vault",
                    ),
                ),
            ),
            sshAgentPublicKeyRepository = FakeSshAgentPublicKeyRepository(
                listOf(
                    SshAgentPublicKeyRow(
                        accountId = "account",
                        cipherId = "cipher",
                        canSign = true,
                        publicKeyBlobSha256 = "cached-hash",
                        publicKey = cachedPublicKey,
                        keyType = "ssh-ed25519",
                        fingerprint = "SHA256:cached",
                        name = "Cached key",
                    ),
                ),
            ),
        )

        val response = server.handleListKeys(
            requestId = 26L,
            req = SshAgentMessages.ListKeysRequest(),
        )

        val payload = requireNotNull(response.listKeys)
        assertEquals(26L, response.id)
        assertNull(response.error)
        assertEquals(listOf("Vault key"), payload.keys.map { it.name })
    }

    @Test
    fun `handleListKeys omits trashed ssh keys`() = runTest {
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Active key",
                        publicKey = "ssh-ed25519 AAAA... active@example",
                        fingerprint = "SHA256:active",
                    ),
                    createSshSecret(
                        name = "Trashed key",
                        publicKey = "ssh-ed25519 AAAA... trashed@example",
                        fingerprint = "SHA256:trashed",
                        deletedDate = Instant.parse("2024-02-01T00:00:00Z"),
                    ),
                ),
            ),
        )

        val response = server.handleListKeys(
            requestId = 22L,
            req = SshAgentMessages.ListKeysRequest(),
        )

        val payload = requireNotNull(response.listKeys)
        assertEquals(22L, response.id)
        assertNull(response.error)
        assertEquals(listOf("Active key"), payload.keys.map { it.name })
    }

    @Test
    fun `handleListKeys records list request in usage history`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val caller = SshAgentMessages.CallerIdentity(appName = "Termux")
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(
                    history,
                    createSshSecret(
                        name = "Primary key",
                        publicKey = "ssh-ed25519 AAAA... primary@example",
                        fingerprint = "SHA256:primary",
                    ),
                    createSshSecret(
                        name = "Secondary key",
                        publicKey = "ssh-ed25519 AAAA... secondary@example",
                        fingerprint = "SHA256:secondary",
                    ),
                ),
            ),
            sessionId = "session-list",
        )

        val response = server.handleListKeys(
            requestId = 23L,
            req = SshAgentMessages.ListKeysRequest(caller = caller),
        )

        assertNull(response.error)
        assertEquals(2, response.listKeys?.keys?.size)
        assertEquals(1, history.size)
        val event = history.single()
        assertNull(event.cipherId)
        assertEquals("session-list", event.sessionId)
        assertTrue(event.caller?.contains("Termux") == true)
        assertEquals(SshUsageHistoryRequestType.AGENT_LIST_KEYS, event.request)
        assertEquals(SshUsageHistoryResponseType.SUCCESS, event.response)
        assertNull(event.fingerprint)
    }

    @Test
    fun `handleListKeys records empty successful list request in usage history`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(history),
            ),
            sessionId = "session-empty-list",
        )

        val response = server.handleListKeys(
            requestId = 24L,
            req = SshAgentMessages.ListKeysRequest(),
        )

        assertNull(response.error)
        assertEquals(0, response.listKeys?.keys?.size)
        assertEquals(1, history.size)
        val event = history.single()
        assertNull(event.cipherId)
        assertEquals("session-empty-list", event.sessionId)
        assertNull(event.caller)
        assertEquals(SshUsageHistoryRequestType.AGENT_LIST_KEYS, event.request)
        assertEquals(SshUsageHistoryResponseType.SUCCESS, event.response)
        assertNull(event.fingerprint)
    }

    // ================================================================
    // handleSignData with locked vault
    // ================================================================

    @Test
    fun `handleSignData returns vault locked when locked vault unlock is unavailable`() = runTest {
        val server = createServer()
        val req = SshAgentMessages.SignDataRequest(
            publicKey = buildOpenSshPublicKey("ssh-ed25519"),
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
    fun `handleSignData returns user denied when locked vault approval is denied`() = runTest {
        var approvalPromptCount = 0
        var unlockPromptCount = 0
        val server = createServer(
            onApprovalRequest = {
                approvalPromptCount++
                false
            },
            onGetListRequest = {
                unlockPromptCount++
                false
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = buildOpenSshPublicKey("ssh-ed25519"),
            data = byteArrayOf(1, 2, 3),
            flags = 0,
        )

        val response = server.handleSignData(requestId = 31L, req = req)

        assertEquals(31L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.USER_DENIED, response.error!!.code)
        assertNull(response.signData)
        assertEquals(1, approvalPromptCount)
        assertEquals(0, unlockPromptCount)
    }

    @Test
    fun `handleSignData returns vault locked when approval succeeds but vault stays locked`() = runTest {
        var approvalPromptCount = 0
        var unlockPromptCount = 0
        val server = createServer(
            onApprovalRequest = {
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
        assertEquals(0, unlockPromptCount)
    }

    @Test
    fun `handleSignData uses cached key label while locked but still requires vault`() = runTest {
        var approvalPromptCount = 0
        var approvalKeyName: String? = null
        var approvalKeyFingerprint: String? = null
        val publicKey = buildOpenSshPublicKey("ssh-ed25519")
        val server = createServer(
            sshAgentPublicKeyRepository = FakeSshAgentPublicKeyRepository(
                listOf(
                    SshAgentPublicKeyRow(
                        accountId = "account",
                        cipherId = "cipher",
                        canSign = true,
                        publicKeyBlobSha256 = "cached-hash",
                        publicKey = publicKey,
                        keyType = "ssh-ed25519",
                        fingerprint = "SHA256:cached",
                        name = "Cached key",
                    ),
                ),
            ),
            onApprovalRequest = { prompt ->
                approvalPromptCount++
                approvalKeyName = prompt.keyName
                approvalKeyFingerprint = prompt.keyFingerprint
                true
            },
        )

        val response = server.handleSignData(
            requestId = 47L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = "$publicKey requester-comment",
                data = byteArrayOf(1, 2, 3),
                flags = 0,
            ),
        )

        assertEquals(47L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, response.error!!.code)
        assertEquals(1, approvalPromptCount)
        assertEquals("Cached key", approvalKeyName)
        assertEquals("SHA256:cached", approvalKeyFingerprint)
    }

    @Test
    fun `handleSignData uses fingerprint fallback when cached key name is absent`() = runTest {
        var approvalKeyName: String? = null
        val publicKey = buildOpenSshPublicKey("ssh-ed25519")
        val server = createServer(
            sshAgentPublicKeyRepository = FakeSshAgentPublicKeyRepository(
                listOf(
                    SshAgentPublicKeyRow(
                        accountId = "account",
                        cipherId = "cipher",
                        canSign = true,
                        publicKeyBlobSha256 = "cached-hash",
                        publicKey = publicKey,
                        keyType = "ssh-ed25519",
                        fingerprint = "SHA256:fallback",
                        name = null,
                    ),
                ),
            ),
            onApprovalRequest = { prompt ->
                approvalKeyName = prompt.keyName
                true
            },
        )

        val response = server.handleSignData(
            requestId = 48L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = publicKey,
                data = byteArrayOf(1, 2, 3),
                flags = 0,
            ),
        )

        assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, response.error?.code)
        assertEquals("SHA256:fallback", approvalKeyName)
    }

    @Test
    fun `handleSignData still requires approval when vault is unlocked`() = runTest {
        var approvalPromptCount = 0
        var unlockPromptCount = 0
        val publicKeyBlob = buildOpenSshPublicKeyBlob("ssh-ed25519")
        val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicKeyBlob)}"
        val unlockedSession = MutableVaultSession(
            createUnlockedSession(
                createSshSecret(
                    name = "Signer",
                    publicKey = "$publicKey signer@example",
                    fingerprint = "SHA256:signer",
                    privateKey = "private-key-placeholder",
                ),
            ),
        )
        val server = createServer(
            vaultSession = unlockedSession,
            onApprovalRequest = {
                approvalPromptCount++
                false
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0,
        )

        val response = server.handleSignData(requestId = 33L, req = req)

        assertEquals(33L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.USER_DENIED, response.error!!.code)
        assertNull(response.signData)
        assertEquals(0, unlockPromptCount)
        assertEquals(1, approvalPromptCount)
        assertTrue("User denied the signing request" in loggedMessages)
        assertTrue(loggedMessages.none { it.contains("Signer") })
    }

    @Test
    fun `handleSignData returns key not found for trashed ssh key without approval`() = runTest {
        var approvalPromptCount = 0
        val publicKeyBlob = buildOpenSshPublicKeyBlob("ssh-ed25519")
        val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicKeyBlob)}"
        val unlockedSession = MutableVaultSession(
            createUnlockedSession(
                createSshSecret(
                    name = "Trashed signer",
                    publicKey = "$publicKey signer@example",
                    fingerprint = "SHA256:trashed-signer",
                    privateKey = "private-key-placeholder",
                    deletedDate = Instant.parse("2024-02-01T00:00:00Z"),
                ),
            ),
        )
        val server = createServer(
            vaultSession = unlockedSession,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0,
        )

        val response = server.handleSignData(requestId = 35L, req = req)

        assertEquals(35L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.KEY_NOT_FOUND, response.error!!.code)
        assertNull(response.signData)
        assertEquals(0, approvalPromptCount)
    }

    @Test
    fun `handleSignData does not ask twice after approval unlocks the vault`() = runTest {
        var approvalPromptCount = 0
        val publicKeyBlob = buildOpenSshPublicKeyBlob("ssh-ed25519")
        val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicKeyBlob)}"
        val unlockedSession = createUnlockedSession(
            createSshSecret(
                name = "Signer",
                publicKey = "$publicKey signer@example",
                fingerprint = "SHA256:signer",
                privateKey = "private-key-placeholder",
            ),
        )
        val vaultSession = MutableVaultSession()
        val server = createServer(
            vaultSession = vaultSession,
            onApprovalRequest = {
                approvalPromptCount++
                if (approvalPromptCount == 1) {
                    vaultSession.valueOrNull = unlockedSession
                    true
                } else {
                    false
                }
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0,
        )

        val response = server.handleSignData(requestId = 34L, req = req)

        assertEquals(34L, response.id)
        assertNotNull(response.error)
        assertEquals(SshAgentMessages.ErrorCode.UNSPECIFIED, response.error!!.code)
        assertEquals(1, approvalPromptCount)
    }

    @Test
    fun `handleSignData records user denied after matching ssh key`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val publicKeyBlob = buildOpenSshPublicKeyBlob("ssh-ed25519")
        val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicKeyBlob)}"
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(
                    history,
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                    ),
                ),
            ),
            onApprovalRequest = { false },
        )

        val response = server.handleSignData(
            requestId = 36L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = publicKey,
                data = byteArrayOf(1, 2, 3),
                flags = 0,
            ),
        )

        assertEquals(SshAgentMessages.ErrorCode.USER_DENIED, response.error?.code)
        val event = history.single()
        assertEquals("signer", event.cipherId)
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA, event.request)
        assertEquals(SshUsageHistoryResponseType.USER_DENIED, event.response)
    }

    @Test
    fun `handleSignData records key not found for unmatched public key`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        var approvalPromptCount = 0
        val storedPublicKey = buildOpenSshPublicKey("ssh-ed25519")
        val requestedPublicKey = buildOpenSshPublicKey("ssh-rsa")
        val caller = SshAgentMessages.CallerIdentity(appName = "Terminal")
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(
                    history,
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$storedPublicKey signer@example",
                        fingerprint = "SHA256:signer",
                    ),
                ),
            ),
            sessionId = "session-missing-key",
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )

        val response = server.handleSignData(
            requestId = 40L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = requestedPublicKey,
                data = byteArrayOf(1, 2, 3),
                flags = 0,
                caller = caller,
            ),
        )

        assertEquals(SshAgentMessages.ErrorCode.KEY_NOT_FOUND, response.error?.code)
        assertEquals(0, approvalPromptCount)
        val event = history.single()
        assertNull(event.cipherId)
        assertEquals("session-missing-key", event.sessionId)
        assertTrue(event.caller?.contains("Terminal") == true)
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA, event.request)
        assertEquals(SshUsageHistoryResponseType.KEY_NOT_FOUND, event.response)
        assertNull(event.fingerprint)
    }

    @Test
    fun `handleSignData records missing private key after matching ssh key`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val publicKeyBlob = buildOpenSshPublicKeyBlob("ssh-ed25519")
        val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicKeyBlob)}"
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(
                    history,
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = "",
                    ),
                ),
            ),
        )

        val response = server.handleSignData(
            requestId = 37L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = publicKey,
                data = byteArrayOf(1, 2, 3),
                flags = 0,
            ),
        )

        assertEquals(SshAgentMessages.ErrorCode.KEY_NOT_FOUND, response.error?.code)
        val event = history.single()
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA, event.request)
        assertEquals(SshUsageHistoryResponseType.KEY_NOT_FOUND, event.response)
    }

    @Test
    fun `handleSignData records signing failure after matching ssh key`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val publicKeyBlob = buildOpenSshPublicKeyBlob("ssh-ed25519")
        val publicKey = "ssh-ed25519 ${Base64.getEncoder().encodeToString(publicKeyBlob)}"
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(
                    history,
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = "not a valid PEM key",
                    ),
                ),
            ),
            onApprovalRequest = { true },
        )

        val response = server.handleSignData(
            requestId = 38L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = publicKey,
                data = byteArrayOf(1, 2, 3),
                flags = 0,
            ),
        )

        assertEquals(SshAgentMessages.ErrorCode.UNSPECIFIED, response.error?.code)
        val event = history.single()
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA, event.request)
        assertEquals(SshUsageHistoryResponseType.FAILURE, event.response)
    }

    @Test
    fun `handleSignData rejects an approved public key backed by another private identity`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val privateIdentity = generateRsaSshKeyPair()
        val approvedIdentity = generateRsaSshKeyPair()
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(
                    history,
                    createSshSecret(
                        name = "Mismatched signer",
                        publicKey = approvedIdentity.publicKeyOpenSsh,
                        fingerprint = "SHA256:approved",
                        privateKey = privateIdentity.privateKeyPem,
                    ),
                ),
            ),
            onApprovalRequest = { true },
        )

        val response = server.handleSignData(
            requestId = 58L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = approvedIdentity.publicKeyOpenSsh,
                data = byteArrayOf(1, 2, 3),
                flags = 0x02,
            ),
        )

        assertEquals(SshAgentMessages.ErrorCode.UNSPECIFIED, response.error?.code)
        assertNull(response.signData)
        val event = history.single()
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA, event.request)
        assertEquals(SshUsageHistoryResponseType.FAILURE, event.response)
    }

    @Test
    fun `handleSignData records successful signing after matching ssh key`() = runTest {
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSessionWithHistory(
                    history,
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            sessionId = "session-sign",
            onApprovalRequest = { true },
        )

        val response = server.handleSignData(
            requestId = 39L,
            req = SshAgentMessages.SignDataRequest(
                publicKey = publicKey,
                data = byteArrayOf(1, 2, 3),
                flags = 0x02,
            ),
        )

        assertNull(response.error)
        assertNotNull(response.signData)
        val event = history.single()
        assertEquals("session-sign", event.sessionId)
        assertEquals("signer", event.cipherId)
        assertEquals(SshUsageHistoryRequestType.AGENT_SIGN_DATA, event.request)
        assertEquals(SshUsageHistoryResponseType.SUCCESS, event.response)
        assertEquals("SHA256:signer", event.fingerprint)
    }

    @Test
    fun `handleSignData remembers approval within configured window`() = runTest {
        var approvalPromptCount = 0
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindow = 5.minutes,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = cacheableCaller(),
        )

        val firstResponse = server.handleSignData(requestId = 41L, req = req)
        val secondResponse = server.handleSignData(requestId = 42L, req = req)

        assertNull(firstResponse.error)
        assertNull(secondResponse.error)
        assertEquals(1, approvalPromptCount)
    }

    @Test
    fun `macos terminal approval is reused by new processes with or without an application subject`() = runTest {
        listOf<Byte?>(40, null).forEach { application ->
            assertMacosApprovalCount(
                callers = listOf(
                    macosCaller(connection = 1, application = application),
                    macosCaller(connection = 2, application = application),
                ),
                expected = 1,
            )
        }
    }

    @Test
    fun `macos terminal sessions share only when application scope is selected`() = runTest {
        val callers = listOf(
            macosCaller(connection = 1, terminal = 30),
            macosCaller(connection = 2, terminal = 31),
            macosCaller(connection = 3, terminal = 32, application = 41),
        )
        assertMacosApprovalCount(callers = callers, expected = 3)
        assertMacosApprovalCount(
            policy = AgentApprovalCachePolicy.Application,
            callers = callers,
            expected = 2,
        )
    }

    @Test
    fun `macos subjects cannot widen connection or process approval scopes`() = runTest {
        val callers = listOf(
            macosCaller(connection = 1, process = 11),
            macosCaller(connection = 2, process = 11),
            macosCaller(connection = 3, process = 12),
        )
        assertMacosApprovalCount(
            policy = AgentApprovalCachePolicy.Connection,
            callers = callers,
            expected = 3,
        )
        assertMacosApprovalCount(
            policy = AgentApprovalCachePolicy.Process,
            callers = callers,
            expected = 2,
        )
    }

    @Test
    fun `macos failed joint proof cannot reuse approval through its application label`() = runTest {
        val callers = listOf(
            macosCaller(connection = 1, terminal = null, application = null),
            macosCaller(connection = 2, terminal = null, application = null),
        )
        listOf(AgentApprovalCachePolicy.Default, AgentApprovalCachePolicy.Application).forEach { policy ->
            assertMacosApprovalCount(policy = policy, callers = callers, expected = 2)
        }
    }

    @Test
    fun `macos session approval remains partitioned by SSH authorization context`() = runTest {
        assertMacosApprovalCount(
            callers = listOf(
                macosCaller(connection = 1, context = 50),
                macosCaller(connection = 2, context = 50),
                macosCaller(connection = 3, context = 51),
            ),
            expected = 2,
        )
    }

    private suspend fun assertMacosApprovalCount(
        callers: List<SshAgentMessages.CallerIdentity>,
        expected: Int,
        policy: AgentApprovalCachePolicy = AgentApprovalCachePolicy.Default,
    ) {
        var approvalPromptCount = 0
        val keyPair = signerKeyPair
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = keyPair.publicKeyOpenSsh,
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindow = 5.minutes,
            approvalCachePolicyFlow = flowOf(policy),
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        callers.forEachIndexed { index, caller ->
            val response = server.handleSignData(
                requestId = index.toLong(),
                req = SshAgentMessages.SignDataRequest(
                    publicKey = keyPair.publicKeyOpenSsh,
                    data = byteArrayOf(1, 2, 3),
                    flags = 0x02,
                    caller = caller,
                ),
            )
            assertNull(response.error, "Request $index with $policy")
        }
        assertEquals(expected, approvalPromptCount, "Approval count with $policy")
    }

    @Test
    fun `handleSignData never remembers legacy callers with the same app name`() = runTest {
        var approvalPromptCount = 0
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindow = 5.minutes,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        val request = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = SshAgentMessages.CallerIdentity(appName = "Terminal"),
        )

        val firstResponse = server.handleSignData(requestId = 47L, req = request)
        val secondResponse = server.handleSignData(requestId = 48L, req = request)

        assertNull(firstResponse.error)
        assertNull(secondResponse.error)
        assertEquals(2, approvalPromptCount)
    }

    @Test
    fun `handleSignData separates identical app names with different principals`() = runTest {
        var approvalPromptCount = 0
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindow = 5.minutes,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        fun request(principal: Byte) = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = cacheableCaller(
                fingerprintByte = principal,
                appName = "Terminal",
            ),
        )

        val firstResponse = server.handleSignData(requestId = 49L, req = request(1))
        val secondResponse = server.handleSignData(requestId = 50L, req = request(2))
        val repeatedFirstResponse = server.handleSignData(requestId = 51L, req = request(1))

        assertNull(firstResponse.error)
        assertNull(secondResponse.error)
        assertNull(repeatedFirstResponse.error)
        assertEquals(2, approvalPromptCount)
    }

    @Test
    fun `handleSignData forgets approval when approval window is disabled and re-enabled`() = runTest {
        var approvalPromptCount = 0
        val approvalWindow = MutableStateFlow(5.minutes)
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindowFlow = approvalWindow,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = cacheableCaller(),
        )

        val firstResponse = server.handleSignData(requestId = 43L, req = req)
        approvalWindow.value = Duration.ZERO
        approvalWindow.value = 5.minutes
        val secondResponse = server.handleSignData(requestId = 44L, req = req)

        assertNull(firstResponse.error)
        assertNull(secondResponse.error)
        assertEquals(2, approvalPromptCount)
    }

    @Test
    fun `handleSignData forgets approval when approval window changes`() = runTest {
        var approvalPromptCount = 0
        val approvalWindow = MutableStateFlow(5.minutes)
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindowFlow = approvalWindow,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = cacheableCaller(),
        )

        val firstResponse = server.handleSignData(requestId = 45L, req = req)
        approvalWindow.value = 15.minutes
        val secondResponse = server.handleSignData(requestId = 46L, req = req)

        assertNull(firstResponse.error)
        assertNull(secondResponse.error)
        assertEquals(2, approvalPromptCount)
    }

    @Test
    fun `handleSignData never resurrects approvals across cache policy changes`() = runTest {
        var approvalPromptCount = 0
        val approvalCachePolicy = MutableStateFlow(AgentApprovalCachePolicy.Default)
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindow = 5.minutes,
            approvalCachePolicyFlow = approvalCachePolicy,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        val request = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = cacheableCaller(),
        )

        assertNull(server.handleSignData(requestId = 52L, req = request).error)
        assertNull(server.handleSignData(requestId = 53L, req = request).error)
        approvalCachePolicy.value = AgentApprovalCachePolicy.Application
        assertNull(server.handleSignData(requestId = 54L, req = request).error)
        approvalCachePolicy.value = AgentApprovalCachePolicy.Default
        assertNull(server.handleSignData(requestId = 55L, req = request).error)

        assertEquals(3, approvalPromptCount)
    }

    @Test
    fun `policy change while approval is open cannot resurrect a grant`() = runTest {
        var approvalPromptCount = 0
        val approvalStarted = CompletableDeferred<Unit>()
        val finishFirstApproval = CompletableDeferred<Unit>()
        val approvalCachePolicy = MutableStateFlow(AgentApprovalCachePolicy.Default)
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val server = createServer(
            vaultSession = MutableVaultSession(
                createUnlockedSession(
                    createSshSecret(
                        name = "Signer",
                        publicKey = "$publicKey signer@example",
                        fingerprint = "SHA256:signer",
                        privateKey = keyPair.privateKeyPem,
                    ),
                ),
            ),
            approvalWindow = 5.minutes,
            approvalCachePolicyFlow = approvalCachePolicy,
            onApprovalRequest = {
                approvalPromptCount++
                if (approvalPromptCount == 1) {
                    approvalStarted.complete(Unit)
                    finishFirstApproval.await()
                }
                true
            },
        )
        val request = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = cacheableCaller(),
        )

        val firstResponse = async {
            server.handleSignData(requestId = 56L, req = request)
        }
        approvalStarted.await()

        // Returning to the original value must not make the in-flight access
        // valid again: it belongs to an older policy epoch.
        approvalCachePolicy.value = AgentApprovalCachePolicy.Application
        approvalCachePolicy.value = AgentApprovalCachePolicy.Default
        finishFirstApproval.complete(Unit)

        assertNull(firstResponse.await().error)
        assertNull(server.handleSignData(requestId = 57L, req = request).error)
        assertEquals(2, approvalPromptCount)
    }

    @Test
    fun `handleSignData forgets approval when unlocked vault session changes`() = runTest {
        var approvalPromptCount = 0
        val keyPair = signerKeyPair
        val publicKey = keyPair.publicKeyOpenSsh
        val privateKey = keyPair.privateKeyPem
        val vaultSession = MutableVaultSession(
            createUnlockedSession(
                createSshSecret(
                    name = "Signer",
                    publicKey = "$publicKey signer@example",
                    fingerprint = "SHA256:signer",
                    privateKey = privateKey,
                ),
            ),
        )
        val server = createServer(
            vaultSession = vaultSession,
            approvalWindow = 5.minutes,
            onApprovalRequest = {
                approvalPromptCount++
                true
            },
        )
        val req = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = cacheableCaller(),
        )

        val firstResponse = server.handleSignData(requestId = 43L, req = req)
        vaultSession.valueOrNull = createUnlockedSession(
            createSshSecret(
                name = "Signer",
                publicKey = "$publicKey signer@example",
                fingerprint = "SHA256:signer",
                privateKey = privateKey,
            ),
        )
        val secondResponse = server.handleSignData(requestId = 44L, req = req)

        assertNull(firstResponse.error)
        assertNull(secondResponse.error)
        assertEquals(2, approvalPromptCount)
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
                authenticate = SshAgentMessages.AuthenticateRequest(
                    token = authToken.copyOf(),
                    protocolRevision = SshAgentMessages.PROTOCOL_REVISION,
                ),
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

    @Test
    fun `locking or replacing the vault during approval prevents SSH signing`() = runTest {
        for (replaceSession in listOf(false, true)) {
            val session = MutableVaultSession(createUnlockedSession(createSigningSecret()))
            val response = finishPendingSigning(session) {
                session.valueOrNull = if (replaceSession) createUnlockedSession() else MasterSession.Empty()
            }
            assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, response.error?.code)
            assertNull(response.signData)
        }
    }

    @Test
    fun `key eligibility and cipher identity changes during approval prevent SSH signing`() = runTest {
        for (change in listOf("removed", "deleted", "private removed", "cipher replaced", "account replaced")) {
            val ciphers = MutableStateFlow(listOf(createSigningSecret()))
            val history = mutableListOf<AddSshUsageHistoryRequest>()
            val session = MutableVaultSession(createUnlockedSessionWithHistory(history, ciphers = ciphers))
            val response = finishPendingSigning(session) {
                ciphers.value = ciphers.value.mapNotNull { cipher ->
                    when (change) {
                        "removed" -> null
                        "deleted" -> cipher.copy(deletedDate = Instant.parse("2024-01-02T00:00:00Z"))
                        "private removed" -> cipher.copy(sshKey = cipher.sshKey?.copy(privateKey = null))
                        "cipher replaced" -> cipher.copy(id = "replacement")
                        else -> cipher.copy(accountId = "replacement")
                    }
                }
            }
            assertEquals(SshAgentMessages.ErrorCode.KEY_NOT_FOUND, response.error?.code, change)
            assertNull(response.signData)
            assertEquals(SshUsageHistoryResponseType.KEY_NOT_FOUND, history.single().response)
        }
    }

    @Test
    fun `SSH signing reads the current filter after approval`() = runTest {
        var filter = SshAgentFilter()
        val session = MutableVaultSession(createUnlockedSession(createSigningSecret()))
        val response = finishPendingSigning(
            vaultSession = session,
            agentFilter = object : GetSshAgentFilter {
                override fun invoke(): Flow<SshAgentFilter> = flow { emit(filter) }
            },
        ) {
            filter = onlyOtherCipherFilter()
        }
        assertEquals(SshAgentMessages.ErrorCode.KEY_NOT_FOUND, response.error?.code)
        assertNull(response.signData)
    }

    @Test
    fun `SSH signing uses the refreshed private material after approval`() = runTest {
        val ciphers = MutableStateFlow(listOf(createSigningSecret()))
        val session = MutableVaultSession(createUnlockedSessionWithHistory(null, ciphers = ciphers))
        val response = finishPendingSigning(session) {
            ciphers.value = ciphers.value.map { cipher ->
                cipher.copy(sshKey = cipher.sshKey?.copy(privateKey = "invalid replacement key"))
            }
        }
        assertNotNull(response.error)
        assertNull(response.signData, "The original valid private key must not be used")
    }

    @Test
    fun `reordering duplicate SSH keys retains the approved cipher`() = runTest {
        val original = createSigningSecret()
        val ciphers = MutableStateFlow(listOf(original, original.copy(id = "duplicate")))
        val history = mutableListOf<AddSshUsageHistoryRequest>()
        val session = MutableVaultSession(createUnlockedSessionWithHistory(history, ciphers = ciphers))
        val response = finishPendingSigning(session) { ciphers.value = ciphers.value.reversed() }
        assertNull(response.error)
        assertNotNull(response.signData)
        assertEquals(original.id, history.single().cipherId)
    }

    @Test
    fun `locked-origin SSH signing can unlock with one approval`() = runTest {
        val session = MutableVaultSession(MasterSession.Empty())
        val response = finishPendingSigning(session) {
            session.valueOrNull = createUnlockedSession(createSigningSecret())
        }
        assertNull(response.error)
        assertNotNull(response.signData)
    }

    @Test
    fun `remembered and unlock-origin signing resolve ciphers and filter once`() = runTest {
        val ciphers = MutableStateFlow(listOf(createSigningSecret()))
        var cipherReads = 0
        val cipherFlow = flow {
            cipherReads++
            emit(ciphers.value)
        }
        var filterReads = 0
        val filter = object : GetSshAgentFilter {
            override fun invoke(): Flow<SshAgentFilter> = flow {
                filterReads++
                emit(SshAgentFilter())
            }
        }
        val unlockedSession = createUnlockedSessionWithHistory(null, ciphers = cipherFlow)
        val vault = MutableVaultSession(unlockedSession)
        var approvals = 0
        val server = createServer(
            vaultSession = vault,
            approvalWindow = 5.minutes,
            agentFilter = filter,
            onApprovalRequest = {
                approvals++
                if (vault.valueOrNull !is MasterSession.Key) {
                    vault.valueOrNull = unlockedSession
                }
                true
            },
        )
        val request = SshAgentMessages.SignDataRequest(
            publicKey = signerKeyPair.publicKeyOpenSsh,
            data = "read counts".encodeToByteArray(),
            flags = 0x02,
            caller = cacheableCaller(),
        )

        assertNull(server.handleSignData(requestId = 901L, req = request).error)
        assertEquals(2, cipherReads, "Prompted signing must refresh the cipher")
        assertEquals(2, filterReads, "Prompted signing must refresh the filter")

        cipherReads = 0
        filterReads = 0
        assertNull(server.handleSignData(requestId = 902L, req = request).error)
        assertEquals(1, cipherReads, "Remembered signing resolves the cipher once")
        assertEquals(1, filterReads, "Remembered signing resolves the filter once")
        assertEquals(1, approvals)

        vault.valueOrNull = MasterSession.Empty()
        runCurrent()
        cipherReads = 0
        filterReads = 0
        assertNull(server.handleSignData(requestId = 903L, req = request).error)
        assertEquals(1, cipherReads, "Unlock-origin signing resolves the cipher once")
        assertEquals(1, filterReads, "Unlock-origin signing resolves the filter once")
        assertEquals(2, approvals)
    }

    @Test
    fun `remembered signing reads current eligibility after cache access unblocks`() = runTest {
        for (excludeWithFilter in listOf(false, true)) {
            val ciphers = MutableStateFlow(listOf(createSigningSecret()))
            var cipherReads = 0
            val cipherFlow = flow {
                cipherReads++
                emit(ciphers.value)
            }
            var currentFilter = SshAgentFilter()
            var filterReads = 0
            val filter = object : GetSshAgentFilter {
                override fun invoke(): Flow<SshAgentFilter> = flow {
                    filterReads++
                    emit(currentFilter)
                }
            }
            val config = createApprovalCacheConfig()
            var approvals = 0
            val server = createServer(
                vaultSession = MutableVaultSession(
                    createUnlockedSessionWithHistory(null, ciphers = cipherFlow),
                ),
                approvalCacheConfigState = config,
                agentFilter = filter,
                onApprovalRequest = {
                    approvals++
                    true
                },
            )
            val request = createSigningRequest("cache contention")
            assertNull(server.handleSignData(requestId = 904L, req = request).error)

            cipherReads = 0
            filterReads = 0
            val response = finishAfterBlockedApprovalCacheAccess(
                config = config,
                request = { server.handleSignData(requestId = 905L, req = request) },
            ) {
                assertEquals(0, cipherReads, "Cipher read happened before cache access completed")
                assertEquals(0, filterReads, "Filter read happened before cache access completed")
                if (excludeWithFilter) {
                    currentFilter = onlyOtherCipherFilter()
                } else {
                    ciphers.value = emptyList()
                }
            }
            assertEquals(SshAgentMessages.ErrorCode.KEY_NOT_FOUND, response.error?.code)
            assertNull(response.signData)
            assertEquals(1, approvals, "Remembered request must not prompt again")
            assertEquals(1, cipherReads)
            assertEquals(1, filterReads)
        }
    }

    @Test
    fun `remembered signing stays bound to its session while cache access waits`() = runTest {
        for (replaceSession in listOf(false, true)) {
            val ciphers = MutableStateFlow(listOf(createSigningSecret()))
            var cipherReads = 0
            val cipherFlow = flow {
                cipherReads++
                emit(ciphers.value)
            }
            val history = mutableListOf<AddSshUsageHistoryRequest>()
            val original = createUnlockedSessionWithHistory(history, ciphers = cipherFlow)
            val vault = MutableVaultSession(original)
            val config = createApprovalCacheConfig()
            val pendingHistory = RecordingPendingUsageHistoryQueue()
            var approvals = 0
            val server = createServer(
                vaultSession = vault,
                approvalCacheConfigState = config,
                pendingUsageHistoryQueue = pendingHistory,
                onApprovalRequest = {
                    approvals++
                    true
                },
            )
            val request = createSigningRequest("session contention")
            assertNull(server.handleSignData(requestId = 906L, req = request).error)
            history.clear()
            cipherReads = 0

            val response = finishAfterBlockedApprovalCacheAccess(
                config = config,
                request = { server.handleSignData(requestId = 907L, req = request) },
            ) {
                assertEquals(0, cipherReads, "Cipher read happened before cache access completed")
                vault.valueOrNull = if (replaceSession) {
                    createUnlockedSession(createSigningSecret())
                } else {
                    MasterSession.Empty()
                }
            }
            assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, response.error?.code)
            assertNull(response.signData)
            assertEquals(1, approvals)
            assertEquals(0, cipherReads, "Stale session must be rejected before reading ciphers")
            assertTrue(history.isEmpty())
            assertEquals(SshUsageHistoryResponseType.VAULT_LOCKED.name, pendingHistory.items.single().responseType)
        }
    }

    @Test
    fun `cached signing requires new approval after settings change during eligibility reads`() = runTest {
        for (readFilter in listOf(false, true)) {
            for (change in ApprovalCacheInvalidation.entries) {
                var beforeRead: suspend () -> Unit = {}
                val cipherFlow = flow {
                    if (!readFilter) beforeRead()
                    emit(listOf(createSigningSecret()))
                }
                val filter = object : GetSshAgentFilter {
                    override fun invoke(): Flow<SshAgentFilter> = flow {
                        if (readFilter) beforeRead()
                        emit(SshAgentFilter())
                    }
                }
                val config = createApprovalCacheConfig()
                var approvals = 0
                val server = createServer(
                    vaultSession = MutableVaultSession(createUnlockedSessionWithHistory(null, ciphers = cipherFlow)),
                    approvalCacheConfigState = config,
                    agentFilter = filter,
                    onApprovalRequest = {
                        approvals++
                        true
                    },
                )
                val request = createSigningRequest("approval invalidation")
                assertNull(server.handleSignData(requestId = 910L, req = request).error)
                val result = finishAfterBlockedAgentRead(
                    beforeRead = { beforeRead = it },
                    request = { server.handleSignData(requestId = 911L, req = request) },
                ) { change.apply(config) }

                assertNull(result.error)
                assertNotNull(result.signData)
                assertEquals(2, approvals, "filter=$readFilter, $change")
                assertNull(server.handleSignData(requestId = 912L, req = request).error)
                val expected = if (change == ApprovalCacheInvalidation.Disable) 3 else 2
                assertEquals(expected, approvals, "New approval must use the current policy")
            }
        }
    }

    @Test
    fun `renewed SSH approvals still reject denial and removed keys`() = runTest {
        for (deny in listOf(false, true)) {
            var beforeRead: suspend () -> Unit = {}
            val ciphers = MutableStateFlow(listOf(createSigningSecret()))
            val cipherFlow = flow {
                beforeRead()
                emit(ciphers.value)
            }
            val config = createApprovalCacheConfig()
            var approvals = 0
            val server = createServer(
                vaultSession = MutableVaultSession(createUnlockedSessionWithHistory(null, ciphers = cipherFlow)),
                approvalCacheConfigState = config,
                onApprovalRequest = {
                    approvals++
                    if (approvals > 1) ciphers.value = emptyList()
                    approvals == 1 || !deny
                },
            )
            val request = createSigningRequest("renewed approval")
            assertNull(server.handleSignData(requestId = 920L, req = request).error)
            val result = finishAfterBlockedAgentRead(
                beforeRead = { beforeRead = it },
                request = { server.handleSignData(requestId = 921L, req = request) },
            ) { ApprovalCacheInvalidation.Policy.apply(config) }

            val expected = if (deny) {
                SshAgentMessages.ErrorCode.USER_DENIED
            } else {
                SshAgentMessages.ErrorCode.KEY_NOT_FOUND
            }
            assertEquals(expected, result.error?.code)
            assertNull(result.signData)
            assertEquals(2, approvals)
        }
    }

    private fun createSigningSecret() = createSshSecret(
        name = "Signer",
        publicKey = signerKeyPair.publicKeyOpenSsh,
        fingerprint = "SHA256:signer",
        privateKey = signerKeyPair.privateKeyPem,
    )

    private fun createSigningRequest(data: String) = SshAgentMessages.SignDataRequest(
        publicKey = signerKeyPair.publicKeyOpenSsh,
        data = data.encodeToByteArray(),
        flags = 0x02,
        caller = cacheableCaller(),
    )

    private fun onlyOtherCipherFilter() = SshAgentFilter(
        state = mapOf("cipher" to setOf(DFilter.ById(id = "other", what = DFilter.ById.What.CIPHER))),
    )

    private fun createApprovalCacheConfig() = AgentApprovalCacheConfigState(
        loadApprovalWindow = { Duration.INFINITE },
        loadCachePolicy = { AgentApprovalCachePolicy.Default },
    )

    private suspend fun finishPendingSigning(
        vaultSession: GetVaultSession,
        agentFilter: GetSshAgentFilter = sshAgentFilter,
        change: () -> Unit,
    ): SshAgentMessages.IpcResponse = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var approvals = 0
        val server = createServer(
            vaultSession = vaultSession,
            agentFilter = agentFilter,
            onApprovalRequest = {
                approvals++
                started.complete(Unit)
                finish.await()
                true
            },
        )
        val pending = async {
            server.handleSignData(
                requestId = 900L,
                req = SshAgentMessages.SignDataRequest(
                    publicKey = signerKeyPair.publicKeyOpenSsh,
                    data = "approval race".encodeToByteArray(),
                    flags = 0x02,
                ),
            )
        }
        started.await()
        change()
        finish.complete(Unit)
        pending.await().also { assertEquals(1, approvals) }
    }

    private class MutableVaultSession(
        initialValue: MasterSession? = null,
    ) : GetVaultSession {
        private val state = MutableStateFlow(initialValue)

        override var valueOrNull: MasterSession? = initialValue
            set(value) {
                field = value
                state.value = value
            }

        override fun invoke(): Flow<MasterSession> = state.filterNotNull()
    }

    private class FakeSshAgentPublicKeyRepository(
        initialKeys: List<SshAgentPublicKeyRow> = emptyList(),
    ) : SshAgentPublicKeyRepository {
        private var keys = initialKeys

        override fun get(): IO<List<SshAgentPublicKeyRow>> = {
            keys
        }

        override fun getByPublicKeyBlobSha256(
            publicKeyBlobSha256: String,
        ): IO<List<SshAgentPublicKeyRow>> = {
            keys.filter { it.publicKeyBlobSha256 == publicKeyBlobSha256 }
        }

        override fun getByPublicKey(
            publicKey: String,
        ): IO<List<SshAgentPublicKeyRow>> = {
            keys.filter { key ->
                sshPublicKeysMatch(key.publicKey, publicKey)
            }
        }

        override fun replaceAll(
            keys: List<SshAgentPublicKeyRow>,
        ): IO<Unit> = {
            this.keys = keys
        }

        override fun clear(): IO<Unit> = {
            keys = emptyList()
        }

        override fun clearNames(): IO<Unit> = {
            keys = keys.map { it.copy(name = null) }
        }
    }

    private fun createUnlockedSession(
        vararg secrets: DSecret,
    ): MasterSession.Key = createUnlockedSessionWithHistory(
        null,
        *secrets,
    )

    private fun createUnlockedSessionWithHistory(
        history: MutableList<AddSshUsageHistoryRequest>?,
        vararg secrets: DSecret,
        ciphers: Flow<List<DSecret>> = flowOf(secrets.toList()),
    ): MasterSession.Key = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.LATEST,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        session = testVaultSession {
            scoped<GetCiphers> {
                object : GetCiphers {
                    override fun invoke(): Flow<List<DSecret>> = ciphers
                }
            }
            if (history != null) {
                scoped<AddSshUsageHistory> {
                    object : AddSshUsageHistory {
                        override fun invoke(request: AddSshUsageHistoryRequest): IO<Unit> = {
                            history += request
                        }
                    }
                }
            }
        },
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private fun createSshSecret(
        name: String,
        publicKey: String,
        fingerprint: String,
        privateKey: String = "private-key-placeholder",
        deletedDate: Instant? = null,
    ): DSecret = DSecret(
        id = name.lowercase().replace(' ', '-'),
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
        createdDate = Instant.parse("2024-01-01T00:00:00Z"),
        archivedDate = null,
        deletedDate = deletedDate,
        service = com.artemchep.keyguard.core.store.bitwarden.BitwardenService(),
        name = name,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.SshKey,
        sshKey = DSecret.SshKey(
            privateKey = privateKey,
            publicKey = publicKey,
            fingerprint = fingerprint,
        ),
    )

    private fun cacheableCaller(
        fingerprintByte: Byte = 1,
        appName: String = "Terminal",
    ): SshAgentMessages.CallerIdentity = SshAgentMessages.CallerIdentity(
        uid = 1000,
        gid = 1000,
        processName = "ssh",
        executablePath = "/usr/bin/ssh",
        appName = appName,
        authorization = CallerAuthorization(
            connectionFingerprint = ByteArray(32) { fingerprintByte },
        ),
    )

    private fun macosCaller(
        connection: Byte,
        process: Byte = (connection + 10).toByte(),
        terminal: Byte? = 30,
        application: Byte? = 40,
        context: Byte? = null,
    ) = SshAgentMessages.CallerIdentity(
        appName = "iTerm2",
        authorization = CallerAuthorization(
            connectionFingerprint = ByteArray(32) { connection },
            subjects = buildList {
                add(
                    CallerAuthorizationSubject(
                        kind = AgentCallerAuthorizationSchema.SubjectKind.PROCESS,
                        evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_AUDIT_TOKEN,
                        fingerprint = ByteArray(32) { process },
                    ),
                )
                terminal?.let { fingerprint ->
                    add(
                        CallerAuthorizationSubject(
                            kind = AgentCallerAuthorizationSchema.SubjectKind.TERMINAL_SESSION,
                            evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_TERMINAL_SESSION,
                            fingerprint = ByteArray(32) { fingerprint },
                        ),
                    )
                }
                application?.let { fingerprint ->
                    add(
                        CallerAuthorizationSubject(
                            kind = AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION,
                            evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_CODE_SIGNING,
                            fingerprint = ByteArray(32) { fingerprint },
                        ),
                    )
                }
            },
            authorizationContextFingerprint = context?.let { value -> ByteArray(32) { value } }
                ?: byteArrayOf(),
        ),
    )

    private fun buildOpenSshPublicKey(
        keyType: String,
    ): String {
        val blob = buildOpenSshPublicKeyBlob(keyType)
        return "$keyType ${Base64.getEncoder().encodeToString(blob)}"
    }

    private fun buildOpenSshPublicKeyBlob(
        keyType: String,
    ): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { dataOutput ->
            val keyTypeBytes = keyType.toByteArray(Charsets.US_ASCII)
            dataOutput.writeInt(keyTypeBytes.size)
            dataOutput.write(keyTypeBytes)

            val keyBytes = ByteArray(32) { (it + 1).toByte() }
            dataOutput.writeInt(keyBytes.size)
            dataOutput.write(keyBytes)
            dataOutput.flush()
        }
        output.toByteArray()
    }

    companion object {
        // Generated once for the whole suite: the signing tests only need a
        // consistent identity, not a fresh key, and RSA-2048 generation is
        // expensive.
        private val signerKeyPair by lazy { generateRsaSshKeyPair() }
    }
}
