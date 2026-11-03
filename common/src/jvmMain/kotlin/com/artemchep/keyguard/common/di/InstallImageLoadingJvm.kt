package com.artemchep.keyguard.common.di

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import java.io.File
import org.koin.core.scope.Scope

internal actual fun ImageLoader.Builder.installPlatformDiskCache(
    scope: Scope,
): ImageLoader.Builder =
    diskCache {
        // This is getting called from a default dispatcher, according
        // to the coil documentation.
        val cacheDirProvider = scope.get<CacheDirProvider>()
        val cacheDir = cacheDirProvider.getBlocking()
        DiskCache.Builder()
            .directory(File(cacheDir.value).resolve("coil3_disk_cache"))
            .build()
    }
