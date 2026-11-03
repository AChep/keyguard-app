package com.artemchep.keyguard.common.di

import coil3.ImageLoader
import org.koin.core.scope.Scope

internal actual fun ImageLoader.Builder.installPlatformDiskCache(
    scope: Scope,
): ImageLoader.Builder = this
