package com.artemchep.keyguard.util.agentipc

/**
 * The one process an [AgentIpcHost] serves: the exact helper the app started.
 *
 * Production code builds it only from a started process:
 * `Process.asAgentIpcExpectedPeer()` on desktop and `NSTask.asAgentIpcExpectedPeer()`
 * on macOS. The own-process peer exists only for in-process tests, behind
 * [AgentIpcOwnProcessTestApi].
 */
class AgentIpcExpectedPeer internal constructor(
    private val sample: () -> Long?,
) {
    /** The child's process ID while it runs, else null. */
    fun currentPid(): Long? = sample()?.takeIf { it > 0L }

    companion object {
        /** This process: for in-process tests whose client is the test process itself. */
        @AgentIpcOwnProcessTestApi
        fun currentProcessForTests(): AgentIpcExpectedPeer = AgentIpcExpectedPeer { currentProcessId() }
    }
}

/**
 * Marks the own-process peer. A production host must serve only the helper it
 * started; a test whose client is the test process itself opts in.
 */
@RequiresOptIn(
    message = "Own-process peers are for in-process tests only.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class AgentIpcOwnProcessTestApi

/** The ID of this process. */
internal expect fun currentProcessId(): Long
