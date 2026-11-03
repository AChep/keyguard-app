package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import com.artemchep.keyguard.provider.bitwarden.api.builder.headers
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BitwardenMutualTlsTest {
    private val service = MutualTlsServiceNative()

    @Test
    fun `imports protected empty and unprotected stores and rejects ambiguous identities`() {
        for (password in listOf(null, "", "secret")) {
            val source = TestIdentity("client").bundle(password)
            if (!password.isNullOrEmpty()) {
                assertIs<MutualTlsImportResult.PasswordRequired>(service.importPkcs12(source, null))
                assertIs<MutualTlsImportResult.InvalidPassword>(service.importPkcs12(source, "wrong"))
            }
            val result = assertIs<MutualTlsImportResult.Success>(service.importPkcs12(source, password))
            val metadata = assertNotNull(service.describeImportedPkcs12(result.imported.pkcs12Base64))
            assertTrue(metadata.displayName.isNotBlank())
            assertEquals(result.metadata, metadata)
        }
        // Public details survive the native protocol in display form.
        val imported = service.importPkcs12(TestIdentity("client").bundle(null), null)
        val details = assertNotNull(
            service.describeImportedPkcs12(assertIs<MutualTlsImportResult.Success>(imported).imported.pkcs12Base64),
        )
        assertEquals("CN=client", details.subject)
        assertEquals("CN=client", details.issuer)
        assertEquals("client", details.issuerName)
        assertEquals("RSA 2048", details.keyType)
        assertTrue(details.validFrom < details.expiresAt)
        assertTrue(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(details.sha256Fingerprint), details.sha256Fingerprint)
        assertTrue(Regex("[0-9A-F]{2}(:[0-9A-F]{2})*").matches(details.serialNumber), details.serialNumber)
        val multiple = TestIdentity("one").bundle("secret", TestIdentity("two"))
        assertIs<MutualTlsImportResult.Error>(service.importPkcs12(multiple, "secret"))
        assertIs<MutualTlsImportResult.Error>(service.importPkcs12(byteArrayOf(1, 2, 3), null))
    }

    @Test
    fun `replacement sends the new identity while old clients remain usable and removal uses plain TLS`(): Unit =
        runBlocking {
            val first = TestIdentity("first")
            val second = TestIdentity("second")
            val serverIdentity = TestIdentity("localhost")
            val trustedServer = trust(serverIdentity.certificate)
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustedServer), null) }
            val okHttp = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trustedServer).build()
            val base = HttpClient(OkHttp) { engine { preconfigured = okHttp } }
            val provider = BitwardenHttpClientProviderImpl(base, BitwardenHttpClientFactoryJvm(okHttp, Json))
            fun imported(identity: TestIdentity) = assertIs<MutualTlsImportResult.Success>(
                service.importPkcs12(identity.bundle(""), null),
            ).imported
            try {
                TestServer(serverIdentity, first.certificate, second.certificate).use { server ->
                    val original = ServerEnv(baseUrl = server.url, mutualTls = imported(first))
                    val oldClient = provider.client(original)
                    oldClient.get(server.url)
                    assertEquals(first.certificate.subjectX500Principal.name, server.peer)
                    val replacement = original.copy(mutualTls = imported(second))
                    provider.client(replacement).get(server.url)
                    assertEquals(second.certificate.subjectX500Principal.name, server.peer)
                    // Another account sharing the old identity, and already-running work, keep their client.
                    assertSame(oldClient, provider.client(original))
                    oldClient.get(server.url)
                    assertEquals(first.certificate.subjectX500Principal.name, server.peer)
                    assertSame(base, provider.client(replacement.copy(mutualTls = null)))
                    assertFails { provider.client(replacement.copy(mutualTls = null)).get(server.url) }
                }
            } finally {
                provider.close()
                base.close()
            }
        }

    @Test
    fun `clients of certificates the accounts stopped using are closed`() {
        val base = HttpClient(MockEngine { respondOk() })
        val provider = BitwardenHttpClientProviderImpl(base) { _, _ -> HttpClient(MockEngine { respondOk() }) }
        fun env(pkcs12Base64: String) = ServerEnv(
            baseUrl = "https://vault.example.com/",
            mutualTls = ServerEnv.MutualTls.ImportedPkcs12(pkcs12Base64),
        )
        val original = env("AAAA")
        val replacement = env("BBBB")
        val unsaved = env("CCCC")
        try {
            val originalClient = provider.client(original)
            val unsavedClient = provider.client(unsaved)
            provider.updateAccounts(listOf(original))
            assertSame(originalClient, provider.client(original))

            // The account replaced its certificate.
            provider.updateAccounts(listOf(replacement))
            assertFalse(originalClient.isActive)
            assertNotSame(originalClient, provider.client(original))
            // A certificate that was never stored, such as
            // one of an account being added, keeps its client.
            assertTrue(unsavedClient.isActive)
            assertSame(unsavedClient, provider.client(unsaved))
        } finally {
            provider.close()
            base.close()
        }
    }

    @Test
    fun `client identity reaches only configured HTTPS origins and headers are added once`(): Unit = runBlocking {
        val identity = TestIdentity("client")
        val serverIdentity = TestIdentity("localhost")
        val trustedServer = trust(serverIdentity.certificate)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustedServer), null) }
        val okHttp = OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustedServer)
            .build()
        val base = HttpClient(OkHttp) { engine { preconfigured = okHttp } }
        val provider = BitwardenHttpClientProviderImpl(base, BitwardenHttpClientFactoryJvm(okHttp, Json))
        val imported = assertIs<MutualTlsImportResult.Success>(
            service.importPkcs12(identity.bundle("secret"), "secret"),
        ).imported
        try {
            TestServer(serverIdentity, identity.certificate).use { server ->
                val env = ServerEnv(
                    baseUrl = server.url,
                    mutualTls = imported,
                    headers = listOf(ServerHeader("X-Test", "one"), ServerHeader("X-Test", "two")),
                )
                assertFails { base.get(server.url) }
                val client = provider.client(env)
                assertEquals("ok", client.get(server.url) { headers(env) }.bodyAsText())
                val headerValues = server.headers?.flatMap { it.split(',').map(String::trim) }
                assertEquals(listOf("one", "two"), headerValues)
                assertEquals(identity.certificate.subjectX500Principal.name, server.peer)
                val otherIdentity = assertIs<MutualTlsImportResult.Success>(
                    service.importPkcs12(TestIdentity("other").bundle(""), null),
                ).imported
                assertNotSame(client, provider.client(env.copy(mutualTls = otherIdentity)))
                assertFails { provider.client(env.copy(mutualTls = otherIdentity)).get(server.url) }
                assertFails {
                    provider.client(env.copy(mutualTls = ServerEnv.MutualTls.ImportedPkcs12("invalid")))
                }
                assertSame(client, provider.client(env.copy(headers = emptyList())))
                assertNotSame(client, provider.client(env.copy(identityUrl = "https://identity.example.test/")))
                assertSame(base, provider.clientForUrl(env, "https://storage.example.test/file"))
                assertSame(base, provider.client(ServerEnv()))
                assertFails { provider.clientForUrl(env, "http://storage.example.test/file") }
                assertFails { client.get(server.url.replace("https:", "http:")) }
                TestServer(serverIdentity, null).use { other ->
                    server.redirect = other.url
                    assertFails { client.get(server.url) }
                    assertEquals(0, other.requests.get())
                    assertEquals("ok", provider.clientForUrl(env, other.url).get(other.url).bodyAsText())
                    assertNull(other.peer)
                    provider.close()
                    assertEquals("ok", base.get(other.url).bodyAsText())
                }
            }
        } finally {
            provider.close()
            base.close()
        }
        assertFails { provider.client(ServerEnv()) }
    }
}
