package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.io.IO

interface SshAgentPublicKeyRepository {
    fun get(): IO<List<SshAgentPublicKeyRow>>

    /**
     * Returns every catalog row holding the given public key: the same
     * key may legitimately live in more than one cipher.
     */
    fun getByPublicKeyBlobSha256(
        publicKeyBlobSha256: String,
    ): IO<List<SshAgentPublicKeyRow>>

    fun getByPublicKey(
        publicKey: String,
    ): IO<List<SshAgentPublicKeyRow>>

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
    ): IO<List<SshAgentPublicKeyRow>> = {
        emptyList()
    }

    override fun getByPublicKey(
        publicKey: String,
    ): IO<List<SshAgentPublicKeyRow>> = {
        emptyList()
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
