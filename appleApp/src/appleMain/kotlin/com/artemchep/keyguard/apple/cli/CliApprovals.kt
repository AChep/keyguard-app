package com.artemchep.keyguard.apple.cli

import com.artemchep.keyguard.apple.agent.snapshotCallerName
import com.artemchep.keyguard.apple.agent.snapshotCallerPath
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.environments.resolveApprovalRequest
import com.artemchep.keyguard.common.service.cli.CliCallerIdentity
import com.artemchep.keyguard.common.service.cli.CliOperation
import com.artemchep.keyguard.common.service.cli.CliService
import com.artemchep.keyguard.common.service.cli.displayCommand
import com.artemchep.keyguard.common.service.cli.displayWorkingDirectory
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

enum class CliOperationKind {
    RUN,
}

@Suppress("LongParameterList") // Flat native snapshot keeps Swift bridge values explicit.
class CliApprovalSnapshot(
    val id: String,
    /** The verified name of the caller, empty if unknown. */
    val callerName: String,
    /** The executable of the caller, empty if unknown. */
    val callerPath: String,
    val operation: CliOperationKind,
    val environmentName: String?,
    val accountName: String?,
    /** The command `keyguard run` starts, escaped for display. */
    val command: String?,
    val workingDirectory: String?,
    /** Approving also lets this terminal tab repeat the operation until the approval expires. */
    val terminalSession: Boolean,
    val expiresAtEpochMs: Long,
)

internal fun CoreContext.observeCliApprovals(
    onChange: (List<CliApprovalSnapshot>) -> Unit,
): KeyguardCancellable = launchObserver {
    koin.get<CliService>().approvals.map { requests ->
        requests.map { request ->
            val caller = CliCallerIdentity(request.caller)
            when (val operation = request.operation) {
                is CliOperation.Run -> CliApprovalSnapshot(
                    id = request.id,
                    callerName = caller.snapshotCallerName(),
                    callerPath = caller.snapshotCallerPath(),
                    operation = CliOperationKind.RUN,
                    environmentName = operation.environmentName,
                    accountName = operation.accountName,
                    command = operation.displayCommand(),
                    workingDirectory = operation.displayWorkingDirectory(),
                    terminalSession = request.terminalSession,
                    expiresAtEpochMs = request.expiresAt.toEpochMilliseconds(),
                )
            }
        }
    }.collect { snapshots -> publishOnMain { onChange(snapshots) } }
}

internal fun CoreContext.resolveCliRequest(
    id: String,
    approved: Boolean,
    interceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean),
) {
    val cli = koin.get<CliService>()
    resolveApprovalRequest(
        id = id,
        approved = approved,
        interceptorProvider = interceptorProvider,
        // Confirms the user's identity first, if the operation requires it.
        confirm = { verify -> cli.confirmIdentity(id, verify) },
        respond = cli::respondToApproval,
    )
}
