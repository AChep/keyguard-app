package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.platform.AppleStorageDirectories
import com.artemchep.keyguard.platform.LocalPath

class CacheDirProviderApple(
    private val directories: AppleStorageDirectories,
) : CacheDirProvider {
    override suspend fun get(): LocalPath = directories.cache

    override fun getBlocking(): LocalPath = directories.cache
}
