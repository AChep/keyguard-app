package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import com.artemchep.keyguard.common.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentCallerAuthorizationTest {
    @Test
    fun `cache policy storage keys are stable and unknown persisted values fail closed`() {
        AgentApprovalCachePolicy.entries.forEach { policy ->
            assertEquals(policy, AgentApprovalCachePolicy.fromStorageKey(policy.storageKey))
        }
        assertEquals(
            AgentApprovalCachePolicy.ApplicationAndTerminalSession,
            AgentApprovalCachePolicy.fromStorageKey(null),
        )
        assertEquals(
            AgentApprovalCachePolicy.Connection,
            AgentApprovalCachePolicy.fromStorageKey("future-value"),
        )
    }

    @Test
    fun `display-only caller metadata never creates an approval identity`() {
        assertNull(SshAgentMessages.CallerIdentity(appName = "Terminal").toApprovalCacheIdentity())
    }

    @Test
    fun `connection and optional context fingerprints have exact lengths`() {
        listOf(0, 1, 31, 33, 64).forEach { size ->
            assertNull(sshCaller(connectionFingerprint = ByteArray(size)).toApprovalCacheIdentity())
        }
        assertNotNull(sshCaller(connectionFingerprint = fingerprint(1)).toApprovalCacheIdentity())

        listOf(1, 31, 33, 64).forEach { size ->
            assertNull(
                sshCaller(
                    authorizationContextFingerprint = ByteArray(size),
                ).toApprovalCacheIdentity(),
            )
        }
        assertNotNull(
            sshCaller(
                authorizationContextFingerprint = fingerprint(2),
            ).toApprovalCacheIdentity(),
        )
    }

    @Test
    fun `strict default selects terminal session before owning application`() {
        val identity = sshCaller(subjects = allSubjects()).toApprovalCacheIdentity()

        assertNotNull(identity)
        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession,
            identity.cacheSubject.kind,
        )
        assertEquals(fingerprint(5).toHex(), identity.cacheSubject.fingerprintHex)
    }

    @Test
    fun `strict default selects application for every non-terminal application`() {
        val identity = sshCaller(
            appName = "Tower",
            subjects = listOf(
                processSubject(),
                applicationInstanceSubject(),
                stableApplicationSubject(),
            ),
        ).toApprovalCacheIdentity()

        assertNotNull(identity)
        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.StableApplication,
            identity.cacheSubject.kind,
        )
        assertEquals(fingerprint(4).toHex(), identity.cacheSubject.fingerprintHex)
    }

    @Test
    fun `application policy shares terminal sessions through the owning application`() {
        val identity = sshCaller(subjects = allSubjects()).toApprovalCacheIdentity(
            policy = AgentApprovalCachePolicy.Application,
        )

        assertNotNull(identity)
        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.StableApplication,
            identity.cacheSubject.kind,
        )
    }

    @Test
    fun `application policies use safe narrower fallbacks`() {
        val terminal = sshCaller(
            subjects = listOf(processSubject(), terminalSessionSubject()),
        ).toApprovalCacheIdentity(AgentApprovalCachePolicy.Application)
        val process = sshCaller(
            subjects = listOf(processSubject()),
        ).toApprovalCacheIdentity(AgentApprovalCachePolicy.Application)
        val connection = sshCaller().toApprovalCacheIdentity(AgentApprovalCachePolicy.Application)

        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession,
            terminal?.cacheSubject?.kind,
        )
        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.Process,
            process?.cacheSubject?.kind,
        )
        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.Connection,
            connection?.cacheSubject?.kind,
        )
    }

    @Test
    fun `connection and process policies cannot select broader subjects`() {
        val connection = sshCaller(subjects = allSubjects()).toApprovalCacheIdentity(
            AgentApprovalCachePolicy.Connection,
        )
        val process = sshCaller(subjects = allSubjects()).toApprovalCacheIdentity(
            AgentApprovalCachePolicy.Process,
        )

        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.Connection,
            connection?.cacheSubject?.kind,
        )
        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.Process,
            process?.cacheSubject?.kind,
        )
    }

    @Test
    fun `macos joint proof exports select only the available authorization subjects`() {
        val process = processSubject().copy(
            evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_AUDIT_TOKEN,
        )
        val terminal = terminalSessionSubject().copy(
            evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_TERMINAL_SESSION,
        )
        val applications = listOf(
            stableApplicationSubject().copy(
                evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_CODE_SIGNING,
            ),
            applicationInstanceSubject().copy(
                evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_APPLICATION_ANCESTRY,
            ),
        )

        applications.forEach { application ->
            val caller = sshCaller(subjects = listOf(process, terminal, application))
            assertEquals(
                AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession,
                caller.toApprovalCacheIdentity()?.cacheSubject?.kind,
            )
            assertEquals(
                application.fingerprint.toHex(),
                caller.toApprovalCacheIdentity(AgentApprovalCachePolicy.Application)
                    ?.cacheSubject?.fingerprintHex,
            )
        }

        val withoutApplication = sshCaller(subjects = listOf(process, terminal))
        listOf(AgentApprovalCachePolicy.Default, AgentApprovalCachePolicy.Application).forEach { policy ->
            assertEquals(
                AgentApprovalCacheIdentity.CacheSubject.Kind.TerminalSession,
                withoutApplication.toApprovalCacheIdentity(policy)?.cacheSubject?.kind,
            )
        }
    }

    @Test
    fun `verified application label cannot broaden process-only macos authorization`() {
        val caller = sshCaller(
            appName = "iTerm2",
            subjects = listOf(
                processSubject().copy(
                    evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.MACOS_AUDIT_TOKEN,
                ),
            ),
        )

        listOf(AgentApprovalCachePolicy.Default, AgentApprovalCachePolicy.Application).forEach { policy ->
            assertEquals(
                AgentApprovalCacheIdentity.CacheSubject.Kind.Process,
                caller.toApprovalCacheIdentity(policy)?.cacheSubject?.kind,
            )
        }
    }

    @Test
    fun `duplicate subject kinds are rejected as ambiguous`() {
        val duplicateProcess = processSubject().copy(fingerprint = fingerprint(9))

        assertNull(
            sshCaller(
                subjects = listOf(processSubject(), duplicateProcess),
            ).toApprovalCacheIdentity(),
        )
    }

    @Test
    fun `subject set is strictly bounded`() {
        assertNull(
            sshCaller(
                subjects = allSubjects() + processSubject().copy(fingerprint = fingerprint(9)),
            ).toApprovalCacheIdentity(),
        )
    }

    @Test
    fun `fingerprints must be exact and domain separated`() {
        assertNull(
            sshCaller(
                subjects = listOf(processSubject().copy(fingerprint = ByteArray(31))),
            ).toApprovalCacheIdentity(),
        )
        assertNull(
            sshCaller(
                connectionFingerprint = fingerprint(2),
                subjects = listOf(processSubject()),
            ).toApprovalCacheIdentity(),
        )
        assertNull(
            sshCaller(
                subjects = listOf(
                    processSubject(),
                    applicationInstanceSubject().copy(fingerprint = fingerprint(2)),
                ),
            ).toApprovalCacheIdentity(),
        )
    }

    @Test
    fun `unknown or mismatched subject evidence is rejected`() {
        assertNull(
            sshCaller(
                subjects = listOf(processSubject().copy(kind = 99)),
            ).toApprovalCacheIdentity(),
        )
        assertNull(
            sshCaller(
                subjects = listOf(processSubject().copy(evidenceSource = 99)),
            ).toApprovalCacheIdentity(),
        )
        assertNull(
            sshCaller(
                subjects = listOf(
                    processSubject().copy(
                        kind = AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION,
                    ),
                ),
            ).toApprovalCacheIdentity(),
        )
        assertNull(
            sshCaller(
                subjects = listOf(
                    processSubject().copy(
                        evidenceSource =
                            AgentCallerAuthorizationSchema.EvidenceSource.WINDOWS_NAMED_PIPE_TOKEN,
                    ),
                ),
            ).toApprovalCacheIdentity(),
        )
    }

    @Test
    fun `display metadata is excluded while protocol context remains in equality`() {
        val first = sshCaller(
            appName = "Terminal",
            subjects = listOf(stableApplicationSubject()),
            authorizationContextFingerprint = fingerprint(8),
        ).toApprovalCacheIdentity()
        val renamed = sshCaller(
            appName = "Spoofed Terminal",
            subjects = listOf(stableApplicationSubject()),
            authorizationContextFingerprint = fingerprint(8),
        ).toApprovalCacheIdentity()
        val anotherContext = sshCaller(
            appName = "Terminal",
            subjects = listOf(stableApplicationSubject()),
            authorizationContextFingerprint = fingerprint(9),
        ).toApprovalCacheIdentity()

        assertEquals(first, renamed)
        assertNotEquals(first, anotherContext)
    }

    @Test
    fun `gpg caller uses the same multi-subject selection`() {
        val caller = GpgAgentMessages.CallerIdentity(
            appName = "Fork",
            authorization = CallerAuthorization(
                connectionFingerprint = fingerprint(1),
                subjects = listOf(
                    CallerAuthorizationSubject(
                        kind = AgentCallerAuthorizationSchema.SubjectKind.APPLICATION_INSTANCE,
                        evidenceSource =
                            AgentCallerAuthorizationSchema.EvidenceSource.MACOS_APPLICATION_ANCESTRY,
                        fingerprint = fingerprint(3),
                    ),
                ),
            ),
        )

        val identity = caller.toApprovalCacheIdentity()

        assertNotNull(identity)
        assertEquals(
            AgentApprovalCacheIdentity.CacheSubject.Kind.ApplicationInstance,
            identity.cacheSubject.kind,
        )
        assertEquals(
            AgentApprovalCacheIdentity.EvidenceSource.MacosApplicationAncestry,
            identity.evidenceSource,
        )
    }

    private fun sshCaller(
        appName: String = "Terminal",
        connectionFingerprint: ByteArray = fingerprint(1),
        subjects: List<CallerAuthorizationSubject> = emptyList(),
        authorizationContextFingerprint: ByteArray = byteArrayOf(),
    ): SshAgentMessages.CallerIdentity = SshAgentMessages.CallerIdentity(
        appName = appName,
        authorization = CallerAuthorization(
            connectionFingerprint = connectionFingerprint,
            subjects = subjects,
            authorizationContextFingerprint = authorizationContextFingerprint,
        ),
    )

    private fun allSubjects() = listOf(
        processSubject(),
        applicationInstanceSubject(),
        stableApplicationSubject(),
        terminalSessionSubject(),
    )

    private fun processSubject() = CallerAuthorizationSubject(
        kind = AgentCallerAuthorizationSchema.SubjectKind.PROCESS,
        evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.LINUX_PIDFD,
        fingerprint = fingerprint(2),
    )

    private fun applicationInstanceSubject() = CallerAuthorizationSubject(
        kind = AgentCallerAuthorizationSchema.SubjectKind.APPLICATION_INSTANCE,
        evidenceSource =
            AgentCallerAuthorizationSchema.EvidenceSource.LINUX_APPLICATION_ANCESTRY,
        fingerprint = fingerprint(3),
    )

    private fun stableApplicationSubject() = CallerAuthorizationSubject(
        kind = AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION,
        evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.LINUX_LSM,
        fingerprint = fingerprint(4),
    )

    private fun terminalSessionSubject() = CallerAuthorizationSubject(
        kind = AgentCallerAuthorizationSchema.SubjectKind.TERMINAL_SESSION,
        evidenceSource = AgentCallerAuthorizationSchema.EvidenceSource.LINUX_TERMINAL_SESSION,
        fingerprint = fingerprint(5),
    )

    private fun fingerprint(value: Int) = ByteArray(AgentCallerAuthorizationSchema.FINGERPRINT_SIZE) {
        value.toByte()
    }
}
