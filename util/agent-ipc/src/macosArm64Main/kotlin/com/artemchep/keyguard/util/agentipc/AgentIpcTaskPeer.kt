@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.agentipc

import platform.Foundation.NSTask
import platform.posix.getpid

/**
 * This launched task as the one peer an [AgentIpcHost] serves: its process ID while
 * it runs. A running task is not reaped yet, so its process ID cannot have been
 * reused.
 */
fun NSTask.asAgentIpcExpectedPeer(): AgentIpcExpectedPeer =
    AgentIpcExpectedPeer { processIdentifier.toLong().takeIf { isRunning() } }

internal actual fun currentProcessId(): Long = getpid().toLong()
