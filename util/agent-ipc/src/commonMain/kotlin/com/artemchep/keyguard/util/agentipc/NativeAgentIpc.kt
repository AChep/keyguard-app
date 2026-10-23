package com.artemchep.keyguard.util.agentipc

internal const val AGENT_IPC_ABI_VERSION = 1

internal const val AGENT_IPC_INVALID_ARGUMENT = -1L
internal const val AGENT_IPC_IO = -2L
internal const val AGENT_IPC_CLOSED = -3L
internal const val AGENT_IPC_PEER_CLOSED = -4L
internal const val AGENT_IPC_INVALID_HANDLE = -5L
internal const val AGENT_IPC_BUSY = -6L
internal const val AGENT_IPC_PERMISSION = -7L

/**
 * The raw native bridge: ABI v1 codes, one function per native export.
 *
 * Every call except [lastError] clears this thread's diagnostic first. After a
 * negative code, read [lastError] on the same thread before the next call;
 * [agentIpcFailure] does. Calls block the calling thread.
 *
 * On desktop a call throws [AgentIpcException] with [AgentIpcFailureKind.UNAVAILABLE]
 * when the library is missing or has another ABI version. Apple targets link it
 * statically and check its ABI version before every call.
 */
internal expect object NativeAgentIpc {
    /** The ABI version of the native library; [AgentIpcLibrary.ensureAvailable] checks it. */
    fun abiVersion(): Int

    /** This thread's diagnostic from the last failed call. Read it before the next call. */
    fun lastError(): String?

    /**
     * Creates and secures an endpoint: a positive listener handle or a negative code.
     * A null [parent] is none (Windows); a null [exactName] asks for a random name.
     */
    fun listen(parent: String?, prefix: String, exactName: String?): Long

    /** The listener's socket path or pipe name, or null with a diagnostic. */
    fun endpoint(listener: Long): String?

    /** Blocks until a client connects: a positive connection handle or a negative code. */
    fun accept(listener: Long): Long

    /** Closes a listener and consumes its handle: 0, or `-5` once consumed. */
    fun closeListener(listener: Long): Long

    /** The peer's process ID from the accept snapshot; `-7` when it is not verifiable. */
    fun peerPid(connection: Long): Long

    /** Reads up to [length] bytes into [buffer] at [offset]: the count, 0 at end of stream. */
    fun read(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long

    /** Writes [length] bytes of [buffer] from [offset]: 0. Length 0 is a no-op. */
    fun write(connection: Long, buffer: ByteArray, offset: Int, length: Int): Long

    /** Waits without consuming input: 1 for input, 0 at end of stream. */
    fun awaitInput(connection: Long): Long

    /** Closes a connection and consumes its handle: 0, or `-5` once consumed. */
    fun close(connection: Long): Long
}

/**
 * What the Kotlin API throws for a negative native [code], with this thread's
 * diagnostic. A consumed handle reads as closed; busy is a bug of the caller.
 */
internal fun agentIpcFailure(code: Long): Exception {
    if (code == AGENT_IPC_BUSY) return IllegalStateException("agent_ipc_busy")
    val kind = when (code) {
        AGENT_IPC_INVALID_ARGUMENT -> AgentIpcFailureKind.INVALID_ARGUMENT
        AGENT_IPC_IO -> AgentIpcFailureKind.IO
        AGENT_IPC_CLOSED, AGENT_IPC_INVALID_HANDLE -> AgentIpcFailureKind.CLOSED
        AGENT_IPC_PEER_CLOSED -> AgentIpcFailureKind.PEER_CLOSED
        AGENT_IPC_PERMISSION -> AgentIpcFailureKind.PERMISSION
        else -> AgentIpcFailureKind.INTERNAL
    }
    return AgentIpcException(kind, diagnostic = NativeAgentIpc.lastError())
}

/** Returns a non-negative native result, or throws its [agentIpcFailure]. */
internal fun Long.checkedAgentIpcResult(): Long {
    if (this < 0L) throw agentIpcFailure(this)
    return this
}
