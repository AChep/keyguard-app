package com.artemchep.keyguard.common.service.sshagent

/**
 * Resolves the socket or the named pipe that the SSH agent listens on, or
 * returns `null` if it is unknown.
 */
fun interface SshAgentSocketPathProvider {
    suspend operator fun invoke(): String?
}
