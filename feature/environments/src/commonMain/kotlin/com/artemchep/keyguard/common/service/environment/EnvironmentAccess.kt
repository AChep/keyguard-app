package com.artemchep.keyguard.common.service.environment

import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * Who may access the environments right now: the approval prompts of
 * the callers, and the access the user confirmed.
 */
interface EnvironmentAccess {
    val approvals: StateFlow<List<EnvironmentApprovalRequest>>

    /** @return `true` if the prompt was still pending. */
    fun respondToApproval(id: String, approved: Boolean): Boolean

    /** The challenge that confirms the access to the environment, `null` if none is required. */
    suspend fun elevationChallenge(id: String): String?

    fun grantElevatedAccess(id: String, challenge: String)

    /** Grants the access to the directory of a local `.env` file, until the grants are revoked. */
    fun provideDirectoryGrant(path: String, bookmark: String)
}

/**
 * Asks the user to confirm the access to the environment, if it is
 * required. Returns `false` if the user did not confirm it.
 *
 * @param challenge The challenge to answer, `null` to request one.
 */
suspend fun EnvironmentAccess.ensureElevatedAccess(
    id: String,
    challenge: String? = null,
    verify: suspend () -> Boolean,
): Boolean {
    val resolvedChallenge = challenge ?: elevationChallenge(id) ?: return true
    val verified = verify()
    if (verified) grantElevatedAccess(id, resolvedChallenge)
    return verified
}

/**
 * Confirms the access to the environment of an approval [request], if it
 * is required. Returns `false` if the user did not confirm it, or if the
 * request is no longer pending.
 */
suspend fun EnvironmentAccess.ensureElevatedAccess(
    request: EnvironmentApprovalRequest,
    verify: suspend () -> Boolean,
): Boolean {
    val id = request.environmentId ?: return true
    return ensureElevatedAccess(id, request.elevationChallenge) {
        verify() && approvals.value.any { it.id == request.id }
    }
}

/**
 * The process that asked for access. The process and its application are
 * verified by the operating system. The [clientName] is reported by the MCP
 * client itself, so it is only a hint and never identifies the caller.
 */
data class EnvironmentCaller(
    val pid: Int = 0,
    val processName: String = "",
    val executablePath: String = "",
    val appName: String = "",
    val appBundlePath: String = "",
    val clientName: String? = null,
)

data class EnvironmentApprovalRequest(
    val id: String,
    /**
     * The caller, `null` if it can not be identified: reading a
     * local `.env` file does not reveal the reader.
     */
    val caller: EnvironmentCaller?,
    val environmentName: String?,
    val capability: EnvironmentCapability,
    val path: String?,
    val expiresAt: Instant,
    val environmentId: String? = null,
    val elevationChallenge: String? = null,
    /** The account of a new environment. */
    val accountId: String? = null,
)

/** What an approval allows the caller to do. */
enum class EnvironmentCapability {
    Authenticate,
    ListEnvironments,
    CreateEnvironment,
    RenameEnvironment,
    ListVariables,
    AppendVariables,
    CreateLocalEnvFile,
    ListLocalEnvFiles,
    ReadLocalEnvFile,
}
