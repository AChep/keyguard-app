package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.agent.runAgentCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Asks the agent executable for the socket or the named pipe that it serves. */
class DesktopSshAgentSocketPathProvider(
    private val sshAgentManager: SshAgentManager,
    private val logRepository: LogRepository,
) : SshAgentSocketPathProvider {
    companion object {
        private const val TAG = "SshAgentSocketPath"

        // It only prints a path.
        private const val PRINT_SOCKET_TIMEOUT_SECONDS = 15L
    }

    private val mutex = Mutex()
    private var cachedPath: String? = null

    /**
     * The socket is fixed for the app's lifetime, so resolve it once.
     * Throws if the executable can't report it.
     */
    suspend fun require(): String = mutex.withLock {
        cachedPath ?: withContext(Dispatchers.IO) {
            val executable = requireNotNull(sshAgentManager.defaultBinaryPath) {
                "SSH agent executable is unavailable"
            }
            val override = sshAgentManager.defaultAgentSocketPath
            val command = listOf(executable.toString(), "--print-socket") +
                (override?.let { listOf("--ssh-socket", it.toString()) } ?: emptyList())
            val result = runAgentCommand(command, PRINT_SOCKET_TIMEOUT_SECONDS)
            check(result.exitCode == 0) {
                "SSH agent failed to print its socket: ${result.output.trim()}"
            }
            result.output.trim()
        }.also { cachedPath = it }
    }

    override suspend fun invoke(): String? = runCatchingNonFatal { require() }
        .onFailure { e ->
            logRepository.post(
                tag = TAG,
                message = "Failed to resolve the SSH agent socket: ${e.message}",
                level = LogLevel.WARNING,
            )
        }
        .getOrNull()
}
