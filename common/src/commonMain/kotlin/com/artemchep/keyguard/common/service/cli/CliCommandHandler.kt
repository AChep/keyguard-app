package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.service.agent.AgentApprovalCacheIdentity
import kotlinx.serialization.KSerializer

/** The verified context of one request of the CLI. */
class CliCallContext(
    val caller: CliCaller,
    /** The verified terminal tab of the caller, `null` if the agent could not establish one. */
    val terminalSession: AgentApprovalCacheIdentity?,
) {
    /** Whether the user approved this request, or its terminal tab reused an approval. */
    var approved: Boolean = false
        internal set
}

/**
 * One command of the CLI. The [CliCommandDispatcher] checks that the CLI
 * is enabled and that the build and the vault meet the requirements of the
 * [command], and decodes the [Request], before it calls a handler.
 *
 * The [Request] and [Result] mirror the command in `commonCli`; the shared
 * fixtures in `desktopCliAgent/proto/fixtures/` pin both sides.
 */
interface CliCommandHandler<Request : Any, Result : Any> {
    /** The command that this handler answers. */
    val command: CliCommand

    val requestSerializer: KSerializer<Request>

    /** Serializes the result without `ok`, which the dispatcher adds. */
    val resultSerializer: KSerializer<Result>

    /**
     * Any other exception answers `operation_failed`, so a request that
     * decodes but is not valid throws `invalid_request` itself.
     *
     * @throws CliServiceException with a stable, non-sensitive error code.
     */
    suspend fun handle(
        context: CliCallContext,
        request: Request,
    ): Result
}
