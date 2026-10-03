package com.artemchep.keyguard.common.service.webdav

import com.artemchep.keyguard.common.usecase.GetWebDavTransactions
import com.artemchep.keyguard.util.webdav.KtorWebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavClientConfig
import com.artemchep.keyguard.util.webdav.WebDavWriteStrategy
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first

fun interface WebDavClientFactory {
    /**
     * [WebDavClientConfig.writeStrategy] is the caller's request; the user's
     * "WebDAV transactions" preference may downgrade it to
     * [WebDavWriteStrategy.DirectPut].
     */
    suspend fun create(
        config: WebDavClientConfig,
    ): WebDavClient
}

class KtorWebDavClientFactory(
    private val httpClient: HttpClient,
    private val getWebDavTransactions: GetWebDavTransactions,
) : WebDavClientFactory {
    override suspend fun create(
        config: WebDavClientConfig,
    ): WebDavClient {
        val writeStrategy = if (getWebDavTransactions().first()) {
            config.writeStrategy
        } else {
            WebDavWriteStrategy.DirectPut
        }
        return KtorWebDavClient(
            httpClient = httpClient,
            config = config.copy(writeStrategy = writeStrategy),
        )
    }
}
