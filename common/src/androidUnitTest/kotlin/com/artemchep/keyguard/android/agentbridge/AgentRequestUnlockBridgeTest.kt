package com.artemchep.keyguard.android.agentbridge

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.sshagent.SshAgentApprovalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class AgentRequestUnlockBridgeTest {
    private val ssh = AgentBridgePrompt.Ssh(
        SshAgentApprovalRequest(
            id = "ssh",
            keyName = "key",
            keyFingerprint = "SHA256:ssh",
            accountId = null,
            cipherId = null,
            caller = null,
            expiresAt = Instant.DISTANT_FUTURE,
        ),
    )

    private val gpg = AgentBridgePrompt.Gpg(
        GpgAgentApprovalRequest(
            id = "gpg",
            operation = GpgAgentOperation.SIGN,
            keyName = "key",
            keyFingerprint = "gpg",
            keygrip = "A".repeat(40),
            accountId = null,
            cipherId = null,
            caller = null,
            expiresAt = Instant.DISTANT_FUTURE,
        ),
    )

    @Test
    fun `the request on screen names the agent`() {
        assertEquals(
            AgentBridge.GPG,
            agentRequestUnlockBridge(shown = gpg, pending = listOf(ssh), recovery = AgentBridge.SSH),
        )
    }

    @Test
    fun `without a request on screen the next waiting one names the agent`() {
        assertEquals(
            AgentBridge.GPG,
            agentRequestUnlockBridge(shown = null, pending = listOf(gpg, ssh), recovery = AgentBridge.SSH),
        )
    }

    @Test
    fun `without waiting requests the recovery launch names the agent`() {
        assertEquals(
            AgentBridge.GPG,
            agentRequestUnlockBridge(shown = null, pending = emptyList(), recovery = AgentBridge.GPG),
        )
    }

    @Test
    fun `no agent is named when none is known`() {
        assertNull(agentRequestUnlockBridge(shown = null, pending = emptyList(), recovery = null))
    }
}
