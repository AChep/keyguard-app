package com.artemchep.keyguard.util.webauthn

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAuthnRelatedOriginDocumentTest {
    // Spec coverage: Section 5.11 requires HTTPS well-known JSON with
    // application/json, status 200, an origins string array, at least five
    // origin labels, and HTTPS-only redirects. Section 5.11.1 defines the
    // per-origin validation loop.
    @Test
    fun `https rp validation allows related origin from webauthn well known document`() = runTest {
        var requestedUrl: String? = null
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://example.co.uk"]}""",
        ) { url ->
            requestedUrl = url
        }

        validator.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
        )

        assertEquals(
            "https://example.com/.well-known/webauthn",
            requestedUrl,
        )
    }

    @Test
    fun `https rp validation rejects related origin document with non ok status`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://example.co.uk"]}""",
            status = HttpStatusCode.Created,
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects unlisted related origin`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://another.example"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation keeps trailing dot significant for related origins`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://example.co.uk."]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document with invalid content type`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://example.co.uk"]}""",
            contentType = ContentType.Text.Plain.toString(),
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects lenient related origin json`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{origins:["https://example.co.uk"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation skips invalid related origin document entries`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://example.co.uk/login","https://example.co.uk"]}""",
        )

        validator.requireRpMatchesOrigin(
            rpId = "example.com",
            origin = "https://example.co.uk",
        )
    }

    @Test
    fun `https rp validation rejects related origin document with only invalid entries`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://example.co.uk/login"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin with fragment`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://example.co.uk#fragment"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin with user info`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":["https://user@example.co.uk"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation enforces related origin label cap`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """
                {
                  "origins": [
                    "https://one.example",
                    "https://two.example",
                    "https://three.example",
                    "https://four.example",
                    "https://five.example",
                    "https://target.example"
                  ]
                }
            """.trimIndent(),
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://target.example",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document without origins property`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"notOrigins":["https://example.co.uk"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document with empty origins array`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":[]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }

    @Test
    fun `https rp validation rejects related origin document with non-string entries`() = runTest {
        val validator = createWebAuthnRpIdTestValidator(
            responseBody = """{"origins":[42,"https://example.co.uk"]}""",
        )

        assertFailsWith<RuntimeException> {
            validator.requireRpMatchesOrigin(
                rpId = "example.com",
                origin = "https://example.co.uk",
            )
        }
    }
}
