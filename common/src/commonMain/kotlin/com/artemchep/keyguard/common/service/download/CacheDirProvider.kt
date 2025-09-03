package com.artemchep.keyguard.common.service.download

import java.io.File

interface CacheDirProvider {
    /**
     * Returns the cache directory.
     */
    suspend fun get(): File

    /**
     * Returns the cache directory. This method may perform I/O operations
     * and will block the current thread.
     */
    fun getBlocking(): File
}
