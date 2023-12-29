package com.artemchep.keyguard.feature.favicon

import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.feature.auth.common.util.verifyIsLocalUrl
import io.ktor.http.Url

interface FaviconServer {
    val id: String

    fun transform(url: String): String?
}

class FaviconAccountServer(
    account: DAccount,
) : FaviconServer {
    override val id: String = account.accountId()

    private val transformer = account.faviconServer

    override fun transform(
        url: String,
    ): String? = kotlin.runCatching {
        val ktorUrl = Url(url)
        when (ktorUrl.specifiedPort) {
            80,
            443,
            0,
            -> {
                // All is fine, continue as if
                // the port was not specified.
            }

            else -> return@runCatching null
        }
        if (verifyIsLocalUrl(ktorUrl)) {
            // Do not download local urls.
            return@runCatching null
        }

        transformer.invoke(ktorUrl.host)
    }.getOrNull()
}
