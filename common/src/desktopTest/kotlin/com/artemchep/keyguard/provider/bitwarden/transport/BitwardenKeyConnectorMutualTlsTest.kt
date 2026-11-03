package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame

class BitwardenKeyConnectorMutualTlsTest {
    private val service = MutualTlsServiceNative()

    @Test
    fun `key connector on its own host gets the client certificate`(): Unit = runBlocking {
        val identity = TestIdentity("client")
        val serverIdentity = TestIdentity("localhost")
        val trustedServer = trust(serverIdentity.certificate)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustedServer), null) }
        val okHttp = OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trustedServer).build()
        val base = HttpClient(OkHttp) { engine { preconfigured = okHttp } }
        val provider = BitwardenHttpClientProviderImpl(base, BitwardenHttpClientFactoryJvm(okHttp, Json))
        val imported = assertIs<MutualTlsImportResult.Success>(
            service.importPkcs12(identity.bundle(""), null),
        ).imported
        try {
            TestServer(serverIdentity, identity.certificate).use { server ->
                TestServer(serverIdentity, identity.certificate).use { keyConnector ->
                    val env = ServerEnv(baseUrl = server.url, mutualTls = imported)
                    val url = keyConnector.url + "user-keys"

                    // Signed transfer URLs on another host never get the certificate.
                    assertFails { provider.clientForUrl(env, url).get(url) }
                    assertEquals(0, keyConnector.requests.get())

                    val client = provider.clientForKeyConnector(env, url)
                    assertEquals("ok", client.get(url).bodyAsText())
                    assertEquals(identity.certificate.subjectX500Principal.name, keyConnector.peer)
                    assertEquals(listOf("/user-keys"), keyConnector.paths)

                    // The client is still bound to the account's servers and the Key Connector.
                    TestServer(serverIdentity, identity.certificate).use { other ->
                        assertFails { client.get(other.url) }
                        assertEquals(0, other.requests.get())
                    }
                    assertFails { provider.clientForKeyConnector(env, url.replace("https:", "http:")) }
                    assertSame(base, provider.clientForKeyConnector(env.copy(mutualTls = null), url))
                }
            }
        } finally {
            provider.close()
            base.close()
        }
    }
}
