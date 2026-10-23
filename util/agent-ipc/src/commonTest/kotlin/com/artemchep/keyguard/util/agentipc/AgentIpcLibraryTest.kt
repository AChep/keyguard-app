package com.artemchep.keyguard.util.agentipc

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentIpcLibraryTest {
    @Test
    fun libraryIsAvailable() {
        AgentIpcLibrary.ensureAvailable()
    }

    @Test
    fun nativeAbiMatchesTheBinding() {
        assertEquals(AGENT_IPC_ABI_VERSION, NativeAgentIpc.abiVersion())
    }
}
