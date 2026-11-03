package com.artemchep.keyguard.provider.bitwarden.transport

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsParameters
import com.sun.net.httpserver.HttpsServer
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal class TestIdentity(name: String) {
    private val provider = BouncyCastleProvider()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val certificate: X509Certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
        JcaX509v3CertificateBuilder(
            X500Name("CN=$name"), BigInteger.valueOf(System.nanoTime()),
            Date(System.currentTimeMillis() - 60_000), Date(System.currentTimeMillis() + 86_400_000),
            X500Name("CN=$name"), keys.public,
        ).addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, "localhost")),
        )
            .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keys.private)),
    )

    fun store(password: String?, second: TestIdentity? = null): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry("client", keys.private, password?.toCharArray(), arrayOf(certificate))
        second?.let { setKeyEntry("second", it.keys.private, password?.toCharArray(), arrayOf(it.certificate)) }
    }

    fun bundle(password: String?, second: TestIdentity? = null): ByteArray = ByteArrayOutputStream().use {
        store(password, second).store(it, password?.toCharArray())
        it.toByteArray()
    }
}

internal fun trust(vararg certificates: X509Certificate): X509TrustManager {
    val store = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate -> setCertificateEntry("trusted-$index", certificate) }
    }
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
}

internal class TestServer(
    identity: TestIdentity,
    client: X509Certificate?,
    vararg otherClients: X509Certificate,
) : AutoCloseable {
    val requests = AtomicInteger()
    val paths = CopyOnWriteArrayList<String>()
    @Volatile var headers: List<String>? = null
    @Volatile var peer: String? = null
    @Volatile var redirect: String? = null
    private val context = SSLContext.getInstance("TLS").apply {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(identity.store(""), charArrayOf())
        }
        init(keys.keyManagers, client?.let { arrayOf(trust(it, *otherClients)) }, null)
    }
    private val server = HttpsServer.create(InetSocketAddress("localhost", 0), 0).apply {
        httpsConfigurator = object : HttpsConfigurator(context) {
            override fun configure(params: HttpsParameters) {
                params.setSSLParameters(context.defaultSSLParameters.apply { needClientAuth = client != null })
            }
        }
        createContext("/") { exchange ->
            requests.incrementAndGet()
            paths += exchange.requestURI.path
            headers = exchange.requestHeaders["X-Test"]
            peer = runCatching {
                (exchange as com.sun.net.httpserver.HttpsExchange).sslSession.peerPrincipal.name
            }.getOrNull()
            val target = redirect
            if (target != null) {
                exchange.responseHeaders.add("Location", target)
                exchange.sendResponseHeaders(302, -1)
            } else {
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
            exchange.close()
        }
        start()
    }
    val url get() = "https://localhost:${server.address.port}/"
    override fun close() = server.stop(0)
}
