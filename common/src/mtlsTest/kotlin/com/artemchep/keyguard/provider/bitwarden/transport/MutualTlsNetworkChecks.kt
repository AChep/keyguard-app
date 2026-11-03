package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import io.ktor.client.HttpClient
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

/** The same real-network contract runs with the JVM, Android and Darwin engines. */
suspend fun verifyMutualTlsTransport(
    port: String?,
    bootstrap: HttpClient,
    clients: (ca: ByteArray) -> Pair<HttpClient, BitwardenHttpClientFactory>,
) = withTimeout(60_000) {
    assertNotNull(port, "Run this suite with scripts/run_mtls_tests.py")
    val controlUrl = "http://localhost:$port/"
    val fixture = Json.parseToJsonElement(bootstrap.read(controlUrl + "fixture")).jsonObject
    fun field(name: String) = fixture.getValue(name).jsonPrimitive.content
    fun identity(name: String): ServerEnv.MutualTls.ImportedPkcs12 {
        val bytes = Base64.decode(field(name))
        return try {
            assertIs<MutualTlsImportResult.Success>(MutualTlsServiceNative().importPkcs12(bytes, "fixture")).imported
        } finally {
            bytes.fill(0)
        }
    }
    val env = ServerEnv(
        baseUrl = field("baseUrl"),
        notificationsUrl = field("notificationsUrl"),
        mutualTls = identity("first"),
    )
    val (base, factory) = clients(Base64.decode(field("ca")))
    val provider = BitwardenHttpClientProviderImpl(base, factory)
    try {
        val first = provider.client(env)
        // The server trusts only the root; success requires sending the intermediate too.
        assertEquals("client-one", first.read(env.baseUrl + "identity"))
        rejected("a missing certificate") { base.read(env.baseUrl + "identity") }
        rejected("an identity signed by a different CA") {
            provider.client(env.copy(mutualTls = identity("wrong"))).read(env.baseUrl + "identity")
        }

        // RSA -> P-256 rotation, including a separate connection for the notifications origin.
        val replacement = env.copy(mutualTls = identity("second"))
        val second = provider.client(replacement)
        assertEquals("client-two", second.read(env.baseUrl + "identity"))
        exchange(first, env.notificationsUrl, "client-one")
        exchange(second, env.notificationsUrl, "client-two")
        rejected("a WebSocket without a client certificate") { exchange(base, env.notificationsUrl, "none") }
        rejected("a removed identity") {
            provider.client(replacement.copy(mutualTls = null)).read(env.baseUrl + "identity")
        }

        // A configured destination must still pass both CA and hostname verification.
        val untrusted = env.copy(baseUrl = field("untrustedUrl"))
        rejected("an untrusted server certificate") { provider.client(untrusted).read(untrusted.baseUrl) }
        val wrongHost = env.copy(baseUrl = env.baseUrl.replace("localhost", "127.0.0.1"))
        rejected("a server certificate for a different hostname") { provider.client(wrongHost).read(wrongHost.baseUrl) }

        val externalUrl = field("externalUrl")
        val externalPort = Url(externalUrl).port.toString()
        suspend fun externalActivity(): String = Json.parseToJsonElement(
            bootstrap.read(controlUrl + "counts"),
        ).jsonObject.getValue(externalPort).toString()
        val before = externalActivity()
        rejected("a redirect outside the configured origins") { first.read(env.baseUrl + "redirect") }
        rejected("a WebSocket outside the configured origins") { exchange(first, externalUrl, "client-one") }
        assertEquals(before, externalActivity(), "An unconfigured server must receive no TLS connections or requests")

        // Signed transfer URLs outside the account's servers still work, without its identity.
        assertEquals("none", provider.clientForUrl(env, externalUrl).read(externalUrl + "identity"))
        rejected("a cleartext transfer URL") { provider.clientForUrl(env, controlUrl) }
        provider.close()
        rejected("a closed vault session") { provider.client(env) }
    } finally {
        provider.close()
        base.close()
    }
}

private suspend fun exchange(client: HttpClient, url: String, peer: String) {
    client.webSocket(
        urlString = url.replace("https:", "wss:") + "ws",
        request = { retry { maxRetries = 0 } },
    ) {
        assertEquals(peer, assertIs<Frame.Text>(incoming.receive()).readText())
        send(Frame.Text("ping"))
        assertEquals("pong:$peer", assertIs<Frame.Text>(incoming.receive()).readText())
    }
}

private suspend fun rejected(description: String, block: suspend () -> Unit) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error // Propagate cancellation. A timeout means the test failed before TLS could reject the peer.
    } catch (_: Exception) {
        return
    }
    fail("Expected rejection of $description")
}

private suspend fun HttpClient.read(url: String): String =
    get(url) { retry { maxRetries = 0 } }.bodyAsText()
