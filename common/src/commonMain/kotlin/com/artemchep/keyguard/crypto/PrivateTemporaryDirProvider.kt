package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath

/**
 * Supplies the directory that hosts private scratch storage, such as staging
 * spills and native OpenPGP staging. Application graphs bind it per platform,
 * so tests can relocate that storage. Exports, imports and agent sockets use
 * their own locations.
 */
fun interface PrivateTemporaryDirProvider {
    /**
     * Returns the directory. This method may perform I/O operations
     * and will block the current thread.
     */
    fun get(): LocalPath

    companion object {
        /** The platform location, for callers created outside a graph. */
        internal val Default = PrivateTemporaryDirProvider {
            privateTemporaryStorageDirectory()
        }
    }
}
