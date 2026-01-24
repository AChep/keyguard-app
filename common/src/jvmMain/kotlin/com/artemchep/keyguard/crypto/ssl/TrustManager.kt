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
        else -> this
    }

fun OkHttpClient.Builder.installMacOsTrustManager() = installHybridTrustManager {
    getMacOsTrustManager()
}

fun OkHttpClient.Builder.installWindowsTrustManager() = installHybridTrustManager {
    getWindowsTrustManager()
}

private inline fun OkHttpClient.Builder.installHybridTrustManager(
    fallback: () -> X509TrustManager = { getDefaultTrustManager() },
    primary: () -> X509TrustManager,
): OkHttpClient.Builder {
    val primaryTm = runCatching {
        primary()
    }.getOrElse { e ->
        // Could not get the platform specific
        // trust manager.
        e.printStackTrace()
        return this
    }
    val fallbackTm = fallback()

    // Combine with a new trust manager and set it
    // as the OkHTTPs socket factory.
    val hybridTm = HybridTrustManager(primaryTm, fallbackTm)
    val hybridSslSocketFactory = Platform.get().newSslSocketFactory(hybridTm)
    return sslSocketFactory(
        sslSocketFactory = hybridSslSocketFactory,
        trustManager = hybridTm,
    )
}

private fun getDefaultTrustManager() = Platform.get().platformTrustManager()

private fun getMacOsTrustManager() = run {
    val appleFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    val appleKeyStore = KeyStore.getInstance("KeychainStore")
    appleKeyStore.load(null, null)
    appleFactory.init(appleKeyStore)
    val appleTm = appleFactory.trustManagers
        .first { it is X509TrustManager } as X509TrustManager
    appleTm
}

private fun getWindowsTrustManager() = run {
    val winFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    val winKeyStore = KeyStore.getInstance("Windows-MY")
    winKeyStore.load(null, null)
    winFactory.init(winKeyStore)
    val winTm = winFactory.trustManagers
        .first { it is X509TrustManager } as X509TrustManager
    winTm
}

/**
 * A TrustManager that delegates to a primary manager, and falls back
 * to a secondary manager if the primary fails validation.
 */
private class HybridTrustManager(
    private val primary: X509TrustManager,
    private val secondary: X509TrustManager,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            println("??? BEFORE")
            primary.checkServerTrusted(chain, authType)
        } catch (_: CertificateException) {
            println("??? CHECK FAILED")
            secondary.checkServerTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            primary.checkClientTrusted(chain, authType)
        } catch (_: CertificateException) {
            secondary.checkClientTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        // Combine the list of trusted CAs from both sources so the server
        // knows we accept certificates from either.
        return primary.acceptedIssuers + secondary.acceptedIssuers
    }
}
