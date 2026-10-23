package com.artemchep.keyguard.util.agentipc

/** The native library behind the agent IPC transport. */
object AgentIpcLibrary {
    /**
     * Loads the native library and checks its ABI version. Throws [AgentIpcException]
     * with [AgentIpcFailureKind.UNAVAILABLE] when it is missing or incompatible.
     */
    fun ensureAvailable() {
        if (NativeAgentIpc.abiVersion() != AGENT_IPC_ABI_VERSION) {
            throw AgentIpcException(AgentIpcFailureKind.UNAVAILABLE)
        }
    }
}
