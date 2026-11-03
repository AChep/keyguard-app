package com.artemchep.keyguard.android.agentbridge

import com.artemchep.keyguard.common.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentBridgeStatusTextTest {
    @Test
    fun `a single agent keeps its own title and text`() {
        AgentBridge.entries.forEach { bridge ->
            assertEquals(
                AgentBridgeStatusText(
                    title = bridge.text.title,
                    content = bridge.text.connecting,
                ),
                agentBridgeStatusText(setOf(bridge)),
            )
        }
    }

    @Test
    fun `overlapping agents share one title and text`() {
        assertEquals(
            AgentBridgeStatusText(
                title = R.string.notification_agent_bridge_title,
                content = R.string.notification_agent_bridge_content,
            ),
            agentBridgeStatusText(setOf(AgentBridge.SSH, AgentBridge.GPG)),
        )
    }

    @Test
    fun `no active agent has no text`() {
        assertFailsWith<IllegalArgumentException> {
            agentBridgeStatusText(emptySet())
        }
    }
}
