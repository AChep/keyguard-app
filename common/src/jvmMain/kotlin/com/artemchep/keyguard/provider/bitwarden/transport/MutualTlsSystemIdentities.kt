package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import java.io.IOException
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import okhttp3.Interceptor
import okhttp3.Response

/** The [privateKey] may be a handle to a key that never leaves the store of the system. */
class MutualTlsIdentity(
    val privateKey: PrivateKey,
    val chain: Array<X509Certificate>,
)

/**
 * Loads the certificates of [ServerEnv.MutualTls.SystemStore].
 * Only bound on platforms that have such a store.
 */
fun interface MutualTlsSystemIdentities {
    /**
     * Blocks, so it must not run on the main thread. Returns `null` if the
     * certificate is gone or the app may no longer use it; any other
     * failure is an [IOException].
     */
    @Throws(IOException::class)
    fun load(certificate: ServerEnv.MutualTls.SystemStore): MutualTlsIdentity?
}

/** Loads the identity on demand and keeps it; a failure is never kept. */
internal class SystemStoreIdentitySource(
    val certificate: ServerEnv.MutualTls.SystemStore,
    private val identities: MutualTlsSystemIdentities?,
) {
    private val lock = Any()
    private var identity: MutualTlsIdentity? = null

    /** Returns `null` if the certificate is gone. */
    @Throws(IOException::class)
    fun getOrLoad(): MutualTlsIdentity? = synchronized(lock) {
        identity ?: load()?.also { identity = it }
    }

    /** Forgets the [stale] identity, unless another request has loaded a new one since. */
    fun invalidate(stale: MutualTlsIdentity) = synchronized(lock) {
        if (identity === stale) identity = null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun load(): MutualTlsIdentity? = try {
        identities?.load(certificate)
    } catch (e: IOException) {
        throw e
    } catch (e: RuntimeException) {
        // OkHttp rethrows anything but an I/O failure on
        // its own thread, which would end the process.
        throw IOException(e)
    }
}

/**
 * Loads the identity before a call connects. The handshake can not report
 * a missing key: the server would only answer that it got no certificate.
 *
 * It runs on a thread of the HTTP client, so the store may block.
 */
internal class SystemStoreIdentityInterceptor(
    private val source: SystemStoreIdentitySource,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val identity = source.getOrLoad()
            ?: throw MutualTlsCertificateMissingException()
        return try {
            chain.proceed(chain.request())
        } catch (e: SSLException) {
            // The key might have left the store since
            // it was loaded: load it again next time.
            source.invalidate(identity)
            throw e
        }
    }
}

/** Presents its only identity; the server decides whether to accept it. */
internal class SystemStoreKeyManager(
    private val source: SystemStoreIdentitySource,
) : X509ExtendedKeyManager() {
    private val alias get() = source.certificate.alias

    // A key manager must not throw: without
    // an identity it offers no certificate.
    private fun identityOrNull(): MutualTlsIdentity? = try {
        source.getOrLoad()
    } catch (_: IOException) {
        null
    }

    private fun aliasOrNull(): String? = alias.takeIf { identityOrNull() != null }

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = aliasOrNull()

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = aliasOrNull()

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = aliasOrNull()?.let { arrayOf(it) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        identityOrNull()?.chain?.takeIf { alias == this.alias }

    override fun getPrivateKey(alias: String?): PrivateKey? =
        identityOrNull()?.privateKey?.takeIf { alias == this.alias }

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null
}
