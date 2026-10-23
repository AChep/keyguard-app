package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.util.agentipc.AgentIpcLibrary
import kotlin.test.Test

class AgentIpcLinkTest {
    /** A real call site, so the test binary links the agent IPC archive next to the others. */
    @Test
    fun agentIpcLibraryIsLinkedAndCompatible() {
        AgentIpcLibrary.ensureAvailable()
    }
}
