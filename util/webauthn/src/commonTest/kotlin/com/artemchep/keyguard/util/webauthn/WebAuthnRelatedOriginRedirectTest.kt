package com.artemchep.keyguard.util.webauthn

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebAuthnRelatedOriginRedirectTest {
    @Test
    fun `https rp validation follows https redirects for related origin document`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val validator = createWebAuthnRpIdTestValidator(
            responses = listOf(
                WebAuthnMockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://static.example.com/.well-known/webauthn",
                    ),
                ),
                WebAuthnMockHttpResponse(
                    responseBody = """{"origins":["https://example.co.uk"]}""",
                ),
            ),
        ) { url ->
            requestedUrls += url
        }

        validator.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
        )

        assertEquals(
            listOf(
                "https://example.com/.well-known/webauthn",
                "https://static.example.com/.well-known/webauthn",
            ),
            requestedUrls,
        )
    }

    @Test
    fun `https rp validation rejects related origin document redirect loop above limit`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val validator = createWebAuthnRpIdTestValidator(
            responses = List(21) {
                WebAuthnMockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://example.com/.well-known/webauthn",
                    ),
                )
            },
        ) { url ->
            requestedUrls += url
        }

        val error = assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }

        assertTrue(
            actual = error.message.orEmpty()
                .contains("Related origins document redirects too many times."),
            message = error.message,
        )
        assertEquals(21, requestedUrls.size)
    }

    @Test
    fun `https rp validation allows https redirect to unrelated related origin document host`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val validator = createWebAuthnRpIdTestValidator(
            responses = listOf(
                WebAuthnMockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://evil-example.com/.well-known/webauthn",
                    ),
                ),
                WebAuthnMockHttpResponse(
                    responseBody = """{"origins":["https://example.co.uk"]}""",
                ),
            ),
        ) { url ->
            requestedUrls += url
        }

        validator.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
        )

        assertEquals(
            listOf(
                "https://example.com/.well-known/webauthn",
                "https://evil-example.com/.well-known/webauthn",
            ),
            requestedUrls,
        )
    }

    @Test
    fun `https rp validation rejects http redirects for related origin document`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val validator = createWebAuthnRpIdTestValidator(
            responses = listOf(
                WebAuthnMockHttpResponse(
                    status = HttpStatusCode.Found,
                    contentType = null,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "http://example.com/.well-known/webauthn",
                    ),
                ),
                WebAuthnMockHttpResponse(
                    responseBody = """{"origins":["https://example.co.uk"]}""",
                ),
            ),
        ) { url ->
            requestedUrls += url
        }

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
        assertEquals(
            listOf("https://example.com/.well-known/webauthn"),
            requestedUrls,
        )
    }
}
