package com.artemchep.keyguard.util.webauthn

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebAuthnOriginParsingTest {
    @Test
    fun `web origin rejects user info`() {
        val invalidOrigins = listOf(
            "https://alice@example.com",
            "https://alice:secret@example.com",
            "https://:secret@example.com",
        )

        for (origin in invalidOrigins) {
            assertNull(parseWebAuthnOrigin(Url(origin)), origin)
        }
    }

    @Test
    fun `web origin rejects paths queries and fragments`() {
        val invalidOrigins = listOf(
            "https://example.com/login",
            "https://example.com//",
            "https://example.com?action=login",
            "https://example.com?",
            "https://example.com/#fragment",
        )

        for (origin in invalidOrigins) {
            assertNull(parseWebAuthnOrigin(Url(origin)), origin)
        }
    }

    @Test
    fun `web origin serialization normalizes case root paths and default ports`() {
        val origins = mapOf(
            "https://EXAMPLE.COM" to "https://example.com",
            "https://example.com/" to "https://example.com",
            "https://example.com:443/" to "https://example.com",
            "https://example.com:8443/" to "https://example.com:8443",
            "https://example.com.:443/" to "https://example.com.",
            "http://LOCALHOST/" to "http://localhost",
            "http://localhost:80/" to "http://localhost",
            "http://localhost:8080/" to "http://localhost:8080",
        )

        for ((origin, expected) in origins) {
            val parsed = assertNotNull(parseWebAuthnOrigin(Url(origin)), origin)
            assertEquals(expected, parsed.serialized, origin)
        }
    }
}
