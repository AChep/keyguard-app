package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.crypto.SshCertificateParser
import com.artemchep.keyguard.common.service.vault.testDomainSessionAccess
import com.artemchep.keyguard.common.service.vault.testVaultSession
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.createSecret
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// 2026-01-01T00:00:00Z
internal const val TEST_NOW = 1_767_225_600L

internal val lockedVaultSession = object : GetVaultSession {
    override val valueOrNull: MasterSession? = null
    override fun invoke(): Flow<MasterSession> = flowOf()
}

internal fun unlockedVaultSession(vararg secrets: DSecret): GetVaultSession {
    val session = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.LATEST,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        session = testVaultSession {
            scoped<GetCiphers> {
                object : GetCiphers {
                    override fun invoke(): Flow<List<DSecret>> = flowOf(secrets.toList())
                }
            }
        },
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.fromEpochSeconds(0),
    )
    return object : GetVaultSession {
        override val valueOrNull: MasterSession = session
        override fun invoke(): Flow<MasterSession> = flowOf(session)
    }
}

internal fun certifiedSecret(
    certificate: String?,
    id: String = "certified",
    name: String = "Certified",
) = createSecret(
    id = id,
    name = name,
    accountId = "account",
    type = DSecret.Type.SshKey,
    sshKey = DSecret.SshKey(
        privateKey = TEST_ED25519_PRIVATE_KEY,
        publicKey = TEST_ED25519_PUBLIC_KEY,
        fingerprint = "SHA256:UCUiLr7Pjs9wFFJMDByLgc3NrtdU344OgUM45wZPcIQ",
        certificate = certificate,
    ),
)

// Every row is of the same key, so they share its blob hash.
internal fun certifiedRow(
    certificate: String?,
    cipherId: String = "certified",
    name: String = "Certified",
) = SshAgentPublicKeyRow(
    accountId = "account",
    cipherId = cipherId,
    publicKeyBlobSha256 = "certified",
    publicKey = TEST_ED25519_PUBLIC_KEY,
    keyType = "ssh-ed25519",
    fingerprint = "SHA256:UCUiLr7Pjs9wFFJMDByLgc3NrtdU344OgUM45wZPcIQ",
    canSign = true,
    name = name,
    certificate = certificate,
)

internal fun certifiedCatalog(
    vararg entries: SshAgentPublicKeyRow,
) = object : SshAgentPublicKeyRepository {
    private val rows = entries.toList()

    override fun get(): IO<List<SshAgentPublicKeyRow>> = { rows }

    override fun getByPublicKeyBlobSha256(
        publicKeyBlobSha256: String,
    ): IO<List<SshAgentPublicKeyRow>> = { rows.filter { it.publicKeyBlobSha256 == publicKeyBlobSha256 } }

    override fun getByPublicKey(
        publicKey: String,
    ): IO<List<SshAgentPublicKeyRow>> = { rows.filter { sshPublicKeysMatch(it.publicKey, publicKey) } }

    override fun replaceAll(keys: List<SshAgentPublicKeyRow>): IO<Unit> = { }

    override fun clear(): IO<Unit> = { }

    override fun clearNames(): IO<Unit> = { }
}

internal fun certificateTestProcessor(
    vaultSession: GetVaultSession,
    catalog: SshAgentPublicKeyRepository = SshAgentPublicKeyRepositoryEmpty,
    now: Long = TEST_NOW,
    parser: SshCertificateParser = TestSshCertificateParser,
    onApprovalRequest: suspend (SshAgentApprovalPrompt) -> Boolean = { true },
) = SshAgentRequestProcessorImpl(
    logRepository = RecordingLogRepository(),
    getVaultSession = vaultSession,
    sessionAccess = testDomainSessionAccess(),
    getSshAgentApprovalWindow = object : GetSshAgentApprovalWindow {
        override fun invoke(): Flow<Duration> = flowOf(Duration.ZERO)
    },
    getSshAgentFilter = object : GetSshAgentFilter {
        override fun invoke(): Flow<SshAgentFilter> = flowOf(SshAgentFilter())
    },
    scope = CoroutineScope(Dispatchers.Unconfined),
    sshAgentPublicKeyRepository = catalog,
    clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(now)
    },
    sshCertificateParser = parser,
    onApprovalRequest = onApprovalRequest,
)

internal suspend fun SshAgentRequestProcessor.listedKeys(): List<SshAgentMessages.SshKey> {
    val result = listKeys(caller = null)
    return assertIs<SshAgentRequestProcessor.ListKeysResult.Success>(result).response.keys
}

internal fun certificateSignRequest(publicKey: String) = SshAgentMessages.SignDataRequest(
    publicKey = publicKey,
    data = "certificate".encodeToByteArray(),
    flags = 0,
)
