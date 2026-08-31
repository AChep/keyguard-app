package com.artemchep.keyguard.crypto.ssl

import okhttp3.OkHttpClient
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

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
            factory().takeIfHasAcceptedIssuers()
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
    val hybridTm = createHybridTrustManager(trustManagers)
    val hybridSslSocketFactory = createSslSocketFactory(hybridTm)
    return sslSocketFactory(
        sslSocketFactory = hybridSslSocketFactory,
        trustManager = hybridTm,
    )
}

private fun getMacOsTrustManager(): X509TrustManager =
    getTrustManager("KeychainStore")

private fun getWindowsRootTrustManager(): X509TrustManager =
    getTrustManager("Windows-ROOT", "SunMSCAPI")

private fun getWindowsMyTrustManager(): X509TrustManager =
    getTrustManager("Windows-My", "SunMSCAPI")

private fun getTrustManager(
    type: String,
    provider: String? = null,
): X509TrustManager {
    val keyStore = if (provider != null) {
        KeyStore.getInstance(type, provider)
    } else {
        KeyStore.getInstance(type)
    }
    keyStore.load(null, null)
    return getTrustManager(keyStore = keyStore)
}

private fun getTrustManager(
    keyStore: KeyStore,
): X509TrustManager {
    val trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(keyStore)
    return trustManagerFactory.trustManagers
        .first { it is X509TrustManager } as X509TrustManager
}
