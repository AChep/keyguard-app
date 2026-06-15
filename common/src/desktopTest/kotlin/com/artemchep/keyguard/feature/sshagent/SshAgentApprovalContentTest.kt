package com.artemchep.keyguard.feature.sshagent

import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SshAgentApprovalContentTest {
    @Test
    fun `buildSshAgentApprovalCallerInfo shows process details for process-only caller`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 321,
            uid = 100,
            gid = 100,
            processName = "ssh",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )

        val info = buildSshAgentApprovalCallerInfo(caller)

        assertNotNull(info)
        assertEquals("ssh", info.primaryLabel)
        assertEquals("pid 321 • /data/data/com.termux/files/usr/bin/ssh", info.secondaryLabel)
    }

    @Test
    fun `buildSshAgentApprovalCallerInfo prefers app name but still shows process metadata`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 456,
            uid = 200,
            gid = 200,
            processName = "com.termux",
            appName = "Termux",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )

        val info = buildSshAgentApprovalCallerInfo(caller)

        assertNotNull(info)
        assertEquals("Termux", info.primaryLabel)
        assertEquals("com.termux • pid 456 • /data/data/com.termux/files/usr/bin/ssh", info.secondaryLabel)
    }

    @Test
    fun `buildSshAgentApprovalCallerInfo returns null for null caller`() {
        assertNull(buildSshAgentApprovalCallerInfo(null))
    }
}
