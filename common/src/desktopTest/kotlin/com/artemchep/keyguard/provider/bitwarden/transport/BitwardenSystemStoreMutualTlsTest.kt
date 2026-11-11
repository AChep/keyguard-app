package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.crypto.ssl.createSslSocketFactory
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BitwardenSystemStoreMutualTlsTest {
    private val certificate = ServerEnv.MutualTls.SystemStore("work laptop")
    private val TestServer.env get() = ServerEnv(baseUrl = url, mutualTls = certificate)

    @Test
    fun `server gets the certificate of the system store and the store is read once`(): Unit = runBlocking {
        val store = FakeIdentities().apply { identities[certificate.alias] = rsaClient }
        withProvider(store) { provider ->
            TestServer(serverIdentity, rsaClient.certificate).use { server ->
                val http = provider.client(server.env)
                assertEquals("ok", http.get(server.url).bodyAsText())
                assertEquals(rsaClient.certificate.subjectX500Principal.name, server.peer)
                assertEquals("ok", http.get(server.url).bodyAsText())
                assertEquals(1, store.loads.get())

                // Accounts that chose the same certificate share a client.
                assertSame(http, provider.client(server.env))
                val other = server.env.copy(mutualTls = ServerEnv.MutualTls.SystemStore("other"))
                assertNotSame(http, provider.client(other))
            }
        }
    }

    @Test
    fun `missing certificate fails before any request and is not retried`(): Unit = runBlocking {
        val store = FakeIdentities()
        withProvider(store) { provider ->
            // The server asks for no certificate: a request would get through.
            TestServer(serverIdentity, null).use { server ->
                assertFailsWith<MutualTlsCertificateMissingException> {
                    provider.client(server.env).get(server.url)
                }
                assertEquals(0, server.requests.get())
                assertEquals(1, store.loads.get())

                // The failure is not kept: once the user chooses
                // the certificate again, the same client works.
                store.identities[certificate.alias] = ecClient
                assertEquals("ok", provider.client(server.env).get(server.url).bodyAsText())
            }
        }
    }

    @Test
    fun `platform without a certificate store reports the certificate as missing`(): Unit = runBlocking {
        withProvider(store = null) { provider ->
            TestServer(serverIdentity, null).use { server ->
                assertFailsWith<MutualTlsCertificateMissingException> {
                    provider.client(server.env).get(server.url)
                }
                assertEquals(0, server.requests.get())
            }
        }
    }

    @Test
    fun `failure of the store is an input-output failure`() {
        val store = FakeIdentities().apply { failure = IllegalStateException("The store is unavailable") }
        val source = SystemStoreIdentitySource(certificate, store)

        // Anything else would escape the HTTP client and end the process.
        val e = assertFailsWith<IOException> { source.getOrLoad() }
        assertIs<IllegalStateException>(e.cause)
        assertFalse(e is MutualTlsCertificateMissingException)

        // The key manager offers no certificate rather than failing the handshake.
        val keys = SystemStoreKeyManager(source)
        assertNull(keys.chooseClientAlias(arrayOf("EC"), null, null))
        assertNull(keys.getPrivateKey(certificate.alias))
        assertNull(keys.getCertificateChain(certificate.alias))
    }

    @Test
    fun `failed handshake reads the store again`() {
        val store = FakeIdentities().apply { identities[certificate.alias] = ecClient }
        val interceptor = SystemStoreIdentityInterceptor(SystemStoreIdentitySource(certificate, store))
        val request = Request.Builder().url("https://vault.example.com/").build()
        val chain = proxy<Interceptor.Chain> { method ->
            when (method) {
                "request" -> request
                // The key left the store after it was loaded.
                "proceed" -> throw SSLHandshakeException("Received fatal alert: bad_certificate")
                else -> error("Unexpected call: $method")
            }
        }

        assertFailsWith<SSLHandshakeException> { interceptor.intercept(chain) }
        assertEquals(1, store.loads.get())
        store.identities.clear()
        assertFailsWith<MutualTlsCertificateMissingException> { interceptor.intercept(chain) }
        assertEquals(2, store.loads.get())
    }

    @Test
    fun `identity loaded by another request survives a late failure of an old one`() {
        val store = FakeIdentities().apply { identities[certificate.alias] = ecClient }
        val source = SystemStoreIdentitySource(certificate, store)
        val old = assertNotNull(source.getOrLoad())
        source.invalidate(old)
        val fresh = assertNotNull(source.getOrLoad())

        // A request that still held the old identity fails only now.
        source.invalidate(old)

        assertSame(fresh, source.getOrLoad())
        assertEquals(2, store.loads.get())
    }

    @Test
    fun `key manager presents only its own alias`() {
        val store = FakeIdentities().apply { identities[certificate.alias] = ecClient }
        val keys = SystemStoreKeyManager(SystemStoreIdentitySource(certificate, store))

        assertEquals(certificate.alias, keys.chooseClientAlias(arrayOf("EC"), null, null))
        assertEquals(certificate.alias, keys.chooseEngineClientAlias(arrayOf("EC"), null, null))
        assertSame(ecClient.privateKey, keys.getPrivateKey(certificate.alias))
        assertEquals(ecClient.certificate, assertNotNull(keys.getCertificateChain(certificate.alias)).single())
        assertNull(keys.getPrivateKey("other"))
        assertNull(keys.getCertificateChain("other"))
        assertNull(keys.chooseServerAlias("EC", null, null))
    }

    @Test
    fun `client of a system certificate the accounts stopped using is closed`() {
        val base = HttpClient(MockEngine { respondOk() })
        val provider = BitwardenHttpClientProviderImpl(base) { _, _ -> HttpClient(MockEngine { respondOk() }) }
        fun env(mutualTls: ServerEnv.MutualTls) = ServerEnv(
            baseUrl = "https://vault.example.com/",
            mutualTls = mutualTls,
        )
        val original = env(certificate)
        // An alias never equals the fingerprint of a bundle, whatever its text.
        val bundle = env(ServerEnv.MutualTls.ImportedPkcs12("AAAA"))
        try {
            val originalClient = provider.client(original)
            assertNotSame(originalClient, provider.client(bundle))
            provider.updateAccounts(listOf(original))
            assertSame(originalClient, provider.client(original))

            // The account replaced the system certificate with a file.
            provider.updateAccounts(listOf(bundle))
            assertFalse(originalClient.isActive)
            assertNotSame(originalClient, provider.client(original))
        } finally {
            provider.close()
            base.close()
        }
    }

    @Test
    fun `common certificates are described the way the native reader describes them`() {
        val service = MutualTlsServiceNative()
        for (identity in listOf(rsaClient, ecClient)) {
            val native = assertIs<MutualTlsImportResult.Success>(
                service.importPkcs12(identity.bundle(""), null),
            ).metadata
            val described = identity.certificate.toMutualTlsMetadata(
                displayName = native.displayName,
                issuerName = native.issuerName,
            )
            assertEquals(native, described)
        }
        val elliptic = ecClient.certificate.toMutualTlsMetadata(displayName = "", issuerName = null)
        assertEquals("EC P-256", elliptic.keyType)
        // Without a common name, the issuer is shown in full; without a name, the subject is.
        assertEquals("CN=client", elliptic.issuerName)
        assertTrue(elliptic.displayName.isNotBlank())
    }

    private suspend fun withProvider(
        store: MutualTlsSystemIdentities?,
        block: suspend (BitwardenHttpClientProvider) -> Unit,
    ) {
        val base = HttpClient(OkHttp) { engine { preconfigured = okHttp } }
        val provider = BitwardenHttpClientProviderImpl(base, BitwardenHttpClientFactoryJvm(okHttp, Json, store))
        try {
            block(provider)
        } finally {
            provider.close()
            base.close()
        }
    }

    private class FakeIdentities : MutualTlsSystemIdentities {
        val identities = ConcurrentHashMap<String, TestIdentity>()
        val loads = AtomicInteger()

        @Volatile
        var failure: RuntimeException? = null

        override fun load(certificate: ServerEnv.MutualTls.SystemStore): MutualTlsIdentity? {
            loads.incrementAndGet()
            failure?.let { throw it }
            val identity = identities[certificate.alias]
                ?: return null
            return MutualTlsIdentity(identity.privateKey, arrayOf(identity.certificate))
        }
    }

    // Generating an RSA key is slow, so every test shares these.
    private companion object {
        val serverIdentity = TestIdentity("localhost")
        val trustedServer = trust(serverIdentity.certificate)
        val okHttp: OkHttpClient = OkHttpClient.Builder()
            .sslSocketFactory(createSslSocketFactory(trustedServer), trustedServer)
            .build()
        val rsaClient = TestIdentity("client")
        val ecClient = TestIdentity("client", elliptic = true)
    }
}

private inline fun <reified T> proxy(crossinline call: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        call(method.name)
    } as T
