package com.artemchep.keyguard.crypto.ssl

import com.artemchep.keyguard.platform.CurrentPlatform
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

fun OkHttpClient.Builder.installPlatformTrustManager() =
    when (CurrentPlatform) {
        is com.artemchep.keyguard.platform.Platform.Desktop.MacOS -> installMacOsTrustManager()
        is com.artemchep.keyguard.platform.Platform.Desktop.Windows -> installWindowsTrustManager()
        else -> this
    }

internal fun X509TrustManager.takeIfHasAcceptedIssuers(): X509TrustManager? =
    takeIf { acceptedIssuers.isNotEmpty() }

internal fun getDefaultTrustManager(): X509TrustManager {
    val trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    trustManagerFactory.init(null as KeyStore?)
    val trustManagers = trustManagerFactory.trustManagers
    check(trustManagers.size == 1 && trustManagers[0] is X509TrustManager) {
        "Unexpected default trust managers: ${trustManagers.contentToString()}"
    }
    return trustManagers[0] as X509TrustManager
}

internal fun createSslSocketFactory(
    trustManager: X509TrustManager,
) = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf<TrustManager>(trustManager), null)
}.socketFactory

internal fun createHybridTrustManager(
    trustManagers: List<X509TrustManager>,
): X509TrustManager {
    require(trustManagers.isNotEmpty()) {
        "At least one trust manager is required."
    }
    return if (trustManagers.all { it is X509ExtendedTrustManager }) {
        ExtendedHybridTrustManager(
            trustManagers.map { it as X509ExtendedTrustManager },
        )
    } else {
        HybridTrustManager(trustManagers)
    }
}

private class HybridTrustManager(
    private val trustManagers: List<X509TrustManager>,
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        trustManagers.checkForAnyOrThrow {
            this.checkServerTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        trustManagers.checkForAnyOrThrow {
            this.checkClientTrusted(chain, authType)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        trustManagers.combinedAcceptedIssuers()
}

private class ExtendedHybridTrustManager(
    private val trustManagers: List<X509ExtendedTrustManager>,
) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        trustManagers.checkForAnyOrThrow {
            checkServerTrusted(chain, authType)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        trustManagers.checkForAnyOrThrow {
            checkClientTrusted(chain, authType)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        trustManagers.checkForAnyOrThrow {
            checkServerTrusted(chain, authType, socket)
        }
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        trustManagers.checkForAnyOrThrow {
            checkClientTrusted(chain, authType, socket)
        }
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        trustManagers.checkForAnyOrThrow {
            checkServerTrusted(chain, authType, engine)
        }
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        trustManagers.checkForAnyOrThrow {
            checkClientTrusted(chain, authType, engine)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> =
        trustManagers.combinedAcceptedIssuers()
}

private inline fun <T : X509TrustManager> List<T>.checkForAnyOrThrow(
    block: T.() -> Unit,
) {
    var lastException: CertificateException? = null
    forEach { trustManager ->
        try {
            trustManager.block()
            return
        } catch (e: CertificateException) {
            lastException = e
        }
    }

    throw lastException
        ?: IllegalStateException(
            "No trust manager could validate the certificate.",
        )
}

private fun List<X509TrustManager>.combinedAcceptedIssuers(): Array<X509Certificate> =
    flatMap {
        it.acceptedIssuers
            .toList()
    }
        .distinct()
        .toTypedArray()
