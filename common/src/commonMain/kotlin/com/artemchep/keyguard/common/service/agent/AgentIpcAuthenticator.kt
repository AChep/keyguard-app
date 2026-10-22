package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.util.foundation.constantTimeEquals
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

enum class AgentIpcAuthentication {
    ACCEPTED,
    REVISION_MISMATCH,
    TOKEN_MISMATCH,
}

/**
 * Checks the first request of a private agent connection: the token the app
 * passed to the agent at launch, and the protocol revision. Holds its own
 * copy of the token until [close]. Connections on other threads may share
 * it: [check] and [close] never overlap.
 */
class AgentIpcAuthenticator(
    authToken: ByteArray,
) {
    companion object {
        const val TOKEN_SIZE = 32
    }

    init {
        require(authToken.size == TOKEN_SIZE) { "Agent IPC authentication requires a $TOKEN_SIZE-byte token" }
    }

    private val lock = SynchronizedObject()
    private val token = authToken.copyOf()
    private var closed = false

    /**
     * Compares the token in constant time. A revision mismatch is reported
     * before a token mismatch. The supplied token is left as it is: the
     * session that decoded the request erases it.
     */
    fun check(request: AgentIpcMessages.AuthenticateRequest): AgentIpcAuthentication {
        val tokenMatches = synchronized(lock) {
            token.constantTimeEquals(request.token) && !closed
        }
        return when {
            request.protocolRevision != AgentIpcMessages.PROTOCOL_REVISION ->
                AgentIpcAuthentication.REVISION_MISMATCH

            !tokenMatches -> AgentIpcAuthentication.TOKEN_MISMATCH
            else -> AgentIpcAuthentication.ACCEPTED
        }
    }

    /** Erases the token; every later check fails. */
    fun close() = synchronized(lock) {
        closed = true
        token.fill(0)
    }
}

/** [AgentIpcAuthenticator.check]s [request] and logs the outcome; true when it is accepted. */
internal fun AgentIpcAuthenticator.checkAndLog(
    request: AgentIpcMessages.AuthenticateRequest,
    logRepository: LogRepository,
    tag: String,
    agentName: String,
): Boolean {
    val result = check(request)
    when (result) {
        AgentIpcAuthentication.REVISION_MISMATCH -> {
            val message = "$agentName authentication failed: protocol revision mismatch " +
                "(expected=${AgentIpcMessages.PROTOCOL_REVISION}, actual=${request.protocolRevision})"
            logRepository.post(tag, message, LogLevel.ERROR)
        }

        AgentIpcAuthentication.TOKEN_MISMATCH -> {
            val message = "$agentName authentication failed: token mismatch"
            logRepository.post(tag, message, LogLevel.ERROR)
        }

        AgentIpcAuthentication.ACCEPTED -> {
            val message = "$agentName authentication successful"
            logRepository.post(tag, message, LogLevel.INFO)
        }
    }
    return result == AgentIpcAuthentication.ACCEPTED
}
