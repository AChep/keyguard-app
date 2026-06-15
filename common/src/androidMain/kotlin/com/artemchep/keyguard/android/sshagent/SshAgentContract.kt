package com.artemchep.keyguard.android.sshagent

object SshAgentContract {
    const val ACTION_RUN_ANDROID_SSH_AGENT = "com.artemchep.keyguard.action.RUN_ANDROID_SSH_AGENT"

    //
    // extra
    //

    const val EXTRA_PROTOCOL_VERSION = "com.artemchep.keyguard.extra.SSH_AGENT_PROTOCOL_VERSION"
    const val EXTRA_PROXY_PORT = "com.artemchep.keyguard.extra.SSH_AGENT_PROXY_PORT"
    const val EXTRA_SESSION_ID = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_ID"
    const val EXTRA_SESSION_SECRET = "com.artemchep.keyguard.extra.SSH_AGENT_SESSION_SECRET"
}
