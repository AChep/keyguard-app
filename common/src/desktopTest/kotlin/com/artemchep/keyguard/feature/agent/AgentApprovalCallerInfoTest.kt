package com.artemchep.keyguard.feature.agent

import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentApprovalCallerInfoTest {
    @Test
    fun `buildAgentApprovalCallerInfo shows process details for process-only caller`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 321,
            uid = 100,
            gid = 100,
            processName = "ssh",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )

        val info = buildAgentApprovalCallerInfo(caller)

        assertNotNull(info)
        assertEquals("ssh", info.primaryLabel)
        assertEquals("pid 321 • /data/data/com.termux/files/usr/bin/ssh", info.secondaryLabel)
    }

    @Test
    fun `buildAgentApprovalCallerInfo prefers app name but still shows process metadata`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 456,
            uid = 200,
            gid = 200,
            processName = "com.termux",
            appName = "Termux",
            appBundlePath = "com.termux",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )

        val info = buildAgentApprovalCallerInfo(caller)

        assertNotNull(info)
        assertEquals("Termux", info.primaryLabel)
        assertEquals("com.termux • pid 456 • /data/data/com.termux/files/usr/bin/ssh", info.secondaryLabel)
    }

    @Test
    fun `buildAgentApprovalCallerInfo keeps authenticated label ahead of OS name`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 456,
            uid = 200,
            gid = 200,
            processName = "ssh",
            appName = "com.apple.Terminal",
            appBundlePath = "/System/Applications/Utilities/Terminal.app",
            executablePath = "/usr/bin/ssh",
        )

        val info = buildAgentApprovalCallerInfo(
            caller = caller,
            resolvedAppName = "Terminal",
        )

        assertNotNull(info)
        assertEquals("com.apple.Terminal", info.primaryLabel)
        assertEquals(
            "Terminal • /System/Applications/Utilities/Terminal.app • " +
                "ssh • pid 456 • /usr/bin/ssh",
            info.secondaryLabel,
        )
    }

    @Test
    fun `buildAgentApprovalCallerInfo escapes controls and bidi and bounds labels`() {
        val caller = SshAgentMessages.CallerIdentity(
            processName = "ssh\nspoof\u202Etxt",
            appName = "A".repeat(300),
            executablePath = "/tmp/ssh\u0000suffix",
        )

        val info = buildAgentApprovalCallerInfo(
            caller = caller,
            resolvedAppName = "Friendly\rName",
        )

        assertNotNull(info)
        assertEquals(256, info.primaryLabel?.length)
        assertEquals("A".repeat(255) + "…", info.primaryLabel)
        assertEquals(
            "Friendly\\u000dName • ssh\\u000aspoof\\u202etxt • /tmp/ssh\\u0000suffix",
            info.secondaryLabel,
        )
    }

    @Test
    fun `buildAgentApprovalCallerInfo uses shared field-specific path limits`() {
        val caller = SshAgentMessages.CallerIdentity(
            appBundlePath = "b".repeat(600),
            executablePath = "/" + "x".repeat(5_000),
        )

        val info = buildAgentApprovalCallerInfo(caller)

        assertNotNull(info)
        assertEquals(512, info.primaryLabel?.length)
        assertEquals("b".repeat(511) + "…", info.primaryLabel)
        assertEquals(4_096, info.secondaryLabel?.length)
        assertEquals("/" + "x".repeat(4_094) + "…", info.secondaryLabel)
    }

    @Test
    fun `buildAgentApprovalCallerInfo returns null for null caller`() {
        assertNull(buildAgentApprovalCallerInfo(null))
    }
}
