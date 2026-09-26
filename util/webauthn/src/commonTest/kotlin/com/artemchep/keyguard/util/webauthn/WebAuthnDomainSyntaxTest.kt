package com.artemchep.keyguard.util.webauthn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAuthnDomainSyntaxTest {
    @Test
    fun `rp id accepts a 63 character label and rejects a 64 character label`() {
        val longestLabel = "a".repeat(63)
        for (rootDot in listOf("", ".")) {
            assertTrue(isValidCanonicalWebAuthnRpId("$longestLabel.example$rootDot"))
            assertFalse(isValidCanonicalWebAuthnRpId("${longestLabel}a.example$rootDot"))
        }
    }

    @Test
    fun `rp id length limit excludes one trailing root dot`() {
        val longestDomain = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(61))
            .joinToString(".")
        val oversizedDomain = "${longestDomain}d"
        assertEquals(253, longestDomain.length)
        assertEquals(254, oversizedDomain.length)

        for (rootDot in listOf("", ".")) {
            assertTrue(isValidCanonicalWebAuthnRpId("$longestDomain$rootDot"))
            assertFalse(isValidCanonicalWebAuthnRpId("$oversizedDomain$rootDot"))
        }
    }

    @Test
    fun `rp id rejects empty labels and characters outside LDH syntax`() {
        val invalidDomains = listOf(
            "-a.example",
            "a-.example",
            "a_b.example",
            "a b.example",
            ".example",
            "a..example",
            "example.com..",
            "a/b.example",
            "a@b.example",
            "a:80.example",
        )

        for (domain in invalidDomains) {
            assertFalse(isValidCanonicalWebAuthnRpId(domain), domain)
        }
    }

    @Test
    fun `rp id permits leading digits and interior hyphens`() {
        for (domain in listOf("1.example", "a-b.example", "1a-b2.example")) {
            assertTrue(isValidCanonicalWebAuthnRpId(domain), domain)
        }
    }
}
