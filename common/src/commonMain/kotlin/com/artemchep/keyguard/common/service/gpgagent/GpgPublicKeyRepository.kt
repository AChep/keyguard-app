package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.IO

interface GpgPublicKeyRepository {
    /**
     * Returns the complete public key catalog and its certification
     * authorities from the same repository snapshot. Catalog rows that
     * miss the armored public key or primary fingerprint are skipped.
     */
    fun getSnapshot(): IO<GpgPublicKeySnapshot>

    fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>>

    /**
     * Returns every catalog row holding the given keygrip: the same
     * component key may legitimately live in more than one cipher.
     */
    fun getKeyInfoByKeygrip(
        keygrip: String,
    ): IO<List<GpgAgentKeyInfoRow>>

    /** Replaces the complete public key and authority snapshot as one operation. */
    fun replaceSnapshot(
        publicKeys: List<GpgPublicKeyEntry>,
        certificationAuthorities: List<GpgCertificationAuthorityEntry>,
    ): IO<Unit>

    fun clear(): IO<Unit>

    fun clearNames(): IO<Unit>
}

object GpgPublicKeyRepositoryEmpty : GpgPublicKeyRepository {
    override fun getSnapshot(): IO<GpgPublicKeySnapshot> = {
        GpgPublicKeySnapshot(
            publicKeys = emptyList(),
            certificationAuthorities = emptyList(),
        )
    }

    override fun getKeyInfo(): IO<List<GpgAgentKeyInfoRow>> = {
        emptyList()
    }

    override fun getKeyInfoByKeygrip(
        keygrip: String,
    ): IO<List<GpgAgentKeyInfoRow>> = {
        emptyList()
    }

    override fun replaceSnapshot(
        publicKeys: List<GpgPublicKeyEntry>,
        certificationAuthorities: List<GpgCertificationAuthorityEntry>,
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
