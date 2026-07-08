package com.artemchep.keyguard.common.service.gpgagent

/**
 * The private-key operation a GPG client is asking the agent to perform. Used to
 * tailor the approval prompt wording (signing vs decryption).
 */
enum class GpgAgentOperation {
    SIGN,
    DECRYPT,
}
