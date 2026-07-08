package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.io.IO

interface GpgAgentPublicKeyRepository {
    fun get(): IO<List<GpgAgentPublicKeyRow>>

    fun getByKeygrip(
        keygrip: String,
    ): IO<GpgAgentPublicKeyRow?>

    fun replaceAll(
        keys: List<GpgAgentPublicKeyRow>,
    ): IO<Unit>

    fun clear(): IO<Unit>

    fun clearNames(): IO<Unit>
}

object GpgAgentPublicKeyRepositoryEmpty : GpgAgentPublicKeyRepository {
    override fun get(): IO<List<GpgAgentPublicKeyRow>> = {
        emptyList()
    }

    override fun getByKeygrip(
        keygrip: String,
    ): IO<GpgAgentPublicKeyRow?> = {
        null
    }

    override fun replaceAll(
        keys: List<GpgAgentPublicKeyRow>,
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
