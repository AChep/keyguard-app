package com.artemchep.keyguard.feature.sshagent.history

import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SshAgentHistoryMapperTest {
    @Test
    fun `build caller info summarizes app and process names`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "ssh",
            executablePath = "/usr/bin/ssh",
            appName = "Terminal",
        )
        val encoded = Json.encodeToString(caller)

        val info = buildSshUsageHistoryCallerInfo(encoded, Json)

        assertEquals("Terminal", info?.primaryLabel)
        assertEquals("ssh", info?.secondaryLabel)
    }

    @Test
    fun `build caller info falls back to process name`() {
        val caller = SshAgentMessages.CallerIdentity(
            processName = "ssh",
        )
        val encoded = Json.encodeToString(caller)

        val info = buildSshUsageHistoryCallerInfo(encoded, Json)

        assertEquals("ssh", info?.primaryLabel)
        assertNull(info?.secondaryLabel)
    }

    @Test
    fun `build caller info returns null for malformed json`() {
        val info = buildSshUsageHistoryCallerInfo("{not-json", Json)

        assertNull(info)
    }
}
