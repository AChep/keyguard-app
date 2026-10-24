package com.artemchep.keyguard.common.service.http

import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.provider.bitwarden.api.BitwardenPersona
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyguardHttpClientConfigTest {
    @Test
    fun `bitwarden user agent identifies as the current platform client`() = runTest {
        val userAgent = sentUserAgent(KeyguardHttpUserAgent.Bitwarden)
        assertEquals(BitwardenPersona.of(CurrentPlatform).userAgent, userAgent)
    }

    @Test
    fun `curl user agent identifies as curl`() = runTest {
        val userAgent = sentUserAgent(KeyguardHttpUserAgent.Curl)
        assertTrue(userAgent.orEmpty().startsWith("curl/"), "User agent: $userAgent")
    }

    private suspend fun sentUserAgent(
        userAgent: KeyguardHttpUserAgent,
    ): String? {
        var sent: String? = null
        val engine = MockEngine { request ->
            sent = request.headers[HttpHeaders.UserAgent]
            respondOk()
        }
        HttpClient(engine) {
            configureKeyguardHttpClient(
                json = Json,
                userAgent = userAgent,
            )
        }.use { client ->
            client.get("https://example.com/")
        }
        return sent
    }
}
