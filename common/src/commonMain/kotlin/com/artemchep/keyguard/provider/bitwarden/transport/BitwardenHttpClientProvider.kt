package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.common.service.http.KeyguardHttpUserAgent
import com.artemchep.keyguard.common.service.http.configureKeyguardHttpClient
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildApiUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildIdentityUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildIconsUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildNotificationsUrl
import com.artemchep.keyguard.provider.bitwarden.api.builder.buildWebVaultUrl
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.Url
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json

fun interface BitwardenHttpClientProvider : AutoCloseable {
    fun client(env: ServerEnv): HttpClient

    /**
     * Like [client], but also presents the client certificate to the
     * [extraOrigins] (`host:port`) that the account's server named.
     */
    fun client(env: ServerEnv, extraOrigins: Set<String>): HttpClient = client(env)

    /**
     * Closes the clients of the certificates that the accounts stopped
     * using since the last call; their requests in flight still finish.
     * The [envs] are the environments of all accounts.
     */
    fun updateAccounts(envs: Collection<ServerEnv>) = Unit

    override fun close() = Unit
}

fun interface BitwardenHttpClientFactory {
    /** Creates a client that presents the certificate of the [env] to the [origins] only. */
    fun create(env: ServerEnv, origins: Set<String>): HttpClient
}

/** Owned by the unlocked vault; the global HTTP client never holds an identity. */
class BitwardenHttpClientProviderImpl(
    private val httpClient: HttpClient,
    private val factory: BitwardenHttpClientFactory,
) : BitwardenHttpClientProvider {
    private val lock = SynchronizedObject()
    private val clients = mutableMapOf<Pair<String, Set<String>>, HttpClient>()
    private var closed = false

    // A client of a certificate that is not stored yet,
    // such as one of a new account, stays until the lock.
    private var accountFingerprints = emptySet<String>()

    override fun client(env: ServerEnv): HttpClient = client(env, emptySet())

    override fun client(env: ServerEnv, extraOrigins: Set<String>): HttpClient {
        val fingerprint = env.mutualTlsFingerprintOrNull()
            ?: return synchronized(lock) {
                check(!closed) { "The vault session is closed." }
                httpClient
            }
        val key = fingerprint to (env.mutualTlsOrigins() + extraOrigins)
        return synchronized(lock) {
            check(!closed) { "The vault session is closed." }
            clients[key]
        } ?: createClient(env, key)
    }

    // Importing the certificate is slow: create the
    // client outside the lock, so others do not wait.
    private fun createClient(env: ServerEnv, key: Pair<String, Set<String>>): HttpClient {
        val created = factory.create(env, key.second)
        val client = synchronized(lock) {
            if (closed) null else clients.getOrPut(key) { created }
        }
        if (client !== created) created.close()
        return checkNotNull(client) { "The vault session is closed." }
    }

    override fun updateAccounts(envs: Collection<ServerEnv>) {
        val fingerprints = envs.mapNotNullTo(mutableSetOf()) { it.mutualTlsFingerprintOrNull() }
        val evicted = synchronized(lock) {
            val removed = accountFingerprints - fingerprints
            accountFingerprints = fingerprints
            val evicted = clients.filterKeys { (fingerprint, _) -> fingerprint in removed }
            evicted.keys.forEach(clients::remove)
            evicted.values
        }
        evicted.forEach(HttpClient::close)
    }

    override fun close() = synchronized(lock) {
        closed = true
        clients.values.forEach(HttpClient::close)
        clients.clear()
    }
}

private fun ServerEnv.mutualTlsFingerprintOrNull(): String? =
    (mutualTls as? ServerEnv.MutualTls.ImportedPkcs12)?.fingerprint

/** Signed storage URLs can point outside the account's configured servers. */
fun BitwardenHttpClientProvider.clientForUrl(env: ServerEnv, url: String): HttpClient {
    if (env.mutualTls == null) return client(env)
    require(secureOrigin(Url(url)) != null) { "Mutual TLS accounts require secure transfer URLs." }
    return client(if (env.permitsMutualTls(url)) env else env.copy(mutualTls = null))
}

/**
 * Key Connector may run on its own host behind the same proxy. Its URL
 * comes from the account's server, so the certificate may go there too.
 */
fun BitwardenHttpClientProvider.clientForKeyConnector(env: ServerEnv, url: String): HttpClient {
    if (env.mutualTls == null) return client(env)
    val origin = requireNotNull(secureOrigin(Url(url))) { "Mutual TLS accounts require a secure Key Connector URL." }
    return client(env, setOf(origin))
}

internal fun ServerEnv.mutualTlsOrigins(): Set<String> = listOf(
    buildApiUrl(), buildIdentityUrl(), buildNotificationsUrl(), buildIconsUrl(), buildWebVaultUrl(),
).mapNotNull { runCatching { secureOrigin(Url(it)) }.getOrNull() }.toSet()

fun ServerEnv.permitsMutualTls(url: String): Boolean =
    runCatching { secureOrigin(Url(url)) in mutualTlsOrigins() }.getOrDefault(false)

private fun secureOrigin(url: Url): String? = when (url.protocol.name) {
    "https", "wss" -> mutualTlsOrigin(url.host, url.port)
    else -> null
}

/** The `host:port` form of the origins that may get the certificate. */
internal fun mutualTlsOrigin(host: String, port: Int) = "${host.lowercase()}:$port"

/**
 * A request of a client with a certificate to a server that must not
 * get it, such as a redirect of the account's server to another host.
 */
class MutualTlsOriginException(
    val url: Url,
) : IllegalArgumentException("Client certificates may only be used with the configured HTTPS servers.")

internal fun HttpClientConfig<*>.configureBitwardenMutualTlsClient(json: Json, origins: Set<String>) {
    configureKeyguardHttpClient(json, KeyguardHttpUserAgent.Bitwarden)
    require(origins.isNotEmpty()) { "Mutual TLS requires an HTTPS server." }
    install(createClientPlugin("BitwardenMutualTlsOrigin") {
        on(SendingRequest) { request, _ ->
            val url = request.url.build()
            if (secureOrigin(url) !in origins) {
                throw MutualTlsOriginException(url)
            }
        }
    })
}
