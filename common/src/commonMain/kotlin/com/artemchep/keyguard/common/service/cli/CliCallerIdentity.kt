package com.artemchep.keyguard.common.service.cli

import com.artemchep.keyguard.common.service.agent.AgentCallerAuthorization
import com.artemchep.keyguard.common.service.agent.AgentCallerIdentity

/** Presents a CLI caller like the callers of the SSH and GPG agents. */
class CliCallerIdentity(
    caller: CliCaller,
) : AgentCallerIdentity {
    override val pid: Int = caller.pid
    override val processName: String = caller.processName
    override val executablePath: String = caller.executablePath
    override val appName: String = caller.appName
    override val appBundlePath: String = caller.appBundlePath

    // Only used for presentation. Approvals are remembered by the verified
    // terminal session, which the dispatcher validates separately.
    override val authorization: AgentCallerAuthorization? = null
}
