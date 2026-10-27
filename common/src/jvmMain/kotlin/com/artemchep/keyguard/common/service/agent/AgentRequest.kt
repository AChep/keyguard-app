package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.platform.util.isRelease
import java.util.logging.Logger
import kotlin.time.Instant

/** A pending agent approval prompt, as an approval window shows it. */
interface AgentRequest {
    val id: String

    val caller: AgentCallerIdentity?

    val expiresAt: Instant
}

/**
 * Logs the answer to an approval prompt outside release builds.
 *
 * @param completed `false` if the prompt was no longer pending.
 */
internal fun logAgentRequestDecision(
    logType: String,
    id: String,
    value: Boolean,
    reason: String,
    completed: Boolean,
) {
    if (isRelease) return
    val logger = Logger.getLogger(AgentRequest::class.java.name)
    val message =
        "Completing agent request type=$logType result=$value completed=$completed " +
                "reason=$reason id=$id"
    if (completed) {
        logger.info(message)
    } else {
        logger.warning(message)
    }
}
