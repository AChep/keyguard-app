package com.artemchep.keyguard.common.service.keychain

import com.artemchep.keyguard.common.io.IO

/**
 * Provides a generic interface for keychain
 * implementation.
 */
interface KeychainRepository {
    fun put(
        id: String,
        password: String,
        requireUserPresence: Boolean = false,
    ): IO<Unit>

    fun get(id: String): IO<String>

    fun delete(id: String): IO<Boolean>

    fun contains(id: String): IO<Boolean>
}
