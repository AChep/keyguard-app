package com.artemchep.keyguard.common.di

import coil3.ComponentRegistry
import coil3.ImageLoader
import io.ktor.client.HttpClient
import org.koin.core.scope.Scope

internal actual fun ComponentRegistry.Builder.installFaviconUrlFetcherFactory(
    httpClient: () -> HttpClient,
) {
}

internal actual fun ImageLoader.Builder.installPlatformDiskCache(
    scope: Scope,
): ImageLoader.Builder = this
