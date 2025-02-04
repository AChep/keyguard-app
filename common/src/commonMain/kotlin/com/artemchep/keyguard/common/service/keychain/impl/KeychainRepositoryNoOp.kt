package com.artemchep.keyguard.common.service.keychain.impl

import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import org.kodein.di.DirectDI

class KeychainRepositoryNoOp(
) : KeychainRepository {
    constructor(directDI: DirectDI) : this()

    override fun put(
        id: String,
        password: String,
        requireUserPresence: Boolean,
    ) = ioRaiseDefault()

    override fun get(id: String) = ioRaiseDefault()

    override fun delete(id: String) = ioRaiseDefault()

    override fun contains(id: String) = ioRaiseDefault()

    private fun ioRaiseDefault() = kotlin.run {
        val e = IllegalStateException("This platform does not have the keychain support!")
        ioRaise<Nothing>(e)
    }
}
