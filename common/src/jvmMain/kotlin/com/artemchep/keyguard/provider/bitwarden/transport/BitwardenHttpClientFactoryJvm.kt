package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
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
    /** `null` on platforms without a certificate store that apps can use. */
    private val systemIdentities: MutualTlsSystemIdentities?,
) : BitwardenHttpClientFactory {
    override fun create(env: ServerEnv, origins: Set<String>): HttpClient {
        val trustManager = requireNotNull(baseOkHttpClient.x509TrustManager)
        val builder = baseOkHttpClient.newBuilder()
            .connectionPool(ConnectionPool())
            .dispatcher(Dispatcher())
            // Ktor follows redirects after checking every destination with the shared guard.
            .followRedirects(false)
            .followSslRedirects(false)
        val keys: Array<KeyManager> = when (val identity = requireNotNull(env.mutualTls)) {
            is ServerEnv.MutualTls.ImportedPkcs12 -> {
                val store = loadMutualTlsKeyStore(identity.pkcs12Base64)
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(store, charArrayOf())
                }.keyManagers
            }

            is ServerEnv.MutualTls.SystemStore -> {
                val source = SystemStoreIdentitySource(identity, systemIdentities)
                builder.addInterceptor(SystemStoreIdentityInterceptor(source))
                arrayOf(SystemStoreKeyManager(source))
            }
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keys, arrayOf(trustManager), null)
        }
        val client = builder
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

/**
 * The key and the certificate chain of the certificate, for platform web
 * views. Blocks. Returns `null` if a certificate of the system's store is
 * gone, or the platform has no such store.
 */
@Throws(IOException::class)
internal fun ServerEnv.MutualTls.loadIdentity(
    systemIdentities: MutualTlsSystemIdentities?,
): MutualTlsIdentity? = when (this) {
    is ServerEnv.MutualTls.ImportedPkcs12 -> loadIdentity()
    is ServerEnv.MutualTls.SystemStore -> systemIdentities?.load(this)
}

/** The single identity of the imported certificate. */
private fun ServerEnv.MutualTls.ImportedPkcs12.loadIdentity(): MutualTlsIdentity {
    val store = loadMutualTlsKeyStore(pkcs12Base64)
    val alias = store.aliases().toList().single(store::isKeyEntry)
    val entry = store.getEntry(alias, KeyStore.PasswordProtection(charArrayOf())) as KeyStore.PrivateKeyEntry
    return MutualTlsIdentity(
        privateKey = entry.privateKey,
        chain = entry.certificateChain
            .filterIsInstance<X509Certificate>()
            .toTypedArray(),
    )
}
