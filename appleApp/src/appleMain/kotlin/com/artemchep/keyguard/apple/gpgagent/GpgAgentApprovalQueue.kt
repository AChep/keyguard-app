package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.apple.agent.AgentApprovalQueue
import com.artemchep.keyguard.apple.agent.snapshotCallerName
import com.artemchep.keyguard.apple.agent.snapshotCallerPath
import com.artemchep.keyguard.common.service.agent.AGENT_APPROVAL_PROMPT_TIMEOUT
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentApprovalPrompt
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import kotlin.time.Clock
import kotlin.time.Duration

internal typealias GpgAgentApprovalQueue = AgentApprovalQueue<GpgAgentApprovalPrompt, GpgAgentRequestSnapshot>

/** Approval state shared by the process-wide native panel. */
internal fun gpgAgentApprovalQueue(
    timeout: Duration = AGENT_APPROVAL_PROMPT_TIMEOUT,
    nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): GpgAgentApprovalQueue = AgentApprovalQueue(
    idPrefix = "gpg",
    idOf = { it.id },
    toSnapshot = GpgAgentApprovalPrompt::toSnapshot,
    timeout = timeout,
    nowEpochMs = nowEpochMs,
)

internal fun GpgAgentApprovalPrompt.toSnapshot(
    id: String,
    timeoutMs: Long,
    expiresAtEpochMs: Long,
) = GpgAgentRequestSnapshot(
    id = id,
    keyName = keyName,
    keyFingerprint = keyFingerprint,
    callerName = caller.snapshotCallerName(),
    callerPath = caller.snapshotCallerPath(),
    operation = when (operation) {
        GpgAgentOperation.SIGN -> GpgAgentOperationSnapshot.SIGN
        GpgAgentOperation.DECRYPT -> GpgAgentOperationSnapshot.DECRYPT
    },
    timeoutMs = timeoutMs,
    expiresAtEpochMs = expiresAtEpochMs,
)
