package com.artemchep.keyguard.common.di

import coil3.ComponentRegistry
import coil3.ImageLoader
import io.ktor.client.HttpClient
import org.kodein.di.DirectDI

internal actual fun ComponentRegistry.Builder.installFaviconUrlFetcherFactory(
    httpClient: () -> HttpClient,
) {
}

internal actual fun ImageLoader.Builder.installPlatformDiskCache(
    directDI: DirectDI,
): ImageLoader.Builder = this
