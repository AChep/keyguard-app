package com.artemchep.keyguard

import com.artemchep.keyguard.common.model.CheckPasswordSetLeakRequest
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilder
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.SshAgentFilter
import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheConfigState
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepositoryEmpty
import com.artemchep.keyguard.common.service.sshagent.SshAgentRequestProcessor.SignDataResult
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.vault.VaultSessionFactory
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherSshKeyWeakCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalCachePolicy
import com.artemchep.keyguard.common.usecase.GetSshAgentApprovalWindow
import com.artemchep.keyguard.common.usecase.GetSshAgentFilter
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.di.GlobalModuleCommon
import com.artemchep.keyguard.di.VaultSessionScope
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Instant
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class SshAgentRequestProcessorAppleTest {
    @Test
    fun lockAndSessionReplacementDuringApprovalPreventSigning() = runTest {
        for (replace in listOf(false, true)) {
            val fixture = Fixture(this)
            val result = fixture.finishApproval {
                fixture.vault.valueOrNull = if (replace) fixture.createSession() else MasterSession.Empty()
            }
            assertIs<SignDataResult.VaultLocked>(result)
        }
    }

    @Test
    fun changedEligibilityAndCipherIdentityPreventSigning() = runTest {
        for (change in listOf("removed", "private removed", "cipher replaced", "account replaced", "filter")) {
            val fixture = Fixture(this)
            val result = fixture.finishApproval {
                if (change == "filter") {
                    fixture.filter.value = SshAgentFilter(
                        state = mapOf("cipher" to setOf(DFilter.ById("other", DFilter.ById.What.CIPHER))),
                    )
                } else {
                    fixture.ciphers.value = fixture.ciphers.value.mapNotNull { cipher ->
                        when (change) {
                            "removed" -> null
                            "private removed" -> cipher.copy(sshKey = cipher.sshKey?.copy(privateKey = null))
                            "cipher replaced" -> cipher.copy(id = "replacement")
                            else -> cipher.copy(accountId = "replacement")
                        }
                    }
                }
            }
            assertIs<SignDataResult.KeyNotFound>(result, change)
        }
    }

    @Test
    fun approvalUsesCurrentPrivateMaterial() = runTest {
        val fixture = Fixture(this)
        val result = fixture.finishApproval {
            fixture.ciphers.value = fixture.ciphers.value.map { cipher ->
                cipher.copy(sshKey = cipher.sshKey?.copy(privateKey = "invalid replacement key"))
            }
        }
        assertIs<SignDataResult.Failure>(result)
    }

    @Test
    fun reorderedDuplicateKeysRetainTheApprovedCipher() = runTest {
        val fixture = Fixture(this)
        val original = fixture.ciphers.value.single()
        fixture.ciphers.value = listOf(original, original.copy(id = "duplicate"))
        val result = fixture.finishApproval { fixture.ciphers.value = fixture.ciphers.value.reversed() }
        assertIs<SignDataResult.Success>(result)
    }

    @Test
    fun unchangedKeySignsAndLockedRequestsDoNotPrompt() = runTest {
        val fixture = Fixture(this)
        assertIs<SignDataResult.Success>(fixture.finishApproval {})
        assertEquals(2, fixture.cipherReads)
        assertEquals(2, fixture.filterReads)

        fixture.cipherReads = 0
        fixture.filterReads = 0
        assertIs<SignDataResult.Success>(fixture.processor.signData(fixture.request))
        assertEquals(1, fixture.cipherReads)
        assertEquals(1, fixture.filterReads)
        assertEquals(1, fixture.approvals)

        fixture.vault.valueOrNull = MasterSession.Empty()
        fixture.cipherReads = 0
        fixture.filterReads = 0
        assertIs<SignDataResult.VaultLocked>(fixture.processor.signData(fixture.request))
        assertEquals(1, fixture.approvals)
        assertEquals(0, fixture.cipherReads)
        assertEquals(0, fixture.filterReads)
    }

    @Test
    fun cachedApprovalReadsCurrentEligibilityAfterCacheAccessUnblocks() = runTest {
        for (excludeWithFilter in listOf(false, true)) {
            val fixture = Fixture(this)
            assertIs<SignDataResult.Success>(fixture.finishApproval {})
            fixture.cipherReads = 0
            fixture.filterReads = 0

            val updateStarted = CompletableDeferred<Unit>()
            val finishUpdate = CompletableDeferred<Unit>()
            val update = async {
                fixture.config.updateApprovalWindow(Duration.INFINITE, persist = {
                    updateStarted.complete(Unit)
                    finishUpdate.await()
                })()
            }
            updateStarted.await()
            val pending = async { fixture.processor.signData(fixture.request) }
            runCurrent()
            assertEquals(false, pending.isCompleted)
            assertEquals(0, fixture.cipherReads)
            assertEquals(0, fixture.filterReads)
            if (excludeWithFilter) {
                fixture.filter.value = SshAgentFilter(
                    state = mapOf(
                        "cipher" to setOf(DFilter.ById("other", DFilter.ById.What.CIPHER)),
                    ),
                )
            } else {
                fixture.ciphers.value = emptyList()
            }
            finishUpdate.complete(Unit)
            update.await()

            assertIs<SignDataResult.KeyNotFound>(pending.await())
            assertEquals(1, fixture.approvals)
            assertEquals(1, fixture.cipherReads)
            assertEquals(1, fixture.filterReads)
        }
    }

    @Test
    fun cachedApprovalStaysBoundToSessionWhileCacheAccessWaits() = runTest {
        for (replaceSession in listOf(false, true)) {
            val fixture = Fixture(this)
            assertIs<SignDataResult.Success>(fixture.finishApproval {})
            fixture.cipherReads = 0
            fixture.filterReads = 0

            val updateStarted = CompletableDeferred<Unit>()
            val finishUpdate = CompletableDeferred<Unit>()
            val update = async {
                fixture.config.updateApprovalWindow(Duration.INFINITE, persist = {
                    updateStarted.complete(Unit)
                    finishUpdate.await()
                })()
            }
            updateStarted.await()
            val pending = async { fixture.processor.signData(fixture.request) }
            runCurrent()
            assertEquals(0, fixture.cipherReads)
            assertEquals(0, fixture.filterReads)
            fixture.vault.valueOrNull = if (replaceSession) {
                fixture.createSession()
            } else {
                MasterSession.Empty()
            }
            finishUpdate.complete(Unit)
            update.await()

            assertIs<SignDataResult.VaultLocked>(pending.await())
            assertEquals(1, fixture.approvals)
            assertEquals(0, fixture.cipherReads)
            assertEquals(0, fixture.filterReads)
        }
    }

    @Test
    fun settingsChangedDuringEligibilityReadsRequireNewApproval() = runTest {
        for (readFilter in listOf(false, true)) {
            for (disable in listOf(false, true)) {
                val fixture = Fixture(this)
                assertIs<SignDataResult.Success>(fixture.finishApproval {})
                val started = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                val hook: suspend () -> Unit = {
                    started.complete(Unit)
                    finish.await()
                }
                if (readFilter) fixture.beforeFilterRead = hook else fixture.beforeCipherRead = hook
                val pending = async { fixture.processor.signData(fixture.request) }
                started.await()
                if (disable) {
                    fixture.config.updateApprovalWindow(Duration.ZERO, persist = {})()
                } else {
                    fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Connection, persist = {})()
                }
                finish.complete(Unit)
                assertIs<SignDataResult.Success>(pending.await())
                assertEquals(2, fixture.approvals)
                assertIs<SignDataResult.Success>(fixture.processor.signData(fixture.request))
                assertEquals(if (disable) 3 else 2, fixture.approvals)
            }
        }
    }

    @Test
    fun completedSettingsWritesInvalidateApprovalBeforeCollectorsRun() = runTest {
        for (readFilter in listOf(false, true)) {
            for (restoreWindow in listOf(false, true)) {
                val fixture = Fixture(this)
                assertIs<SignDataResult.Success>(fixture.finishApproval {})
                // Start any background collectors before the update, then keep
                // the write and authorization decision in this coroutine.
                runCurrent()
                var changed = false
                val hook: suspend () -> Unit = {
                    if (!changed) {
                        changed = true
                        fixture.config.updateApprovalWindow(Duration.ZERO, persist = {})()
                        if (restoreWindow) {
                            fixture.config.updateApprovalWindow(Duration.INFINITE, persist = {})()
                        }
                    }
                }
                if (readFilter) fixture.beforeFilterRead = hook else fixture.beforeCipherRead = hook

                assertIs<SignDataResult.Success>(fixture.processor.signData(fixture.request))
                assertEquals(2, fixture.approvals)
                assertIs<SignDataResult.Success>(fixture.processor.signData(fixture.request))
                assertEquals(if (restoreWindow) 2 else 3, fixture.approvals)
            }
        }
    }

    private class Fixture(scope: TestScope) {
        private val material = NativeCrypto.ssh.generate(NativeSshKeyType.ED25519)
        private val key = try {
            NativeCrypto.ssh.describe(NativeSshKeyType.ED25519, material.privateKey, material.publicKey)
        } finally {
            material.privateKey.fill(0)
        }
        val ciphers = MutableStateFlow(listOf(DSecret(
            id = "signer",
            accountId = "account",
            folderId = null,
            organizationId = null,
            collectionIds = emptySet(),
            revisionDate = Instant.parse("2024-01-01T00:00:00Z"),
            createdDate = null,
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
                privateKey = key.privateKeyPem,
                publicKey = key.publicKeyOpenSsh,
                fingerprint = key.publicFingerprint,
            ),
        )))
        // A real Koin vault scope, so the processor's session lookups go through the
        // production retirement boundary; only the cipher source is faked.
        private val sessionKoin = koinApplication {
            modules(
                GlobalModuleCommon().module,
                module {
                    scope<VaultSessionScope> {
                        // The SSH filter here only matches by cipher id; a filter that
                        // reached for any other service would be a test failure.
                        scoped<CipherFilterContext> { unusedCipherFilterContext() }
                        scoped<GetCiphers> {
                            object : GetCiphers {
                                override fun invoke(): Flow<List<DSecret>> = flow {
                                    cipherReads++
                                    beforeCipherRead()
                                    emit(ciphers.value)
                                }
                            }
                        }
                    }
                },
            )
        }.koin

        val vault = MutableVaultSession(createSession())
        val filter = MutableStateFlow(SshAgentFilter())
        var cipherReads = 0
        var filterReads = 0
        var approvals = 0
        var beforeCipherRead: suspend () -> Unit = {}
        var beforeFilterRead: suspend () -> Unit = {}
        private val started = CompletableDeferred<Unit>()
        private val finish = CompletableDeferred<Unit>()
        val request = SshAgentMessages.SignDataRequest(
            publicKey = key.publicKeyOpenSsh,
            data = "approval race".encodeToByteArray(),
            caller = SshAgentMessages.CallerIdentity(
                uid = 1000,
                gid = 1000,
                processName = "ssh",
                executablePath = "/usr/bin/ssh",
                appName = "Terminal",
                authorization = CallerAuthorization(
                    connectionFingerprint = ByteArray(32) { 1 },
                ),
            ),
        )
        val config = AgentApprovalCacheConfigState(
            loadApprovalWindow = { Duration.INFINITE },
            loadCachePolicy = { AgentApprovalCachePolicy.Default },
        )
        private val getApprovalWindow = object : GetSshAgentApprovalWindow {
            override fun invoke() = config.approvalWindow()
        }
        private val getApprovalCachePolicy = object : GetSshAgentApprovalCachePolicy {
            override val approvalCacheConfig = config

            override fun invoke() = config.cachePolicy()
        }
        val processor = SshAgentRequestProcessorApple(
            logRepository = NoOpLogRepository,
            getVaultSession = vault,
            getSshAgentApprovalWindow = getApprovalWindow,
            getSshAgentApprovalCachePolicy = getApprovalCachePolicy,
            getSshAgentFilter = object : GetSshAgentFilter {
                override fun invoke(): Flow<SshAgentFilter> = flow {
                    filterReads++
                    beforeFilterRead()
                    emit(filter.value)
                }
            },
            scope = scope.backgroundScope,
            sshAgentPublicKeyRepository = SshAgentPublicKeyRepositoryEmpty,
            sessionId = "test",
            onApproval = {
                approvals++
                started.complete(Unit)
                finish.await()
                true
            },
        )

        fun createSession() = MasterSession.Key(
            masterKey = MasterKey(MasterKdfVersion.LATEST, ByteArray(32)),
            session = sessionKoin.get<VaultSessionFactory>()
                .create(MasterKey(MasterKdfVersion.LATEST, ByteArray(32))),
            origin = MasterSession.Key.Authenticated,
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        suspend fun finishApproval(change: () -> Unit): SignDataResult = coroutineScope {
            val pending = async { processor.signData(request) }
            started.await()
            change()
            finish.complete(Unit)
            pending.await().also { assertEquals(1, approvals) }
        }
    }

    private class MutableVaultSession(initial: MasterSession) : GetVaultSession {
        private val state = MutableStateFlow(initial)
        override var valueOrNull: MasterSession
            get() = state.value
            set(value) { state.value = value }

        override fun invoke(): Flow<MasterSession> = state
    }

    private object NoOpLogRepository : LogRepository {
        override fun post(tag: String, message: String, level: LogLevel) = Unit

        override suspend fun add(tag: String, message: String, level: LogLevel) = Unit
    }
}

private fun unusedFilterService(): Nothing = error("This filter must not use an unrelated service")

private fun unusedCipherFilterContext() = CipherFilterContext(
    checkPasswordSetLeak = object : CheckPasswordSetLeak {
        override fun invoke(request: CheckPasswordSetLeakRequest): Nothing = unusedFilterService()
    },
    cipherSshKeyWeakCheck = object : CipherSshKeyWeakCheck {
        override fun invoke(cipher: DSecret): Nothing = unusedFilterService()
    },
    getAutofillDefaultMatchDetection = object : GetAutofillDefaultMatchDetection {
        override fun invoke(): Nothing = unusedFilterService()
    },
    equivalentDomainsBuilderFactory = EquivalentDomainsBuilderFactory(
        logRepository = LogRepositoryBridge(emptyList()),
        getEquivalentDomains = object : GetEquivalentDomains {
            override fun invoke(): Nothing = unusedFilterService()
        },
    ),
    cipherBreachCheck = object : CipherBreachCheck {
        override fun invoke(
            cipher: DSecret,
            breaches: HibpBreachGroup,
            matchType: DSecret.Uri.MatchType,
            equivalentDomains: EquivalentDomains,
        ): Nothing = unusedFilterService()
    },
    getBreaches = object : GetBreaches {
        override fun invoke(forceRefresh: Boolean): Nothing = unusedFilterService()
    },
    cipherIncompleteCheck = object : CipherIncompleteCheck {
        override fun invoke(cipher: DSecret): Nothing = unusedFilterService()
    },
    cipherExpiringCheck = object : CipherExpiringCheck {
        override fun invoke(cipher: DSecret, now: Instant): Nothing = unusedFilterService()
    },
    cipherUnsecureUrlCheck = object : CipherUnsecureUrlCheck {
        override fun invoke(url: String): Nothing = unusedFilterService()
    },
    tldService = object : TldService {
        override val version: String = "unused"
        override fun getDomainName(host: String): Nothing = unusedFilterService()
    },
    getTwoFa = object : GetTwoFa {
        override fun invoke(): Nothing = unusedFilterService()
    },
    getPasskeys = object : GetPasskeys {
        override fun invoke(): Nothing = unusedFilterService()
    },
    cipherUrlDuplicateCheck = object : CipherUrlDuplicateCheck {
        override fun invoke(
            first: DSecret.Uri,
            second: DSecret.Uri,
            matchType: DSecret.Uri.MatchType,
            equivalentDomains: EquivalentDomains,
        ): Nothing = unusedFilterService()
    },
    cipherUrlBroadCheck = object : CipherUrlBroadCheck {
        override fun invoke(
            ciphers: List<DSecret>,
            matchType: DSecret.Uri.MatchType,
            equivalentDomains: EquivalentDomainsBuilder,
        ): Nothing = unusedFilterService()
    },
    getWatchtowerAlerts = object : GetWatchtowerAlerts {
        override fun invoke(): Nothing = unusedFilterService()
    },
)
