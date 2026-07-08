package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.CompletableDeferred
import java.util.logging.Logger
import kotlin.time.Instant

interface AgentRequest {
    val caller: AgentCallerIdentity?

    val notificationTag: String?

    val expiresAt: Instant

    val deferred: CompletableDeferred<Boolean>

    /** Short request-type identifier used in logs, e.g. "approval" / "get_list". */
    val logType: String
}

internal fun AgentRequest.completeWithLog(
    value: Boolean,
    reason: String,
): Boolean {
    val completed = deferred.complete(value)
    if (!isRelease) {
        log(
            value = value,
            reason = reason,
            completed = completed,
        )
    }
    return completed
}

private fun AgentRequest.log(
    value: Boolean,
    reason: String,
    completed: Boolean,
) {
    val logger = Logger.getLogger(this::class.java.name)
    val caller = caller?.let {
        it.appName.takeIf(String::isNotBlank)
            ?: it.processName.takeIf(String::isNotBlank)
            ?: it.executablePath.takeIf(String::isNotBlank)
    } ?: "unknown"
    val message =
        "Completing agent request type=$logType result=$value completed=$completed " +
                "reason=$reason notificationTag=$notificationTag caller=$caller"
    if (completed) {
        logger.info(message)
    } else {
        logger.warning(message)
    }
}
