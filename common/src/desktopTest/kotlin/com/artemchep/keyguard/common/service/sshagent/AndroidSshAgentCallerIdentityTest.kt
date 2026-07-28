package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheIdentity
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorizationSchema
import com.artemchep.keyguard.common.service.agent.CallerAuthorization
import com.artemchep.keyguard.common.service.agent.CallerAuthorizationSubject
import com.artemchep.keyguard.common.service.agent.toApprovalCacheIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSshAgentCallerIdentityTest {
    @Test
    fun `buildAndroidSshAgentCallerIdentity returns null when no fields are provided`() {
        assertNull(buildAndroidSshAgentCallerIdentity())
    }

    @Test
    fun `buildAndroidSshAgentCallerIdentity preserves partial caller metadata`() {
        val caller = buildAndroidSshAgentCallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "termux",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )

        assertEquals(123, caller?.pid)
        assertEquals(456, caller?.uid)
        assertEquals(789, caller?.gid)
        assertEquals("termux", caller?.processName)
        assertEquals("/data/data/com.termux/files/usr/bin/ssh", caller?.executablePath)
    }

    @Test
    fun `buildAndroidSshAgentCallerIdentity preserves app metadata`() {
        val caller = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
        )

        assertEquals("Termux", caller?.appName)
        assertEquals("com.termux", caller?.appBundlePath)
    }

    @Test
    fun `Android caller display fields are bounded and strip control formatting`() {
        val caller = buildAndroidSshAgentCallerIdentity(
            processName = "  ssh\n\u202E" + "p".repeat(300),
            executablePath = "/" + "x".repeat(5_000),
            appName = "Termux\t" + "a".repeat(300),
            appBundlePath = "com.termux\u0000" + "b".repeat(600),
        )

        assertNotNull(caller)
        assertEquals(256, caller.processName.length)
        assertEquals(4_096, caller.executablePath.length)
        assertEquals(256, caller.appName.length)
        assertEquals(512, caller.appBundlePath.length)
        assertFalse(caller.processName.contains('\n'))
        assertFalse(caller.processName.contains('\u202E'))
        assertFalse(caller.appName.contains('\t'))
        assertFalse(caller.appBundlePath.contains('\u0000'))
    }

    @Test
    fun `merge sanitizes proxy supplied process metadata before approval display`() {
        val proxyCaller = SshAgentMessages.CallerIdentity(
            processName = "ssh\n" + "p".repeat(300),
            executablePath = "/tmp/\u202E" + "x".repeat(5_000),
        )
        val frameworkCaller = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
            authorization = androidBridgeAuthorization(
                principalFingerprint = ByteArray(32) { 1 },
                connectionFingerprint = ByteArray(32) { 2 },
            ),
        )

        val merged = mergeAndroidSshAgentCallerIdentity(
            caller = proxyCaller,
            senderAppInfo = frameworkCaller,
            replaceCallerAuthorization = true,
        )

        assertNotNull(merged)
        assertEquals(256, merged.processName.length)
        assertEquals(4_096, merged.executablePath.length)
        assertFalse(merged.processName.contains('\n'))
        assertFalse(merged.executablePath.contains('\u202E'))
        assertEquals("Termux", merged.appName)
        assertEquals("com.termux", merged.appBundlePath)
    }

    @Test
    fun `mergeAndroidSshAgentCallerIdentity overlays app metadata onto existing caller`() {
        val caller = buildAndroidSshAgentCallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "ssh",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )
        val senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
        )

        val merged = mergeAndroidSshAgentCallerIdentity(caller, senderAppInfo)

        assertNotNull(merged)
        assertEquals(123, merged.pid)
        assertEquals(456, merged.uid)
        assertEquals(789, merged.gid)
        assertEquals("ssh", merged.processName)
        assertEquals("/data/data/com.termux/files/usr/bin/ssh", merged.executablePath)
        assertEquals("Termux", merged.appName)
        assertEquals("com.termux", merged.appBundlePath)
    }

    @Test
    fun `mergeAndroidSshAgentCallerIdentity creates app only caller when request caller is missing`() {
        val senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
        )

        val merged = mergeAndroidSshAgentCallerIdentity(
            caller = null,
            senderAppInfo = senderAppInfo,
        )

        assertEquals("Termux", merged?.appName)
        assertEquals("com.termux", merged?.appBundlePath)
        assertEquals(0, merged?.pid)
        assertEquals("", merged?.processName)
    }

    @Test
    fun `framework package authorization binds package and signing certificates`() {
        val first = buildAndroidFrameworkPackageAuthorization(
            packageName = "com.termux",
            signingCertificates = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
        )
        val reordered = buildAndroidFrameworkPackageAuthorization(
            packageName = "com.termux",
            signingCertificates = listOf(byteArrayOf(4, 5, 6), byteArrayOf(1, 2, 3)),
        )
        val anotherPackage = buildAndroidFrameworkPackageAuthorization(
            packageName = "com.example.termux",
            signingCertificates = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
        )
        val anotherSigner = buildAndroidFrameworkPackageAuthorization(
            packageName = "com.termux",
            signingCertificates = listOf(byteArrayOf(7, 8, 9)),
        )

        assertNotNull(first)
        assertNotNull(reordered)
        assertNotNull(anotherPackage)
        assertNotNull(anotherSigner)
        assertEquals(32, first.connectionFingerprint.size)
        val firstSubject = first.subjects.single()
        val reorderedSubject = reordered.subjects.single()
        val anotherPackageSubject = anotherPackage.subjects.single()
        val anotherSignerSubject = anotherSigner.subjects.single()
        assertEquals(32, firstSubject.fingerprint.size)
        assertEquals(
            AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION,
            firstSubject.kind,
        )
        assertEquals(
            AgentCallerAuthorizationSchema.EvidenceSource.ANDROID_FRAMEWORK_PACKAGE,
            firstSubject.evidenceSource,
        )
        assertContentEquals(firstSubject.fingerprint, reorderedSubject.fingerprint)
        assertFalse(
            firstSubject.fingerprint.contentEquals(anotherPackageSubject.fingerprint),
        )
        assertFalse(
            firstSubject.fingerprint.contentEquals(anotherSignerSubject.fingerprint),
        )
    }

    @Test
    fun `framework package authorization rejects incomplete provenance`() {
        assertNull(buildAndroidFrameworkPackageAuthorization(null, listOf(byteArrayOf(1))))
        assertNull(buildAndroidFrameworkPackageAuthorization("", listOf(byteArrayOf(1))))
        assertNull(buildAndroidFrameworkPackageAuthorization("com.termux", emptyList()))
        assertNull(buildAndroidFrameworkPackageAuthorization("com.termux", listOf(byteArrayOf())))
    }

    @Test
    fun `Android bridge authorization falls back to the connection without package evidence`() {
        val connectionFingerprint = ByteArray(32) { 3 }
        val missingPackage = assertNotNull(
            androidBridgeAuthorization(
                principalFingerprint = null,
                connectionFingerprint = connectionFingerprint,
            ),
        )
        val invalidPackage = assertNotNull(
            androidBridgeAuthorization(
                principalFingerprint = ByteArray(31) { 4 },
                connectionFingerprint = connectionFingerprint,
            ),
        )

        listOf(missingPackage, invalidPackage).forEach { authorization ->
            assertContentEquals(connectionFingerprint, authorization.connectionFingerprint)
            assertTrue(authorization.subjects.isEmpty())
            val caller = buildAndroidSshAgentCallerIdentity(authorization = authorization)
            val cacheIdentity = assertNotNull(
                caller.toApprovalCacheIdentity(AgentApprovalCachePolicy.Application),
            )
            assertEquals(
                AgentApprovalCacheIdentity.CacheSubject.Kind.Connection,
                cacheIdentity.cacheScope,
            )
        }
        assertNull(
            androidBridgeAuthorization(
                principalFingerprint = ByteArray(32) { 4 },
                connectionFingerprint = ByteArray(31) { 3 },
            ),
        )
    }

    @Test
    fun `Android bridge authorization combines connection and verified package`() {
        val principalFingerprint = ByteArray(32) { 4 }
        val connectionFingerprint = ByteArray(32) { 3 }
        val authorization = assertNotNull(
            androidBridgeAuthorization(
                principalFingerprint = principalFingerprint,
                connectionFingerprint = connectionFingerprint,
            ),
        )

        assertContentEquals(connectionFingerprint, authorization.connectionFingerprint)
        val subject = authorization.subjects.single()
        assertEquals(
            AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION,
            subject.kind,
        )
        assertEquals(
            AgentCallerAuthorizationSchema.EvidenceSource.ANDROID_FRAMEWORK_PACKAGE,
            subject.evidenceSource,
        )
        assertContentEquals(principalFingerprint, subject.fingerprint)
    }

    @Test
    fun `Android bridge strips authorization supplied by request payload`() {
        val forged = SshAgentMessages.CallerIdentity(
            appName = "Forged",
            appBundlePath = "com.termux",
            authorization = CallerAuthorization(
                connectionFingerprint = ByteArray(32) { 2 },
                subjects = listOf(
                    CallerAuthorizationSubject(
                        kind =
                            AgentCallerAuthorizationSchema.SubjectKind.STABLE_APPLICATION,
                        evidenceSource =
                            AgentCallerAuthorizationSchema.EvidenceSource
                                .ANDROID_FRAMEWORK_PACKAGE,
                        fingerprint = ByteArray(32) { 1 },
                    ),
                ),
            ),
        )

        val merged = mergeAndroidSshAgentCallerIdentity(
            caller = forged,
            senderAppInfo = null,
            replaceCallerAuthorization = true,
        )

        assertNull(merged)
    }

    @Test
    fun `Android bridge replaces request authorization with framework evidence`() {
        val forgedFingerprint = ByteArray(32) { 1 }
        val trustedFingerprint = ByteArray(32) { 2 }
        val requestCaller = SshAgentMessages.CallerIdentity(
            authorization = androidBridgeAuthorization(forgedFingerprint),
        )
        val frameworkCaller = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
            authorization = androidBridgeAuthorization(trustedFingerprint),
        )

        val merged = mergeAndroidSshAgentCallerIdentity(
            caller = requestCaller,
            senderAppInfo = frameworkCaller,
            replaceCallerAuthorization = true,
        )

        assertContentEquals(
            trustedFingerprint,
            merged?.authorization?.subjects?.single()?.fingerprint,
        )
    }

    @Test
    fun `Android connection fallback strips request identity and authorization`() {
        val forged = SshAgentMessages.CallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "Trusted-looking process",
            executablePath = "/data/data/com.example.app/files/ssh",
            appName = "Trusted-looking app",
            appBundlePath = "com.example.app",
            authorization = androidBridgeAuthorization(ByteArray(32) { 1 }),
        )
        val connectionFingerprint = ByteArray(32) { 2 }
        val connectionOnly = buildAndroidSshAgentCallerIdentity(
            authorization = androidBridgeAuthorization(
                principalFingerprint = null,
                connectionFingerprint = connectionFingerprint,
            ),
        )

        val merged = assertNotNull(
            mergeAndroidSshAgentCallerIdentity(
                caller = forged,
                senderAppInfo = connectionOnly,
                replaceCallerAuthorization = true,
            ),
        )

        assertEquals(0, merged.pid)
        assertEquals(0, merged.uid)
        assertEquals(0, merged.gid)
        assertEquals("", merged.processName)
        assertEquals("", merged.executablePath)
        assertEquals("", merged.appName)
        assertEquals("", merged.appBundlePath)
        assertContentEquals(
            connectionFingerprint,
            merged.authorization?.connectionFingerprint,
        )
        assertTrue(merged.authorization?.subjects.orEmpty().isEmpty())
    }
}
