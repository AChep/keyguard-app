package com.artemchep.keyguard.sshe2e

import com.artemchep.keyguard.integration.process.ProcessResult
import com.artemchep.keyguard.integration.process.runProcess

class SshCli(
    private val socket: String,
) {
    fun run(
        vararg args: String,
        stdin: ByteArray? = null,
        timeoutSeconds: Long = 30,
    ): ProcessResult = runProcess(args.toList(), stdin, timeoutSeconds) {
        environment()["SSH_AUTH_SOCK"] = socket
    }
}
