package com.artemchep.keyguard.util.webauthn

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebAuthnRpIdResolutionTest {
    @Test
    fun `rp id defaulting uses caller effective domain for https origin`() {
        val validator = createWebAuthnRpIdTestValidator()

        val rpId = validator.resolveRpId(
            rpId = null,
            origin = "https://login.example.com:1337",
        )

        assertEquals("login.example.com", rpId)
    }

    @Test
    fun `rp id defaulting keeps trailing dot from caller effective domain`() {
        val validator = createWebAuthnRpIdTestValidator()

        val rpId = validator.resolveRpId(
            rpId = null,
            origin = "https://login.example.com.:1337",
        )

        assertEquals("login.example.com.", rpId)
    }

    @Test
    fun `rp id defaulting uses localhost for http localhost origin`() {
        val validator = createWebAuthnRpIdTestValidator()

        val rpId = validator.resolveRpId(
            rpId = null,
            origin = "http://localhost:8080",
        )

        assertEquals("localhost", rpId)
    }

    @Test
    fun `rp id defaulting rejects https origins whose host is not a domain`() {
        val validator = createWebAuthnRpIdTestValidator()
        val invalidOrigins = listOf(
            "https://127.0.0.1",
            "https://[::1]",
        )

        invalidOrigins.forEach { origin ->
            assertFailsWith<RuntimeException> {
                validator.resolveRpId(
                    rpId = null,
                    origin = origin,
                )
            }
        }
    }

    @Test
    fun `rp id defaulting rejects http origins whose host is not exactly localhost`() {
        val validator = createWebAuthnRpIdTestValidator()
        val invalidOrigins = listOf(
            "http://example.com",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
            "http://sub.localhost:8080",
            "http://localhost.example.com",
        )

        invalidOrigins.forEach { origin ->
            assertFailsWith<RuntimeException> {
                validator.resolveRpId(
                    rpId = null,
                    origin = origin,
                )
            }
        }
    }

    @Test
    fun `rp id resolution canonicalizes explicit rp id case before validation`() = runTest {
        val validator = createWebAuthnRpIdTestValidator()

        val rpId = validator.resolveAndValidateRpId(
            rpId = "EXAMPLE.COM",
            origin = "https://login.example.com",
        )

        assertEquals("example.com", rpId)
    }

    @Test
    fun `rp id resolution rejects explicit rp id with surrounding whitespace`() = runTest {
        val validator = createWebAuthnRpIdTestValidator()

        assertFailsWith<RuntimeException> {
            validator.resolveAndValidateRpId(
                rpId = " example.com ",
                origin = "https://login.example.com",
            )
        }
    }

    @Test
    fun `rp id defaulting rejects android origin without effective domain`() {
        val validator = createWebAuthnRpIdTestValidator()

        assertFailsWith<IllegalArgumentException> {
            validator.resolveRpId(
                rpId = null,
                origin = "android:apk-key-hash:abc",
            )
        }
    }
}
