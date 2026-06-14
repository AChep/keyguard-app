package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.IO

interface SshAgentPublicKeyRepository {
    fun get(): IO<List<SshAgentPublicKeyRow>>

    fun getByPublicKeyBlobSha256(
        publicKeyBlobSha256: String,
    ): IO<SshAgentPublicKeyRow?>

    fun getByPublicKey(
        publicKey: String,
    ): IO<SshAgentPublicKeyRow?>

    fun replaceAll(
        keys: List<SshAgentPublicKeyRow>,
    ): IO<Unit>

    fun clear(): IO<Unit>

    fun clearNames(): IO<Unit>
}

object SshAgentPublicKeyRepositoryEmpty : SshAgentPublicKeyRepository {
    override fun get(): IO<List<SshAgentPublicKeyRow>> = {
        emptyList()
    }

    override fun getByPublicKeyBlobSha256(
        publicKeyBlobSha256: String,
    ): IO<SshAgentPublicKeyRow?> = {
        null
    }

    override fun getByPublicKey(
        publicKey: String,
    ): IO<SshAgentPublicKeyRow?> = {
        null
    }

    override fun replaceAll(
        keys: List<SshAgentPublicKeyRow>,
    ): IO<Unit> = {
        Unit
    }

    override fun clear(): IO<Unit> = {
        Unit
    }

    override fun clearNames(): IO<Unit> = {
        Unit
    }
}
