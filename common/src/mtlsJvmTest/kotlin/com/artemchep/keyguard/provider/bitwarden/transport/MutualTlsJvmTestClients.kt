package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.common.service.http.KeyguardHttpUserAgent
import com.artemchep.keyguard.common.service.http.configureKeyguardHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

fun mutualTlsJvmTestClients(ca: ByteArray): Pair<HttpClient, BitwardenHttpClientFactory> {
    val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null)
        setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(ca.inputStream()))
    }
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    val okHttp = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust).build()
    val base = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        configureKeyguardHttpClient(Json, KeyguardHttpUserAgent.Bitwarden)
    }
    return base to BitwardenHttpClientFactoryJvm(okHttp, Json)
}
