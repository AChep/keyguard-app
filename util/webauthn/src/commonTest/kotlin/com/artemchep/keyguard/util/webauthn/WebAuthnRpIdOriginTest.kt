package com.artemchep.keyguard.util.webauthn

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAuthnRpIdOriginTest {
    // Spec coverage: Sections 5.1.3 and 5.1.4.1 establish rp.id/rpId from the
    // caller effective domain, and Section 5.11 allows related origins only
    // after the direct equal-or-suffix RP ID check fails.
    @Test
    fun `https rp validation allows exact effective domain`() = runTest {
        var requestCount = 0
        val validator = createWebAuthnRpIdTestValidator {
            requestCount++
        }

        validator.requireRpMatchesOrigin(
            rpId = "login.example.com",
            origin = "https://login.example.com",
        )

        assertEquals(0, requestCount)
    }

    @Test
    fun `https rp validation allows same registrable domain suffix`() = runTest {
        var requestCount = 0
        val validator = createWebAuthnRpIdTestValidator {
            requestCount++
        }

        validator.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://login.example.com",
        )

        assertEquals(0, requestCount)
    }

    // Spec coverage: HTML's registrable-domain-suffix algorithm rejects suffix
    // matches that cross a public suffix boundary; its examples call out
    // `*.compute.amazonaws.com` directly. WebAuthn L3 then falls through to
    // related-origin validation instead of accepting the RP ID directly.
    @Test
    fun `https rp validation falls through across wildcard public suffix boundary`() = runTest {
        var requestedUrl: String? = null
        val validator = createWebAuthnRpIdTestValidator { url ->
            requestedUrl = url
        }

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "compute.amazonaws.com",
                origin = "https://www.example.compute.amazonaws.com",
            )
        }

        assertEquals(
            "https://compute.amazonaws.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation allows wildcard public suffix boundary via related origins`() = runTest {
        var requestedUrl: String? = null
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://www.example.compute.amazonaws.com"]}""",
        ) { url ->
            requestedUrl = url
        }

        validator.requireRpMatchesOrigin(
            rpId = "compute.amazonaws.com",
            origin = "https://www.example.compute.amazonaws.com",
        )

        assertEquals(
            "https://compute.amazonaws.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation keeps trailing dot significant for explicit rp id`() = runTest {
        var requestedUrl: String? = null
        val validator = createWebAuthnRpIdTestValidator { url ->
            requestedUrl = url
        }

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com.",
                origin = "https://example.com",
            )
        }

        assertEquals(
            "https://example.com./.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation keeps trailing dot significant for origin host`() = runTest {
        var requestedUrl: String? = null
        val validator = createWebAuthnRpIdTestValidator { url ->
            requestedUrl = url
        }

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.com.",
            )
        }

        assertEquals(
            "https://example.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation allows matching trailing dot rp id and origin`() = runTest {
        var requestCount = 0
        val validator = createWebAuthnRpIdTestValidator {
            requestCount++
        }

        validator.requireRpMatchesOrigin(
            rpId = "example.com.",
            origin = "https://login.example.com.",
        )

        assertEquals(0, requestCount)
    }

    @Test
    fun `https rp validation rejects public suffix rp id`() = runTest {
        val validator = createWebAuthnRpIdTestValidator()

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "co.uk",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects rp id that is not suffix of origin domain`() = runTest {
        var requestedUrl: String? = null
        val validator = createWebAuthnRpIdTestValidator { url ->
            requestedUrl = url
        }

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "m.login.example.com",
                origin = "https://login.example.com",
            )
        }

        assertEquals(
            "https://m.login.example.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    // Spec coverage: Section 5.1.3/5.1.4.1 requires a valid-domain effective
    // domain. Section 4's RP ID definition permits HTTPS web origins, with an
    // HTTP exception only when the origin host is exactly localhost.
    @Test
    fun `web rp validation allows http localhost origin`() = runTest {
        var requestCount = 0
        val validator = createWebAuthnRpIdTestValidator {
            requestCount++
        }

        validator.requireRpMatchesOrigin(
            rpId = "localhost",
            origin = "http://localhost:8080",
        )

        assertEquals(0, requestCount)
    }

    @Test
    fun `web rp validation rejects http non-localhost origin`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["http://example.co.uk"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.co.uk",
                origin = "http://example.co.uk",
            )
        }
    }

    @Test
    fun `web rp validation rejects http origins whose host is not exactly localhost`() = runTest {
        val invalidOrigins = listOf(
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
            "http://sub.localhost:8080",
            "http://localhost.example.com",
        )

        invalidOrigins.forEach { origin ->
            var requestCount = 0
            val validator = createWebAuthnRpIdTestValidator {
                requestCount++
            }

            assertFailsWith<RuntimeException> {
                validator.requireRpMatchesOrigin(
                    rpId = "localhost",
                    origin = origin,
                )
            }

            assertEquals(0, requestCount)
        }
    }

    @Test
    fun `web rp validation rejects related localhost origins`() = runTest {
        val relatedOrigins = listOf(
            "http://localhost:8080",
            "https://localhost:8443",
        )

        relatedOrigins.forEach { relatedOrigin ->
            var requestedUrl: String? = null
            val validator = createWebAuthnRpIdTestValidator(
                responseBody = """{"origins":["$relatedOrigin"]}""",
            ) { url ->
                requestedUrl = url
            }

            assertFailsWith<RuntimeException> {
                validator.requireRpMatchesOrigin(
                    rpId = "example.com",
                    origin = relatedOrigin,
                )
            }

            assertEquals(
                "https://example.com/.well-known/webauthn",
                requestedUrl,
            )
        }
    }

    @Test
    fun `web rp validation rejects related public suffix origin`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://co.uk"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://co.uk",
            )
        }
    }
}
