package com.artemchep.keyguard.apple.agent

import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.util.agentipc.AgentIpcExpectedPeer
import com.artemchep.keyguard.util.agentipc.asAgentIpcExpectedPeer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSFileHandle
import platform.Foundation.NSPipe
import platform.Foundation.NSTask
import platform.Foundation.NSURL
import platform.Foundation.closeFile
import platform.Foundation.fileDescriptor
import platform.posix.F_SETNOSIGPIPE
import platform.posix.SIGKILL
import platform.posix.fcntl
import platform.posix.kill

/** A bundled helper whose lifetime is bound to its stdin pipe. NSTask retains and
 * reaps this exact child; checking isRunning before signaling it avoids
 * accidentally signaling a reused PID. */
@OptIn(ExperimentalForeignApi::class)
internal class AgentHelperProcess(
    private val name: String,
    binary: String,
    arguments: List<String>,
) {
    private val task = NSTask()
    private val stdin = NSPipe()
    private val stdout = NSPipe()
    private val stderr = NSPipe()
    private val exited = CompletableDeferred<Unit>()
    private var stderrJob: Job? = null

    init {
        task.setExecutableURL(NSURL.fileURLWithPath(binary))
        task.setArguments(arguments)
        task.setStandardInput(stdin)
        task.setStandardOutput(stdout)
        task.setStandardError(stderr)
        task.setTerminationHandler { exited.complete(Unit) }
    }

    /** This child as the one peer its IPC server serves: its PID while it runs. */
    val expectedPeer: AgentIpcExpectedPeer = task.asAgentIpcExpectedPeer()

    val isRunning: Boolean get() = task.isRunning()

    /** Launches the helper, calls [onLaunched] once this exact child exists, sends
     * it [token], and waits for its readiness line. Stderr is drained in [scope]
     * until [stop]. */
    suspend fun launch(scope: CoroutineScope, token: ByteArray, onLaunched: () -> Unit = {}) {
        check(task.launchAndReturnError(null)) { "Could not launch the bundled $name" }
        // Its IPC server accepts nothing before then; the child may connect before
        // it reports readiness.
        onLaunched()
        val inputFd = stdin.fileHandleForWriting.fileDescriptor
        val outputFd = stdout.fileHandleForReading.fileDescriptor
        val errorFd = stderr.fileHandleForReading.fileDescriptor
        nonblocking(inputFd)
        // Darwin's per-descriptor flag also protects pipes if the child dies
        // while the launch token is being written. Never alter SIGPIPE globally.
        check(fcntl(inputFd, F_SETNOSIGPIPE, 1) == 0) { "Could not protect the $name launch pipe" }
        nonblocking(outputFd)
        nonblocking(errorFd)
        // Drain without retaining potentially sensitive/unbounded helper output.
        stderrJob = scope.launch { drain(errorFd) }
        val tokenLine = (token.toHex() + "\n").encodeToByteArray()
        try {
            check(withTimeoutOrNull(STARTUP_TIMEOUT_MS) {
                check(writeFully(inputFd, tokenLine)) { "$name closed its launch pipe" }
                val received = readFully(outputFd, READY_LINE.size)
                check(received?.contentEquals(READY_LINE) == true) { "$name did not report readiness" }
                check(task.isRunning()) { "$name exited during startup" }
                true
            } == true) { "Timed out waiting for $name readiness" }
        } finally {
            tokenLine.fill(0)
        }
    }

    /** Suspends until the helper exits and returns its termination status. */
    suspend fun awaitExit(): Int {
        exited.await()
        return task.terminationStatus
    }

    /** Closes stdin, which the helper treats as parent death, then terminates it. */
    suspend fun stop() {
        closeHandle(stdin.fileHandleForWriting)
        if (task.isRunning()) {
            runCatching { task.terminate() }
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) { exited.await() }
        }
        if (task.isRunning()) kill(task.processIdentifier, SIGKILL)
        stderrJob?.cancelAndJoin()
        closeHandle(stdout.fileHandleForReading)
        closeHandle(stderr.fileHandleForReading)
    }
}

private fun closeHandle(handle: NSFileHandle) { runCatching { handle.closeFile() } }

private val READY_LINE = "KEYGUARD_AGENT_READY 1\n".encodeToByteArray()

private const val STARTUP_TIMEOUT_MS = 10_000L

private const val SHUTDOWN_TIMEOUT_MS = 2_000L
