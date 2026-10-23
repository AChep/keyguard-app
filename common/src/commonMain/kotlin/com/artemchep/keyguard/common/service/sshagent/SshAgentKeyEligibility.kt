package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.agent.isEligibleForAgent

fun DSecret.isEligibleForSshAgent(): Boolean =
    type == DSecret.Type.SshKey && isEligibleForAgent() &&
        !sshKey?.privateKey.isNullOrBlank() &&
        isSupportedSshAgentKeyType(sshKey?.publicKey?.let(::extractSshKeyType))

internal fun SshAgentPublicKeyRow.isEligibleForSshAgent(): Boolean =
    canSign && isSupportedSshAgentKeyType(keyType)

private fun isSupportedSshAgentKeyType(keyType: String?): Boolean =
    keyType == "ssh-ed25519" || keyType == "ssh-rsa"
