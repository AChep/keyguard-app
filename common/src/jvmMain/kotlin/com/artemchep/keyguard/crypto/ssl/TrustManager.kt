package com.artemchep.keyguard.crypto.ssl

import com.artemchep.keyguard.platform.CurrentPlatform
import okhttp3.OkHttpClient
import okhttp3.internal.platform.Platform
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun OkHttpClient.Builder.installPlatformTrustManager() =
    when (CurrentPlatform) {
        is com.artemchep.keyguard.platform.Platform.Desktop.MacOS -> installMacOsTrustManager()
        is com.artemchep.keyguard.platform.Platform.Desktop.Windows -> installWindowsTrustManager()
        else -> this
    }

fun OkHttpClient.Builder.installMacOsTrustManager() = installHybridTrustManager(
    ::getMacOsTrustManager,
)

fun OkHttpClient.Builder.installWindowsTrustManager() = installHybridTrustManager(
    ::getWindowsMyTrustManager,
    ::getWindowsRootTrustManager,
)

private inline fun OkHttpClient.Builder.installHybridTrustManager(
    vararg factories: () -> X509TrustManager,
): OkHttpClient.Builder {
    val trustManagers = mutableListOf<X509TrustManager>()
    factories.forEach { factory ->
        val trustManager = runCatching {
            factory()
        }.getOrElse { e ->
            // Could not get the platform specific
            // trust manager.
            e.printStackTrace()
            null
        }
        if (trustManager != null) {
            trustManagers += trustManager
        }
    }
    if (trustManagers.isEmpty()) {
        return this
    }

    // Install the default trust manager as the last one.
    trustManagers += getDefaultTrustManager()

    // Combine with a new trust manager and set it
    // as the OkHTTPs socket factory.
    val hybridTm = HybridTrustManager(trustManagers)
    val hybridSslSocketFactory = Platform.get().newSslSocketFactory(hybridTm)
    return sslSocketFactory(
        sslSocketFactory = hybridSslSocketFactory,
        trustManager = hybridTm,
    )
}

private fun getDefaultTrustManager() = Platform.get().platformTrustManager()

private fun getMacOsTrustManager() =
    getTrustManager("KeychainStore")

private fun getWindowsRootTrustManager() =
    getTrustManager("Windows-ROOT", "SunMSCAPI")

private fun getWindowsMyTrustManager() =
    getTrustManager("Windows-My", "SunMSCAPI")

private fun getTrustManager(
    type: String,
    provider: String? = null,
) = run {
    val keyStore = if (provider != null) {
        KeyStore.getInstance(type, provider)
    } else {
        KeyStore.getInstance(type)
    }
    keyStore.load(null, null)
    // load the trust manager
    getTrustManager(keyStore = keyStore)
}

private fun getTrustManager(
    keyStore: KeyStore,
) = run {
    val trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(keyStore)
    val winTm = trustManagerFactory.trustManagers
        .first { it is X509TrustManager } as X509TrustManager
    winTm
}

private class HybridTrustManager(
    private val trustManagers: List<X509TrustManager>,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        checkForAnyOrThrow {
            this.checkServerTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        checkForAnyOrThrow {
            this.checkClientTrusted(chain, authType)
        }
    }

    private inline fun checkForAnyOrThrow(
        block: X509TrustManager.() -> Unit,
    ) {
        var lastException: CertificateException? = null
        trustManagers.forEach { trustManager ->
            try {
                trustManager.block()
                return // finish on the first trust manager
            } catch (e: CertificateException) {
                lastException = e
            }
        }

        val finalException = lastException
            ?: IllegalStateException()
        throw finalException
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        // Combine the list of trusted CAs from both sources so the server
        // knows we accept certificates from either.
        return trustManagers
            .flatMap {
                it.acceptedIssuers
                    .toList()
            }
            .distinct()
            .toTypedArray()
    }
}
