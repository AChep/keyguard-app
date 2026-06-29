package com.artemchep.keyguard.common.service.webdav

import com.artemchep.keyguard.util.webdav.KtorWebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import io.ktor.client.HttpClient

fun interface WebDavClientFactory {
    fun create(
        config: WebDavClientConfig,
    ): WebDavClient
}

class KtorWebDavClientFactory(
    private val httpClient: HttpClient,
) : WebDavClientFactory {
    override fun create(
        config: WebDavClientConfig,
    ): WebDavClient = KtorWebDavClient(
        httpClient = httpClient,
        config = config,
    )
}
