package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.platform.LocalPath

interface CacheDirProvider {
    /**
     * Returns the cache directory.
     */
    suspend fun get(): LocalPath

    /**
     * Returns the cache directory. This method may perform I/O operations
     * and will block the current thread.
     */
    fun getBlocking(): LocalPath
}
