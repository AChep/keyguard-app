package com.artemchep.keyguard.provider.bitwarden.usecase

import arrow.core.Either
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildHost
import com.artemchep.keyguard.provider.bitwarden.model.ServerDiscoveryCandidate
import com.artemchep.keyguard.util.dns.DnsTxtResolver
import com.artemchep.keyguard.util.dns.UnsupportedDnsLookupException
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val TAG = "DiscoverBitwardenServer"
private const val DNS_LOOKUP_TIMEOUT_MS = 4_000L

/**
 * Discovers Bitwarden-compatible servers for the domain of a sign-in email.
 *
 * The domain publishes a server by adding a TXT record that holds the
 * `https://` URL of the server, at `_bitwarden.<domain>` or
 * `_vaultwarden.<domain>`. Returns an empty list when the domain publishes
 * nothing, and fails when a lookup fails and the other finds nothing.
 */
interface DiscoverBitwardenServer : (String) -> IO<List<ServerDiscoveryCandidate>>

class DiscoverBitwardenServerImpl(
    private val dnsTxtResolver: DnsTxtResolver,
    private val tldService: TldService,
    private val logRepository: LogRepository,
) : DiscoverBitwardenServer {
    private val prefixes = listOf(
        "_bitwarden",
        "_vaultwarden",
    )

    override fun invoke(domain: String): IO<List<ServerDiscoveryCandidate>> = ioEffect {
        val results = coroutineScope {
            prefixes
                .map { prefix ->
                    async {
                        lookupTxt("$prefix.$domain")
                    }
                }
                .awaitAll()
        }
        val urls = results
            .flatMap { it.getOrNull().orEmpty() }
            .mapNotNull(::parseServerUrlOrNull)
            .distinctBy { it.dedupeKey() }
        if (urls.isEmpty()) {
            // A failed lookup might hide a server, so report the
            // failure instead of saying that there is none.
            results.firstNotNullOfOrNull { it.leftOrNull() }
                ?.let { throw it }
            // Most domains publish nothing, so do not load the
            // public suffix list unless there is a server to check.
            return@ioEffect emptyList()
        }

        val emailRegistrableDomain = tldService.getDomainName(domain).bind()
        urls.map { url ->
            val serverRegistrableDomain = tldService.getDomainName(url.host).bind()
            ServerDiscoveryCandidate(
                env = url.toServerEnv(),
                sameDomain = serverRegistrableDomain == emailRegistrableDomain,
            )
        }
    }

    private suspend fun lookupTxt(name: String): Either<Throwable, List<String>> =
        ioEffect { dnsTxtResolver.lookupTxt(name) }
            .timeout(DNS_LOOKUP_TIMEOUT_MS)
            .handleErrorTap { e ->
                // Platforms without a TXT API are expected to fail.
                if (e !is UnsupportedDnsLookupException) {
                    logRepository.postDebug(TAG) {
                        "Failed to look up the TXT record of '$name': $e"
                    }
                }
            }
            .attempt()
            .bind()
}

/**
 * Parses a TXT record value as a server URL. Only absolute `https://` URLs
 * without credentials, query or fragment are accepted; anything else returns
 * `null`.
 */
internal fun parseServerUrlOrNull(value: String): Url? {
    val url = runCatching { Url(value.trim()) }.getOrNull()
        ?: return null
    val isHttpsHost = url.protocolOrNull == URLProtocol.HTTPS && url.host.isNotBlank()
    val hasNoCredentials = url.user == null && url.password == null
    val hasNoQueryOrFragment = url.encodedQuery.isEmpty() && url.fragment.isEmpty()
    return url.takeIf { isHttpsHost && hasNoCredentials && hasNoQueryOrFragment }
}

private fun Url.dedupeKey(): String =
    host.lowercase() + ":" + port + encodedPath.trimEnd('/')

/**
 * Maps the official vault hosts to their predefined region so the login form
 * can select the region instead of a custom environment.
 */
private fun Url.toServerEnv(): ServerEnv {
    val isPlainHost = specifiedPort == 0 && encodedPath.trimEnd('/').isEmpty()
    val region = ServerEnv.Region.entries
        .takeIf { isPlainHost }
        ?.firstOrNull { region ->
            val host = host.lowercase()
            host == region.text || host == ServerEnv(region = region).buildHost()
        }
    return if (region != null) {
        ServerEnv(region = region)
    } else {
        ServerEnv(baseUrl = toString())
    }
}
