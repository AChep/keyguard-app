package com.artemchep.keyguard.desktop.services.keychain

import com.artemchep.autotype.keychainAddPassword
import com.artemchep.autotype.keychainContainsPassword
import com.artemchep.autotype.keychainDeletePassword
import com.artemchep.autotype.keychainGetPassword
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.keychain.KeychainRepository
import org.kodein.di.DirectDI

class KeychainRepositoryNative(
) : KeychainRepository {
    constructor(directDI: DirectDI) : this(
    )

    override fun put(
        id: String,
        password: String,
        requireUserPresence: Boolean,
    ): IO<Unit> = ioEffect {
        keychainAddPassword(
            id = id,
            password = password,
        )
    }

    override fun get(id: String): IO<String> = ioEffect {
        keychainGetPassword(id = id)
    }

    override fun delete(id: String): IO<Boolean> = ioEffect {
        keychainDeletePassword(id = id)
    }

    override fun contains(id: String): IO<Boolean> = ioEffect {
        keychainContainsPassword(id = id)
    }
}
