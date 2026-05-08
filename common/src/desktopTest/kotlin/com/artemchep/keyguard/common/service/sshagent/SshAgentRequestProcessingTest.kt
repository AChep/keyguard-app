package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.kodein.di.DI
import org.kodein.di.bindSingleton

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
            onApprovalRequest = { _, _, _ ->
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
        assertEquals(0, unlockPromptCount)
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
            onApprovalRequest = { _, _, _ ->
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
            onApprovalRequest = { _, _, _ ->
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
            onApprovalRequest = { _, _, _ ->
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

    private fun createUnlockedSession(
        vararg secrets: DSecret,
    ): MasterSession.Key = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.LATEST,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        di = DI {
            bindSingleton<GetCiphers> {
                object : GetCiphers {
                    override fun invoke(): Flow<List<DSecret>> = flowOf(secrets.toList())
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
}
