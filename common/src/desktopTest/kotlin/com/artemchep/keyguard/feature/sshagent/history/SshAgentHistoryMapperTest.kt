package com.artemchep.keyguard.feature.sshagent.history

import com.artemchep.keyguard.common.service.agent.CallerIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshAgentHistoryMapperTest {
    @Test
    fun `history prioritizes key identity ahead of long caller paths`() {
        val path = "C:\\Program Files\\WindowsApps\\" + "long-installation-directory".repeat(10)
        val description = buildSshUsageHistoryDescription("Sign data", "Work key", "SHA256:key", path)
        assertTrue(description.indexOf("Work key") < description.indexOf(path))
        assertTrue(description.indexOf("SHA256:key") < description.indexOf(path))
        assertTrue(description.contains(path))
        assertEquals("List keys", buildSshUsageHistoryDescription("List keys", null, null, null))
        assertEquals("Sign data • SHA256:key", buildSshUsageHistoryDescription(
            "Sign data", "SHA256:key", "SHA256:key", null,
        ))
    }

    @Test
    fun `build caller info summarizes app and process names`() {
        val caller = CallerIdentity(
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
        val caller = CallerIdentity(
            processName = "ssh",
        )
        val encoded = Json.encodeToString(caller)

        val info = buildSshUsageHistoryCallerInfo(encoded, Json)

        assertEquals("ssh", info?.primaryLabel)
        assertNull(info?.secondaryLabel)
    }

    @Test
    fun `build caller info shows Android app label and package`() {
        val caller = CallerIdentity(
            pid = 123,
            uid = 456,
            appName = "Example Client",
            appBundlePath = "com.example.client",
        )
        val encoded = Json.encodeToString(caller)

        val info = buildSshUsageHistoryCallerInfo(encoded, Json)

        assertEquals("Example Client", info?.primaryLabel)
        assertEquals("com.example.client", info?.secondaryLabel)
    }

    @Test
    fun `build caller info returns null for malformed json`() {
        val info = buildSshUsageHistoryCallerInfo("{not-json", Json)

        assertNull(info)
    }
}
