package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

class BitwardenHttpClientFactoryJvm(
    private val baseOkHttpClient: OkHttpClient,
    private val json: Json,
) : BitwardenHttpClientFactory {
    override fun create(env: ServerEnv, origins: Set<String>): HttpClient {
        val identity = requireNotNull(env.mutualTls as? ServerEnv.MutualTls.ImportedPkcs12)
        val store = loadMutualTlsKeyStore(identity.pkcs12Base64)
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, charArrayOf())
        }.keyManagers
        val trustManager = requireNotNull(baseOkHttpClient.x509TrustManager)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keys, arrayOf(trustManager), null)
        }
        val client = baseOkHttpClient.newBuilder()
            .connectionPool(ConnectionPool())
            .dispatcher(Dispatcher())
            // Ktor follows redirects after checking every destination with the shared guard.
            .followRedirects(false)
            .followSslRedirects(false)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
        return HttpClient(OkHttp) {
            engine { preconfigured = client }
            configureBitwardenMutualTlsClient(json, origins)
        }
    }
}

/** The stored PKCS#12 is normalized to an empty password on import. */
internal fun loadMutualTlsKeyStore(pkcs12Base64: String): KeyStore {
    val bytes = Base64.Default.decode(pkcs12Base64)
    return try {
        KeyStore.getInstance("PKCS12").apply {
            bytes.inputStream().use { load(it, charArrayOf()) }
        }
    } finally {
        bytes.fill(0)
    }
}

/** The single identity of the imported certificate, for platform web views. */
internal fun WebClientCertificate.loadPrivateKeyEntry(): KeyStore.PrivateKeyEntry {
    val store = loadMutualTlsKeyStore(pkcs12Base64)
    val alias = store.aliases().toList().single(store::isKeyEntry)
    return store.getEntry(alias, KeyStore.PasswordProtection(charArrayOf())) as KeyStore.PrivateKeyEntry
}
