package com.artemchep.keyguard.android.sshagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalWindowMemory
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessorImpl
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SshAgentSharedApprovalMemoryTest {
    @Test
    fun `connection-only bridge reuses approval within the same connection`() = runTest {
        val fixture = Fixture()
        try {
            val processor = fixture.createProcessor()

            repeat(2) {
                assertIs<SshAgentRequestProcessor.SignDataResult.Success>(
                    processor.signData(
                        fixture.request(appFingerprintByte = null, connectionByte = 11),
                    ),
                )
            }

            assertEquals(1, fixture.approvalPromptCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `connection-only bridges do not share approval across connections`() = runTest {
        val fixture = Fixture()
        try {
            val firstProcessor = fixture.createProcessor()
            val secondProcessor = fixture.createProcessor()

            assertIs<SshAgentRequestProcessor.SignDataResult.Success>(
                firstProcessor.signData(
                    fixture.request(appFingerprintByte = null, connectionByte = 11),
                ),
            )
            assertIs<SshAgentRequestProcessor.SignDataResult.Success>(
                secondProcessor.signData(
                    fixture.request(appFingerprintByte = null, connectionByte = 22),
                ),
            )

            assertEquals(2, fixture.approvalPromptCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `two bridge processors reuse approval for the same verified application`() = runTest {
        val fixture = Fixture()
        try {
            val firstProcessor = fixture.createProcessor()
            val secondProcessor = fixture.createProcessor()

            assertIs<SshAgentRequestProcessor.SignDataResult.Success>(
                firstProcessor.signData(fixture.request(appFingerprintByte = 1, connectionByte = 11)),
            )
            assertIs<SshAgentRequestProcessor.SignDataResult.Success>(
                secondProcessor.signData(fixture.request(appFingerprintByte = 1, connectionByte = 22)),
            )

            assertEquals(1, fixture.approvalPromptCount)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `shared approval memory isolates different verified applications`() = runTest {
        val fixture = Fixture()
        try {
            val firstProcessor = fixture.createProcessor()
            val secondProcessor = fixture.createProcessor()

            assertIs<SshAgentRequestProcessor.SignDataResult.Success>(
                firstProcessor.signData(fixture.request(appFingerprintByte = 1, connectionByte = 11)),
            )
            assertIs<SshAgentRequestProcessor.SignDataResult.Success>(
                secondProcessor.signData(fixture.request(appFingerprintByte = 2, connectionByte = 22)),
            )

            assertEquals(2, fixture.approvalPromptCount)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val keyPair = generateRsaKeyPair()
        private val publicKey = toOpenSshPublicKey(keyPair.public as RSAPublicKey)
        private val vaultSession = FixedVaultSession(
            createUnlockedSession(
                createSshSecret(
                    publicKey = publicKey,
                    privateKey = toPkcs8PrivateKeyPem(keyPair.private),
                ),
            ),
        )
        private val approvalWindow = object : GetSshAgentApprovalWindow {
            override fun invoke(): Flow<Duration> = flowOf(5.minutes)
        }
        private val approvalCachePolicy = object : GetSshAgentApprovalCachePolicy {
            override fun invoke(): Flow<AgentApprovalCachePolicy> =
                flowOf(AgentApprovalCachePolicy.Application)
        }
        private val sshAgentFilter = object : GetSshAgentFilter {
            override fun invoke(): Flow<SshAgentFilter> = flowOf(SshAgentFilter())
        }
        private val sharedApprovalMemory = SshAgentApprovalWindowMemory(
            getSshAgentApprovalWindow = approvalWindow,
            getVaultSession = vaultSession,
            scope = scope,
            getSshAgentApprovalCachePolicy = approvalCachePolicy,
        )

        var approvalPromptCount = 0
            private set

        fun createProcessor() = SshAgentRequestProcessorImpl(
            logRepository = NoOpLogRepository,
            getVaultSession = vaultSession,
            getSshAgentApprovalWindow = approvalWindow,
            getSshAgentApprovalCachePolicy = approvalCachePolicy,
            getSshAgentFilter = sshAgentFilter,
            scope = scope,
            approvalWindowMemory = sharedApprovalMemory,
            onApprovalRequest = {
                approvalPromptCount += 1
                true
            },
        )

        fun request(
            appFingerprintByte: Byte?,
            connectionByte: Byte,
        ) = SshAgentMessages.SignDataRequest(
            publicKey = publicKey,
            data = byteArrayOf(1, 2, 3),
            flags = 0x02,
            caller = SshAgentMessages.CallerIdentity(
                appName = "Verified app",
                appBundlePath = "com.example.app",
                authorization = CallerAuthorization(
                    connectionFingerprint = ByteArray(32) { connectionByte },
                    subjects = appFingerprintByte
                        ?.let { fingerprintByte ->
                            listOf(
                                CallerAuthorizationSubject(
                                    kind =
                                        AgentCallerAuthorizationSchema.SubjectKind
                                            .STABLE_APPLICATION,
                                    evidenceSource =
                                        AgentCallerAuthorizationSchema.EvidenceSource
                                            .ANDROID_FRAMEWORK_PACKAGE,
                                    fingerprint = ByteArray(32) { fingerprintByte },
                                ),
                            )
                        }
                        .orEmpty(),
                ),
            ),
        )

        fun close() {
            scope.cancel()
        }
    }

    private class FixedVaultSession(
        private val session: MasterSession.Key,
    ) : GetVaultSession {
        override val valueOrNull: MasterSession = session

        override fun invoke(): Flow<MasterSession> = flowOf(session)
    }

    private object NoOpLogRepository : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit
    }

    private companion object {
        fun createUnlockedSession(
            secret: DSecret,
        ): MasterSession.Key = MasterSession.Key(
            masterKey = MasterKey(
                version = MasterKdfVersion.LATEST,
                byteArray = byteArrayOf(1, 2, 3),
            ),
            di = DI {
                bindSingleton<GetCiphers> {
                    object : GetCiphers {
                        override fun invoke(): Flow<List<DSecret>> = flowOf(listOf(secret))
                    }
                }
            },
            origin = MasterSession.Key.Authenticated,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        fun createSshSecret(
            publicKey: String,
            privateKey: String,
        ): DSecret = DSecret(
            id = "signer",
            accountId = "account",
            folderId = null,
            organizationId = null,
            collectionIds = emptySet(),
            revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
            createdDate = Instant.parse("2024-01-01T00:00:00Z"),
            archivedDate = null,
            deletedDate = null,
            service = BitwardenService(),
            name = "Signer",
            notes = "",
            favorite = false,
            reprompt = false,
            synced = true,
            type = DSecret.Type.SshKey,
            sshKey = DSecret.SshKey(
                privateKey = privateKey,
                publicKey = publicKey,
                fingerprint = "SHA256:signer",
            ),
        )

        fun generateRsaKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            return generator.generateKeyPair()
        }

        fun toOpenSshPublicKey(publicKey: RSAPublicKey): String {
            val blob = ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { dataOutput ->
                    dataOutput.writeSshString("ssh-rsa".toByteArray(Charsets.US_ASCII))
                    dataOutput.writeSshMpint(publicKey.publicExponent)
                    dataOutput.writeSshMpint(publicKey.modulus)
                }
                output.toByteArray()
            }
            return "ssh-rsa ${Base64.getEncoder().encodeToString(blob)}"
        }

        fun DataOutputStream.writeSshMpint(value: BigInteger) {
            writeSshString(value.toByteArray())
        }

        fun DataOutputStream.writeSshString(value: ByteArray) {
            writeInt(value.size)
            write(value)
        }

        fun toPkcs8PrivateKeyPem(privateKey: PrivateKey): String {
            val encoded = Base64.getEncoder().encodeToString(privateKey.encoded)
            return buildString {
                appendLine("-----BEGIN PRIVATE KEY-----")
                encoded.chunked(70).forEach(::appendLine)
                appendLine("-----END PRIVATE KEY-----")
            }
        }
    }
}
