package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.iosKeyguardCacheDirectory

object CacheDirProviderIos : CacheDirProvider {
    override suspend fun get(): LocalPath = cacheDir()

    override fun getBlocking(): LocalPath = cacheDir()

    private fun cacheDir(): LocalPath = iosKeyguardCacheDirectory()
}
