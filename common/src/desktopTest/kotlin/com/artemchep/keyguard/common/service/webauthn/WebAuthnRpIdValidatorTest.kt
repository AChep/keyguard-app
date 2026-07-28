package com.artemchep.keyguard.common.service.webauthn

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.tld.TldService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAuthnRpIdValidatorTest {
    @Test
    fun `rp id validation converts unicode domain to ascii`() = runTest {
        val validator = createValidator()

        val rpId = validator.requireValidRpId("bücher.example")

        assertEquals("xn--bcher-kva.example", rpId)
    }

    @Test
    fun `rp id validation uses non transitional UTS46 processing`() = runTest {
        val validator = createValidator()

        val rpId = validator.requireValidRpId("faß.example")

        assertEquals("xn--fa-hia.example", rpId)
    }

    @Test
    fun `rp validation allows unicode rp id matching punycoded origin`() = runTest {
        var requestCount = 0
        val validator = createValidator {
            requestCount++
        }

        validator.requireRpMatchesOrigin(
            rpId = "bücher.example",
            origin = "https://xn--bcher-kva.example",
        )

        assertEquals(0, requestCount)
    }

    @Test
    fun `rp id validation rejects malformed ace label`() = runTest {
        val validator = createValidator()

        assertFailsWith<IllegalArgumentException> {
            validator.requireValidRpId("xn--a.example")
        }
    }

    private fun createValidator(
        onRequest: (String) -> Unit = {},
    ): WebAuthnRpIdValidator {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(
                content = """{"origins":[]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    ContentType.Application.Json.toString(),
                ),
            )
        }
        val httpClient = HttpClient(engine)
        return WebAuthnRpIdValidator(
            tldService = FakeTldService,
            relatedOrigins = WebAuthnRelatedOrigins(
                tldService = FakeTldService,
                httpClient = httpClient,
            ),
        )
    }
}

private object FakeTldService : TldService {
    override val version: String = "test"

    private val publicSuffixes = setOf(
        "com",
        "example",
    )

    override fun getDomainName(
        host: String,
    ): IO<String> = {
        val normalizedHost = host.lowercase()
        val publicSuffix = publicSuffixes
            .filter { suffix ->
                normalizedHost == suffix ||
                        normalizedHost.endsWith(".$suffix")
            }
            .maxByOrNull { suffix ->
                suffix.count { it == '.' }
            }
        if (publicSuffix == null) {
            normalizedHost
        } else {
            val hostLabels = normalizedHost.split('.')
            val suffixLabelCount = publicSuffix.split('.').size
            if (hostLabels.size <= suffixLabelCount) {
                normalizedHost
            } else {
                hostLabels
                    .takeLast(suffixLabelCount + 1)
                    .joinToString(separator = ".")
            }
        }
    }
}
