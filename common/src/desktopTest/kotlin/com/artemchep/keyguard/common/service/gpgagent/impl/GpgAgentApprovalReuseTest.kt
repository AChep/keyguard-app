package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigState
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.ApprovalCacheInvalidation
import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.agent.finishAfterBlockedApprovalCacheAccess
import com.artemchep.keyguard.common.service.agent.finishAfterBlockedAgentRead
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCrypto
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.pendinghistory.RecordingPendingUsageHistoryQueue
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.test.gpgMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Instant

// Keep the cases together so they share the same fixtures and lifecycle assertions.
@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class GpgAgentApprovalReuseTest {
    @Test
    fun `sign and decrypt reuse macos terminal approvals across new processes`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            listOf<Byte?>(40, null).forEach { application ->
                val fixture = Fixture(this)
                fixture.succeed(operation, caller(connection = 1, application = application))
                fixture.succeed(operation, caller(connection = 2, application = application))
                assertEquals(1, fixture.approvals, operation.toString())
                assertEquals(2, fixture.crypto.calls)
            }
        }
    }

    @Test
    fun `sign and decrypt share different terminal sessions only at application scope`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            listOf(AgentApprovalCachePolicy.Default, AgentApprovalCachePolicy.Application).forEach { policy ->
                val fixture = Fixture(this, policy)
                fixture.succeed(operation, caller(connection = 1, terminal = 30))
                fixture.succeed(operation, caller(connection = 2, terminal = 31))
                fixture.succeed(operation, caller(connection = 3, terminal = 32, application = 41))
                val expected = if (policy == AgentApprovalCachePolicy.Application) 2 else 3
                assertEquals(expected, fixture.approvals, operation.toString() + policy)
            }
        }
    }

    @Test
    fun `failed joint proof keeps sign and decrypt approvals scoped to the process`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            listOf(AgentApprovalCachePolicy.Default, AgentApprovalCachePolicy.Application).forEach { policy ->
                val fixture = Fixture(this, policy)
                fixture.succeed(operation, caller(connection = 1, terminal = null, application = null))
                fixture.succeed(operation, caller(connection = 2, terminal = null, application = null))
                assertEquals(2, fixture.approvals, operation.toString() + policy)
            }
        }
    }

    @Test
    fun `approval reuse stays separate for operations keys and authorization contexts`() = runTest {
        val fixture = Fixture(this)
        var expected = 0
        GpgAgentOperation.entries.forEach { operation ->
            fixture.succeed(operation, caller(connection = 1, context = 50))
            fixture.succeed(operation, caller(connection = 2, context = 50))
            assertEquals(++expected, fixture.approvals, operation.toString())

            fixture.succeed(operation, caller(connection = 3, context = 50), OTHER_KEYGRIP)
            assertEquals(++expected, fixture.approvals, "Different keygrip")

            fixture.succeed(operation, caller(connection = 4, context = 51))
            assertEquals(++expected, fixture.approvals, "Different authorization context")
        }
    }

    @Test
    fun `denied and failed operations do not create remembered approvals`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.onApproval = { false }
            assertIs<GpgAgentOperationResult.UserDenied>(fixture.request(operation, caller(connection = 1)))
            assertEquals(1, fixture.approvals)
            assertEquals(0, fixture.crypto.calls)

            fixture.onApproval = { true }
            fixture.crypto.fail = true
            assertIs<GpgAgentOperationResult.Failure>(fixture.request(operation, caller(connection = 2)))
            assertEquals(2, fixture.approvals)

            fixture.crypto.fail = false
            fixture.succeed(operation, caller(connection = 3))
            fixture.succeed(operation, caller(connection = 4))
            assertEquals(3, fixture.approvals)
            assertEquals(3, fixture.crypto.calls)
        }
    }

    @Test
    fun `config changes vault lock and a new vault session invalidate both operation grants`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.succeed(operation, caller(connection = 1))
            fixture.succeed(operation, caller(connection = 2))
            assertEquals(1, fixture.approvals)

            fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Application, persist = {})()
            fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Default, persist = {})()
            fixture.succeed(operation, caller(connection = 3))
            assertEquals(2, fixture.approvals)

            fixture.config.updateApprovalWindow(Duration.ZERO, persist = {})()
            fixture.config.updateApprovalWindow(Duration.INFINITE, persist = {})()
            fixture.succeed(operation, caller(connection = 4))
            assertEquals(3, fixture.approvals)

            val unlocked = fixture.vault.valueOrNull
            fixture.vault.valueOrNull = MasterSession.Empty()
            runCurrent()
            fixture.vault.valueOrNull = unlocked
            fixture.succeed(operation, caller(connection = 5))
            assertEquals(4, fixture.approvals)

            fixture.vault.valueOrNull = createVaultSession()
            fixture.succeed(operation, caller(connection = 6))
            assertEquals(5, fixture.approvals)
        }
    }

    @Test
    fun `an approval open across a config change cannot restore an old grant`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val result = fixture.finishPendingApproval(operation) {
                fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Application, persist = {})()
                fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Default, persist = {})()
            }
            assertIs<GpgAgentOperationResult.Success<*>>(result)

            fixture.succeed(operation, caller(connection = 2))
            assertEquals(2, fixture.approvals)
        }
    }

    @Test
    fun `lock or replacement vault session during approval prevents either private operation`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (replaceSession in listOf(false, true)) {
                val fixture = Fixture(this)
                val result = fixture.finishPendingApproval(operation) {
                    fixture.vault.valueOrNull = if (replaceSession) createVaultSession() else MasterSession.Empty()
                }
                assertIs<GpgAgentOperationResult.VaultLocked>(result)
                assertEquals(0, fixture.crypto.calls)
                val event = fixture.pendingHistory.items.single()
                assertEquals(GpgUsageHistoryResponseType.VAULT_LOCKED.name, event.responseType)
                assertEquals(KEYGRIP, event.cipherId)
                assertEquals(KEYGRIP, event.fingerprint)
                assertEquals(KEYGRIP, event.keygrip)
            }
        }
    }

    @Test
    fun `keys removed stripped or replaced during approval are not used`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (change in listOf("removed", "private removed", "replaced item", "replaced account")) {
                val fixture = Fixture(this)
                val result = fixture.finishPendingApproval(operation) {
                    fixture.ciphers.value = fixture.ciphers.value.mapNotNull { cipher ->
                        if (cipher.id != KEYGRIP) return@mapNotNull cipher
                        when (change) {
                            "removed" -> null
                            "private removed" -> cipher.copy(gpgKey = cipher.gpgKey?.copy(privateKeyArmored = null))
                            "replaced item" -> cipher.copy(id = "replacement")
                            else -> cipher.copy(accountId = "replacement")
                        }
                    }
                }
                assertIs<GpgAgentOperationResult.KeyNotFound>(result)
                assertEquals(0, fixture.crypto.calls, change)
            }
        }
    }

    @Test
    fun `filter changed during approval is read before sign or decrypt`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val result = fixture.finishPendingApproval(operation) {
                fixture.filter.value = onlyOtherKeyFilter()
            }
            assertIs<GpgAgentOperationResult.KeyNotFound>(result)
            assertEquals(0, fixture.crypto.calls)
        }
    }

    @Test
    fun `revocation during signing approval prevents the signature`() = runTest {
        val fixture = Fixture(this)
        val result = fixture.finishPendingApproval(GpgAgentOperation.SIGN) { fixture.revoked = true }
        assertIs<GpgAgentOperationResult.KeyNotFound>(result)
        assertEquals(0, fixture.crypto.calls)
    }

    @Test
    fun `locked-origin request can unlock and then receive explicit approval`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val session = fixture.vault.valueOrNull
            fixture.vault.valueOrNull = MasterSession.Empty()
            val result = fixture.finishPendingApproval(operation) { fixture.vault.valueOrNull = session }
            assertIs<GpgAgentOperationResult.Success<*>>(result)
            assertEquals(1, fixture.crypto.calls)
            assertEquals(1, fixture.approvals)
        }
    }

    @Test
    fun `cached and locked-origin operations resolve each key only once`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.succeed(operation, caller(connection = 1))
            assertEquals(4, fixture.resolutions, "Explicit approval refreshes both keys")
            fixture.resolutions = 0
            fixture.succeed(operation, caller(connection = 2))
            assertEquals(2, fixture.resolutions, "Cached approval resolves each key once")
            assertEquals(1, fixture.approvals)

            val unlocked = fixture.vault.valueOrNull
            fixture.vault.valueOrNull = MasterSession.Empty()
            fixture.resolutions = 0
            val result = fixture.finishPendingApproval(operation) { fixture.vault.valueOrNull = unlocked }
            assertIs<GpgAgentOperationResult.Success<*>>(result)
            assertEquals(2, fixture.resolutions, "Unlock approval resolves each key once")
            assertEquals(2, fixture.approvals)
        }
    }

    @Test
    fun `cached approvals read key and filter changes after settings contention`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (removeKey in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.succeed(operation, caller(connection = 1))
                val result = finishAfterBlockedApprovalCacheAccess(
                    config = fixture.config,
                    request = { fixture.request(operation, caller(connection = 2)) },
                ) {
                    if (removeKey) {
                        fixture.ciphers.value = fixture.ciphers.value.filter { it.id != KEYGRIP }
                    } else {
                        fixture.filter.value = onlyOtherKeyFilter()
                    }
                }
                assertIs<GpgAgentOperationResult.KeyNotFound>(result)
                assertEquals(1, fixture.approvals)
                assertEquals(1, fixture.crypto.calls)
            }
        }
    }

    @Test
    fun `vault denial history falls back to the cipher fingerprint`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.ciphers.value = fixture.ciphers.value.map { cipher ->
                cipher.copy(gpgKey = cipher.gpgKey?.let { gpgKey ->
                    gpgKey.copy(metadata = gpgKey.metadata?.copy(
                        certificates = gpgKey.metadata.certificates.map { certificate ->
                            certificate.copy(components = certificate.components.map { component ->
                                component.copy(fingerprint = "")
                            })
                        },
                    ))
                })
            }
            val result = fixture.finishPendingApproval(operation) {
                fixture.vault.valueOrNull = MasterSession.Empty()
            }
            assertIs<GpgAgentOperationResult.VaultLocked>(result)
            val event = fixture.pendingHistory.items.single()
            assertEquals(GpgUsageHistoryResponseType.VAULT_LOCKED.name, event.responseType)
            assertEquals(KEYGRIP, event.fingerprint)
            assertEquals(0, fixture.crypto.calls)
        }
    }

    @Test
    fun `cached operations require new approval after settings change during eligibility reads`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (readFilter in listOf(false, true)) {
                for (change in ApprovalCacheInvalidation.entries) {
                    val fixture = Fixture(this)
                    val caller = caller(connection = 1)
                    fixture.succeed(operation, caller)
                    val result = finishAfterBlockedAgentRead(
                        beforeRead = { hook ->
                            if (readFilter) fixture.beforeFilterRead = hook else fixture.beforeCipherRead = hook
                        },
                        request = { fixture.request(operation, caller) },
                    ) { change.apply(fixture.config) }

                    assertIs<GpgAgentOperationResult.Success<*>>(result)
                    assertEquals(2, fixture.approvals, "$operation, filter=$readFilter, $change")
                    assertEquals(2, fixture.crypto.calls)
                    fixture.succeed(operation, caller)
                    val expected = if (change == ApprovalCacheInvalidation.Disable) 3 else 2
                    assertEquals(expected, fixture.approvals, "New approval must use the current policy")
                }
            }
        }
    }

    @Test
    fun `renewed approvals still reject denial and removed keys`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (deny in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.succeed(operation, caller(connection = 1))
                fixture.onApproval = {
                    fixture.ciphers.value = emptyList()
                    !deny
                }
                val result = finishAfterBlockedAgentRead(
                    beforeRead = { fixture.beforeCipherRead = it },
                    request = { fixture.request(operation, caller(connection = 1)) },
                ) { ApprovalCacheInvalidation.Policy.apply(fixture.config) }
                if (deny) {
                    assertIs<GpgAgentOperationResult.UserDenied>(result)
                } else {
                    assertIs<GpgAgentOperationResult.KeyNotFound>(result)
                }
                assertEquals(2, fixture.approvals)
                assertEquals(1, fixture.crypto.calls)
            }
        }
    }

    @Test
    fun `reordering duplicate GPG keys retains the approved cipher`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val original = fixture.ciphers.value.first()
            fixture.ciphers.value = listOf(original, original.copy(id = "duplicate"))
            val result = fixture.finishPendingApproval(operation) {
                fixture.ciphers.value = fixture.ciphers.value.reversed()
            }
            assertIs<GpgAgentOperationResult.Success<*>>(result)
            assertEquals(1, fixture.crypto.calls)
        }
    }

    private fun onlyOtherKeyFilter() = GpgAgentFilter(
        state = mapOf("cipher" to setOf(DFilter.ById(id = OTHER_KEYGRIP, what = DFilter.ById.What.CIPHER))),
    )

    private suspend fun Fixture.finishPendingApproval(
        operation: GpgAgentOperation,
        change: suspend () -> Unit,
    ): GpgAgentOperationResult<*> = coroutineScope {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        onApproval = {
            started.complete(Unit)
            finish.await()
            true
        }
        val pending = async { request(operation, caller(connection = 1)) }
        started.await()
        change()
        finish.complete(Unit)
        pending.await()
    }

    private inner class Fixture(
        scope: TestScope,
        policy: AgentApprovalCachePolicy = AgentApprovalCachePolicy.Default,
    ) {
        val ciphers = MutableStateFlow(listOf(createGpgCipher(KEYGRIP), createGpgCipher(OTHER_KEYGRIP)))
        val filter = MutableStateFlow(GpgAgentFilter())
        var revoked = false
        var resolutions = 0
        var beforeCipherRead: suspend () -> Unit = {}
        var beforeFilterRead: suspend () -> Unit = {}
        val vault = MutableVaultSession(createVaultSession(
            ciphers = ciphers,
            onResolve = { resolutions++ },
            beforeRead = { beforeCipherRead() },
            isRevoked = { revoked },
        ))
        val crypto = FakeCrypto()
        val pendingHistory = RecordingPendingUsageHistoryQueue()
        val config = AgentApprovalCacheConfigState(
            loadApprovalWindow = { Duration.INFINITE },
            loadCachePolicy = { policy },
        )
        var approvals = 0
        var onApproval: suspend () -> Boolean = { true }
        private val processor = GpgAgentRequestProcessorImpl(
            logRepository = NoOpLogRepository,
            crypto = crypto,
            getVaultSession = vault,
            getGpgAgentApprovalWindow = object : GetGpgAgentApprovalWindow {
                override fun invoke() = config.approvalWindow()
            },
            getGpgAgentApprovalCachePolicy = object : GetGpgAgentApprovalCachePolicy {
                override val approvalCacheConfig = config
                override fun invoke() = config.cachePolicy()
            },
            getGpgAgentFilter = object : GetGpgAgentFilter {
                override fun invoke(): Flow<GpgAgentFilter> = flow {
                    beforeFilterRead()
                    emit(filter.value)
                }
            },
            scope = scope.backgroundScope,
            pendingUsageHistoryQueue = pendingHistory,
            onApprovalRequest = {
                approvals++
                onApproval()
            },
        )

        suspend fun succeed(
            operation: GpgAgentOperation,
            caller: GpgAgentMessages.CallerIdentity,
            keygrip: String = KEYGRIP,
        ) {
            assertIs<GpgAgentOperationResult.Success<*>>(request(operation, caller, keygrip))
        }

        suspend fun request(
            operation: GpgAgentOperation,
            caller: GpgAgentMessages.CallerIdentity,
            keygrip: String = KEYGRIP,
        ): GpgAgentOperationResult<*> = when (operation) {
            GpgAgentOperation.SIGN -> processor.signHash(
                GpgAgentMessages.SignHashRequest(
                    keygrip = keygrip,
                    hashAlgorithm = "sha256",
                    hash = byteArrayOf(1, 2, 3),
                    caller = caller,
                ),
            )
            GpgAgentOperation.DECRYPT -> processor.decrypt(
                GpgAgentMessages.PkdecryptRequest(
                    keygrip = keygrip,
                    ciphertext = byteArrayOf(1, 2, 3),
                    caller = caller,
                ),
            )
        }
    }

    private class FakeCrypto : GpgAgentCrypto {
        var calls = 0
        var fail = false

        override fun signHash(
            privateKeyArmored: String,
            metadataKey: GpgAgentKeyMetadataKey,
            hashAlgorithm: String,
            hash: ByteArray,
            candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
        ): GpgAgentMessages.SignHashResponse {
            calls++
            check(!fail) { "Test signing failure" }
            return GpgAgentMessages.SignHashResponse(sexp = "test-signature")
        }

        override fun pkdecrypt(
            privateKeyArmored: String,
            metadataKey: GpgAgentKeyMetadataKey,
            ciphertext: ByteArray,
            unwrapEcdh: Boolean,
        ): GpgAgentMessages.PkdecryptResponse {
            calls++
            check(!fail) { "Test decryption failure" }
            return GpgAgentMessages.PkdecryptResponse(valueSexp = "test-plaintext")
        }
    }

    private class MutableVaultSession(initialValue: MasterSession) : GetVaultSession {
        private val state = MutableStateFlow(initialValue)
        override var valueOrNull: MasterSession
            get() = state.value
            set(value) {
                state.value = value
            }

        override fun invoke(): Flow<MasterSession> = state
    }

    private fun createVaultSession(
        ciphers: MutableStateFlow<List<DSecret>> = MutableStateFlow(
            listOf(createGpgCipher(KEYGRIP), createGpgCipher(OTHER_KEYGRIP)),
        ),
        onResolve: () -> Unit = {},
        beforeRead: suspend () -> Unit = {},
        isRevoked: () -> Boolean = { false },
    ): MasterSession.Key {
        return MasterSession.Key(
            masterKey = MasterKey(version = MasterKdfVersion.LATEST, byteArray = ByteArray(32)),
            di = DI {
                bindSingleton<GetCiphers> {
                    object : GetCiphers {
                        override fun invoke(): Flow<List<DSecret>> = flow {
                            beforeRead()
                            emit(ciphers.value)
                        }
                    }
                }
                bindSingleton<GpgKeyMetadataResolver> {
                    object : GpgKeyMetadataResolver {
                        override fun resolve(
                            privateKeyArmored: String?,
                            publicKeyArmored: String?,
                            fingerprint: String?,
                            candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
                        ): GpgAgentMetadataResolution? {
                            onResolve()
                            val metadata = ciphers.value.firstOrNull { it.gpgKey?.fingerprint == fingerprint }
                                ?.gpgKey?.metadata ?: return null
                            return GpgAgentMetadataResolution(
                                metadata = metadata,
                                authorization = GpgAgentAuthorizationSnapshot(
                                    evaluatedAtEpochSeconds = 1,
                                    policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
                                    keys = metadata.routableAgentKeys,
                                    revocations = metadata.routableAgentKeys.associate { key ->
                                        key.fingerprint to if (isRevoked()) {
                                            GpgRevocationStatus.REVOKED
                                        } else {
                                            GpgRevocationStatus.NOT_REVOKED
                                        }
                                    },
                                ),
                            )
                        }
                    }
                }
            },
            origin = MasterSession.Key.Authenticated,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
    }

    private fun createGpgCipher(keygrip: String) = DSecret(
        id = keygrip,
        accountId = "account",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
        createdDate = Instant.parse("2024-01-01T00:00:00Z"),
        archivedDate = null,
        deletedDate = null,
        service = BitwardenService(),
        name = "Test GPG key",
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        type = DSecret.Type.GpgKey,
        gpgKey = DSecret.GpgKey(
            privateKeyArmored = "test-private-key",
            publicKeyArmored = "test-public-key",
            fingerprint = keygrip,
            metadata = gpgMetadata(
                GpgAgentKeyMetadataKey(
                    keygrip = keygrip,
                    fingerprint = keygrip,
                    algorithm = "RSA",
                    capabilities = setOf("sign", "decrypt"),
                ),
            ),
        ),
    )

    private fun caller(
        connection: Byte,
        terminal: Byte? = 30,
        application: Byte? = 40,
        context: Byte? = null,
    ) = GpgAgentMessages.CallerIdentity(
        appName = "iTerm2",
        authorization = CallerAuthorization(
            connectionFingerprint = ByteArray(32) { connection },
            subjects = buildList {
                add(
                    CallerAuthorizationSubject(
                        kind = AgentCallerAuthorizationSchema.SubjectKind.PROCESS,
                        evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_AUDIT_TOKEN,
                        fingerprint = ByteArray(32) { (connection + 10).toByte() },
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

    private object NoOpLogRepository : LogRepository {
        override fun post(tag: String, message: String, level: LogLevel) = Unit
        override suspend fun add(tag: String, message: String, level: LogLevel) = Unit
    }

    private companion object {
        const val KEYGRIP = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val OTHER_KEYGRIP = "1123456789ABCDEF0123456789ABCDEF01234567"
    }
}
