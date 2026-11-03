package com.artemchep.keyguard.common.service.icon.coil

import coil3.ComponentRegistry
import coil3.annotation.ExperimentalCoilApi
import coil3.network.DeDupeConcurrentRequestStrategy
import com.artemchep.keyguard.common.service.icon.IconResolver
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.favicon.IconRequest
import com.artemchep.keyguard.feature.favicon.IosAppIconUrl
import com.artemchep.keyguard.feature.favicon.RemoteIconUrl

@OptIn(ExperimentalCoilApi::class)
fun ComponentRegistry.Builder.installIconRequestComponents(
    resolver: IconResolver,
) {
    // Rows of the same website load the same icon at the same
    // time; only one of them should read the website.
    val concurrentRequestStrategy = DeDupeConcurrentRequestStrategy()
    add(IconRequestFetcher.Factory<FaviconUrl>(resolver, concurrentRequestStrategy))
    add(IconRequestFetcher.Factory<RemoteIconUrl>(resolver, concurrentRequestStrategy))
    add(IconRequestFetcher.Factory<IosAppIconUrl>(resolver, concurrentRequestStrategy))
    add(
        AppIconFetcher.Factory(
            storeFactory = IconRequestFetcher.Factory(resolver, concurrentRequestStrategy),
        ),
    )
    add(IconRequestKeyer(), IconRequest::class)
}
