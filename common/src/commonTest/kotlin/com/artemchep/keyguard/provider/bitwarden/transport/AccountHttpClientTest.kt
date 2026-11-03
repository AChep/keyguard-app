package com.artemchep.keyguard.provider.bitwarden.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AccountHttpClientTest {
    private val url = "https://vault.example.com/attachments/1"

    @Test
    fun `follows a redirect off the account's servers with the redirect client`() = runTest {
        val redirectRequests = mutableListOf<String>()
        val client = AccountHttpClient(
            httpClient = accountHttpClient(redirectTo = "https://storage.example.com/1?sig=x"),
            redirectHttpClient = HttpClient(
                MockEngine { request ->
                    redirectRequests += request.url.toString()
                    respond("ok")
                },
            ),
        )

        val body = client.request(url) { httpClient, target -> httpClient.get(target).bodyAsText() }

        assertEquals("ok", body)
        assertEquals(listOf("https://storage.example.com/1?sig=x"), redirectRequests)
    }

    @Test
    fun `does not follow a redirect to an insecure server`() = runTest {
        val client = AccountHttpClient(
            httpClient = accountHttpClient(redirectTo = "http://storage.example.com/1"),
            redirectHttpClient = HttpClient(MockEngine { error("Must not be requested.") }),
        )

        // The client does not follow a downgrade at all.
        val status = client.request(url) { httpClient, target -> httpClient.get(target).status }

        assertEquals(HttpStatusCode.Found, status)
    }

    @Test
    fun `does not send an insecure request without the certificate`() = runTest {
        val client = AccountHttpClient(
            httpClient = accountHttpClient(redirectTo = "https://storage.example.com/1"),
            redirectHttpClient = HttpClient(MockEngine { error("Must not be requested.") }),
        )

        assertFailsWith<MutualTlsOriginException> {
            client.request("http://vault.example.com/attachments/1") { httpClient, target -> httpClient.get(target) }
        }
    }

    @Test
    fun `fails without a redirect client`() = runTest {
        val client = AccountHttpClient(
            httpClient = accountHttpClient(redirectTo = "https://storage.example.com/1"),
        )

        assertFailsWith<MutualTlsOriginException> {
            client.request(url) { httpClient, target -> httpClient.get(target) }
        }
    }

    private fun accountHttpClient(redirectTo: String) = HttpClient(
        MockEngine { request ->
            assertEquals("vault.example.com", request.url.host)
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, redirectTo),
            )
        },
    ) {
        configureBitwardenMutualTlsClient(Json, setOf("vault.example.com:443"))
    }
}
