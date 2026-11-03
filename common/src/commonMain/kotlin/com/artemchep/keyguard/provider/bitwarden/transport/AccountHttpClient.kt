package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.http.URLProtocol

/**
 * The client of an account for a URL. The account's client certificate
 * goes to the account's servers only: if one of them redirects to another
 * host, the target gets a new request from the [redirectHttpClient], without
 * the certificate and without the headers of the first request.
 */
class AccountHttpClient(
    val httpClient: HttpClient,
    /** `null` if the [httpClient] has no certificate, so it follows the redirects itself. */
    val redirectHttpClient: HttpClient? = null,
) {
    /** Runs the [block] with the client and the URL to request. */
    suspend fun <T> request(
        url: String,
        block: suspend (httpClient: HttpClient, url: String) -> T,
    ): T = try {
        block(httpClient, url)
    } catch (e: MutualTlsOriginException) {
        val redirectHttpClient = redirectHttpClient
            ?: throw e
        // Same as the first request: an account with a
        // certificate talks to secure servers only.
        if (e.url.protocol != URLProtocol.HTTPS) {
            throw e
        }
        block(redirectHttpClient, e.url.toString())
    }
}

/**
 * Like [clientForUrl], and follows a redirect off the
 * account's servers with a client without the certificate.
 */
fun BitwardenHttpClientProvider.accountClientForUrl(env: ServerEnv, url: String): AccountHttpClient =
    AccountHttpClient(
        httpClient = clientForUrl(env, url),
        redirectHttpClient = env.mutualTls?.let { client(env.copy(mutualTls = null)) },
    )
