package com.artemchep.keyguard.common.service.agent

/** Common shape of the caller identity attached to agent (SSH/GPG) requests. */
interface AgentCallerIdentity {
    val pid: Int
    val processName: String
    val executablePath: String
    val appName: String
}
