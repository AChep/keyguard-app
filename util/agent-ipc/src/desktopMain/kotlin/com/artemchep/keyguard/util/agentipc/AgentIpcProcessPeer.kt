package com.artemchep.keyguard.util.agentipc

/**
 * This started process as the one peer an [AgentIpcHost] serves: its process ID
 * while it is alive. A live [Process] still refers to the exact child, so its
 * process ID cannot have been reused.
 */
fun Process.asAgentIpcExpectedPeer(): AgentIpcExpectedPeer =
    AgentIpcExpectedPeer { takeIf { it.isAlive }?.pid() }

internal actual fun currentProcessId(): Long = ProcessHandle.current().pid()
