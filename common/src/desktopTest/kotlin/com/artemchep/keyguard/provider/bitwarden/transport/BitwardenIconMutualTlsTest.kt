package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.icon.AccountIconServer
import com.artemchep.keyguard.common.service.icon.AccountIconServersImpl
import com.artemchep.keyguard.common.service.icon.ResolvedIcon
import com.artemchep.keyguard.common.service.icon.readIconResponse
import com.artemchep.keyguard.common.usecase.bitwardenToken
import com.artemchep.keyguard.common.usecase.keePassToken
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.ServiceToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.repository.ServiceTokenRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class BitwardenIconMutualTlsTest {
    private val service = MutualTlsServiceNative()

    @Test
    fun `account icons are downloaded with the client certificate`(): Unit = runBlocking {
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
        val scope = CoroutineScope(SupervisorJob())
        try {
            TestServer(serverIdentity, identity.certificate).use { server ->
                val env = ServerEnv(baseUrl = server.url, mutualTls = imported)
                val tokens = MutableStateFlow(
                    listOf(
                        bitwardenToken(BitwardenToken.Environment.of(env)),
                        keePassToken(FileLocation.Local(uri = "file:///vault.kdbx", displayName = "vault.kdbx")),
                    ),
                )
                val servers = AccountIconServersImpl(TestTokenRepository(tokens), provider, scope)

                val remote = assertIs<AccountIconServer.Remote>(servers.get(AccountId("bitwarden")))
                val url = Url(remote.requestUrl("example.com"))
                assertEquals("ok", remote.httpClient.readIconResponse(url, maxBytes = 1024).bytes.decodeToString())
                assertEquals(listOf("/icons/example.com/icon.png"), server.paths)
                assertEquals(identity.certificate.subjectX500Principal.name, server.peer)
                assertSame(AccountIconServer.Website, servers.get(AccountId("keepass")))
                assertNull(servers.get(AccountId("deleted")))

                // Without the certificate the server refuses the handshake.
                assertFails { base.readIconResponse(url, maxBytes = 1024) }

                // An icon server that redirects to another origin gets
                // no certificate sent there, nor anything else.
                TestServer(serverIdentity, null).use { other ->
                    server.redirect = other.url
                    assertFails { remote.httpClient.readIconResponse(url, maxBytes = 1024) }
                    assertEquals(0, other.requests.get())

                    // The icon loader follows it with a client
                    // that has no certificate.
                    val icon = ResolvedIcon(
                        url = url.toString(),
                        client = AccountHttpClient(remote.httpClient, base),
                    )
                    val response = icon.request { httpClient, target ->
                        httpClient.readIconResponse(Url(target), maxBytes = 1024)
                    }
                    assertEquals("ok", response.bytes.decodeToString())
                    assertEquals(1, other.requests.get())
                    assertNull(other.peer)
                }
            }
        } finally {
            scope.cancel()
            provider.close()
            base.close()
        }
    }
}

private class TestTokenRepository(
    private val tokens: Flow<List<ServiceToken>>,
) : ServiceTokenRepository {
    override fun get(): Flow<List<ServiceToken>> = tokens

    override fun getById(id: AccountId): IO<ServiceToken?> = error("Not used")

    override fun put(model: ServiceToken): IO<Unit> = io(Unit)
}
