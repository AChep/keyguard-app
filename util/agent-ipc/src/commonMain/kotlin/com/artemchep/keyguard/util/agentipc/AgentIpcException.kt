package com.artemchep.keyguard.util.agentipc

/** The stable category of an [AgentIpcException]. */
enum class AgentIpcFailureKind {
    /** An argument breaks the contract, such as an invalid endpoint name. */
    INVALID_ARGUMENT,

    /** An operating-system call failed. */
    IO,

    /** Closed locally, also while the call was blocked on it. */
    CLOSED,

    /** The peer closed the connection before a write completed. */
    PEER_CLOSED,

    /** The endpoint is taken or unsafe. */
    PERMISSION,

    /** The native library is missing or has another ABI version. */
    UNAVAILABLE,

    /** A bug, or a listener that can no longer accept. */
    INTERNAL,
}

/**
 * A failed agent IPC call. The message is fixed per [kind] and never carries bytes,
 * paths or names; [diagnostic] is the native `<operation>: <reason>[ (os N)]` label.
 */
class AgentIpcException(
    val kind: AgentIpcFailureKind,
    val diagnostic: String? = null,
    cause: Throwable? = null,
) : Exception("agent_ipc_" + kind.name.lowercase(), cause)
