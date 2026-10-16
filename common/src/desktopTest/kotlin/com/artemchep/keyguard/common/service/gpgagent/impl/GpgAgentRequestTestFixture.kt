package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AddGpgUsageHistoryRequest
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GpgAgentFilter
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigState
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalPrompt
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentAuthorizationSnapshot
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCrypto
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyInfoRow
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepositoryEmpty
import com.artemchep.keyguard.common.service.gpgagent.GpgRevocationStatus
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.pendinghistory.RecordingPendingUsageHistoryQueue
import com.artemchep.keyguard.common.service.vault.testDomainSessionAccess
import com.artemchep.keyguard.common.service.vault.testVaultSession
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetGpgAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetGpgAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.test.gpgMetadata
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope

internal class GpgAgentRequestTestFixture(
    scope: TestScope,
    policy: AgentApprovalCachePolicy = AgentApprovalCachePolicy.Default,
) {
    val ciphers = MutableStateFlow(listOf(createGpgCipher(KEYGRIP), createGpgCipher(OTHER_KEYGRIP)))
    val filter = MutableStateFlow(GpgAgentFilter())
    val history = mutableListOf<AddGpgUsageHistoryRequest>()
    val prompts = mutableListOf<GpgAgentApprovalPrompt>()
    var cachedKeys = emptyList<GpgAgentKeyInfoRow>()
    val revokedCipherIds = mutableSetOf<String>()
    var resolutions = 0
    var beforeCipherRead: suspend () -> Unit = {}
    var beforeFilterRead: suspend () -> Unit = {}
    val vault = MutableVaultSession(createVaultSession(
        ciphers = ciphers,
        onResolve = { resolutions++ },
        beforeRead = { beforeCipherRead() },
        isRevoked = { it.id in revokedCipherIds },
        history = history,
    ))
    val crypto = FakeCrypto()
    val pendingHistory = RecordingPendingUsageHistoryQueue()
    val config = AgentApprovalCacheConfigState(
        loadApprovalWindow = { Duration.INFINITE },
        loadCachePolicy = { policy },
    )
    val approvals get() = prompts.size
    var onApproval: suspend () -> Boolean = { true }
    private val processor = GpgAgentRequestProcessorImpl(
        sessionAccess = testDomainSessionAccess(),
        logRepository = LogRepositoryBridge(emptyList()),
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
        gpgPublicKeyRepository = FakeGpgPublicKeyRepository { cachedKeys },
        pendingUsageHistoryQueue = pendingHistory,
        onApprovalRequest = {
            prompts += it
            onApproval()
        },
    )

    /** Locks the vault and returns a function that restores the unlocked session. */
    fun lock(): () -> Unit {
        val session = vault.valueOrNull
        vault.valueOrNull = MasterSession.Empty()
        return { vault.valueOrNull = session }
    }

    suspend fun listKeys() =
        assertIs<GpgAgentRequestProcessor.ListKeysResult.Success>(processor.listKeys(caller = null)).response.keys

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

    suspend fun finishPendingApproval(
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

    class FakeCrypto : GpgAgentCrypto {
        var fail = false
        val privateKeys = mutableListOf<String>()
        val calls get() = privateKeys.size

        override fun signHash(
            privateKeyArmored: String,
            metadataKey: GpgAgentKeyMetadataKey,
            hashAlgorithm: String,
            hash: ByteArray,
            candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
        ): GpgAgentMessages.SignHashResponse {
            privateKeys += privateKeyArmored
            check(!fail) { "Test signing failure" }
            return GpgAgentMessages.SignHashResponse(sexp = "test-signature")
        }

        override fun pkdecrypt(
            privateKeyArmored: String,
            metadataKey: GpgAgentKeyMetadataKey,
            ciphertext: ByteArray,
            unwrapEcdh: Boolean,
        ): GpgAgentMessages.PkdecryptResponse {
            privateKeys += privateKeyArmored
            check(!fail) { "Test decryption failure" }
            return GpgAgentMessages.PkdecryptResponse(valueSexp = "test-plaintext")
        }
    }

    class MutableVaultSession(initialValue: MasterSession) : GetVaultSession {
        private val state = MutableStateFlow(initialValue)
        override var valueOrNull: MasterSession
            get() = state.value
            set(value) {
                state.value = value
            }

        override fun invoke(): Flow<MasterSession> = state
    }

    companion object {
        fun createVaultSession(
            ciphers: MutableStateFlow<List<DSecret>> = MutableStateFlow(
                listOf(createGpgCipher(KEYGRIP), createGpgCipher(OTHER_KEYGRIP)),
            ),
            onResolve: () -> Unit = {},
            beforeRead: suspend () -> Unit = {},
            isRevoked: (DSecret) -> Boolean = { false },
            history: MutableList<AddGpgUsageHistoryRequest> = mutableListOf(),
        ): MasterSession.Key {
            return MasterSession.Key(
                masterKey = MasterKey(version = MasterKdfVersion.LATEST, byteArray = ByteArray(32)),
                session = testVaultSession {
                    scoped<AddGpgUsageHistory> {
                        object : AddGpgUsageHistory {
                            override fun invoke(request: AddGpgUsageHistoryRequest): IO<Unit> = {
                                history += request
                            }
                        }
                    }
                    scoped<GetCiphers> {
                        object : GetCiphers {
                            override fun invoke(): Flow<List<DSecret>> = flow {
                                beforeRead()
                                emit(ciphers.value)
                            }
                        }
                    }
                    scoped<GpgKeyMetadataResolver> {
                        object : GpgKeyMetadataResolver {
                            override fun resolve(
                                privateKeyArmored: String?,
                                publicKeyArmored: String?,
                                fingerprint: String?,
                                candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
                            ): GpgAgentMetadataResolution? {
                                onResolve()
                                val cipher = ciphers.value.firstOrNull {
                                    it.gpgKey?.fingerprint == fingerprint &&
                                        it.gpgKey?.publicKeyArmored == publicKeyArmored
                                }
                                val metadata = cipher?.gpgKey?.metadata ?: return null
                                val status = if (isRevoked(cipher)) {
                                    GpgRevocationStatus.REVOKED
                                } else {
                                    GpgRevocationStatus.NOT_REVOKED
                                }
                                return GpgAgentMetadataResolution(
                                    metadata = metadata,
                                    authorization = GpgAgentAuthorizationSnapshot(
                                        evaluatedAtEpochSeconds = 1,
                                        policyRevision = GpgAgentAuthorizationSnapshot.SUPPORTED_POLICY_REVISION,
                                        keys = metadata.routableAgentKeys,
                                        revocations = metadata.routableAgentKeys.associate { key ->
                                            key.fingerprint to status
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

        fun createGpgCipher(keygrip: String) = createSecret(
            id = keygrip,
            name = "Test GPG key",
            accountId = "account",
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

        fun caller(
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

        fun cipherFilter(id: String) = GpgAgentFilter(
            state = mapOf("cipher" to setOf(DFilter.ById(id = id, what = DFilter.ById.What.CIPHER))),
        )

        const val KEYGRIP = "0123456789ABCDEF0123456789ABCDEF01234567"
        const val OTHER_KEYGRIP = "1123456789ABCDEF0123456789ABCDEF01234567"
    }
}

internal class FakeGpgPublicKeyRepository(
    private val keyInfo: () -> List<GpgAgentKeyInfoRow>,
) : GpgPublicKeyRepository by GpgPublicKeyRepositoryEmpty {
    override fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>> = { keyInfo() }

    override fun getKeyInfoByKeygrip(keygrip: String): IO<List<GpgAgentKeyInfoRow>> = {
        keyInfo().filter { it.keygrip == keygrip }
    }
}
