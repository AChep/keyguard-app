package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorization
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity

/** Presents an environment caller like the callers of the SSH and GPG agents. */
class EnvironmentCallerIdentity(
    caller: EnvironmentCaller,
) : AgentCallerIdentity {
    override val pid: Int = caller.pid
    override val processName: String = caller.processName
    override val executablePath: String = caller.executablePath
    override val appName: String = caller.appName
    override val appBundlePath: String = caller.appBundlePath

    // Only used for presentation. MCP approvals are remembered by the
    // broker's connection.
    override val authorization: AgentCallerAuthorization? = null
}
