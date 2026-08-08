package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.IO

interface GpgPublicKeyRepository {
    /**
     * Returns the complete catalog rows, skipping the ciphers that
     * miss the armored public key or the primary fingerprint.
     */
    fun getPublicKeys(): IO<List<GpgPublicKeyRow>>

    fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>>

    /**
     * Returns every catalog row holding the given keygrip: the same
     * component key may legitimately live in more than one cipher.
     */
    fun getKeyInfoByKeygrip(
        keygrip: String,
    ): IO<List<GpgAgentKeyInfoRow>>

    fun replaceAll(
        entries: List<GpgPublicKeyEntry>,
    ): IO<Unit>

    fun clear(): IO<Unit>

    fun clearNames(): IO<Unit>
}

object GpgPublicKeyRepositoryEmpty : GpgPublicKeyRepository {
    override fun getPublicKeys(): IO<List<GpgPublicKeyRow>> = {
        emptyList()
    }

    override fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>> = {
        emptyList()
    }

    override fun getKeyInfoByKeygrip(
        keygrip: String,
    ): IO<List<GpgAgentKeyInfoRow>> = {
        emptyList()
    }

    override fun replaceAll(
        entries: List<GpgPublicKeyEntry>,
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
