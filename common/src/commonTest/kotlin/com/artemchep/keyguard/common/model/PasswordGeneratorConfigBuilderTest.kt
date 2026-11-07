package com.artemchep.keyguard.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PasswordGeneratorConfigBuilderTest {
    private val payload = PasswordGeneratorConfigBuilder2.EmailPayload.Value("abc")

    private fun catchAll(domain: String) = PasswordGeneratorConfigBuilder2.EmailCatchAll(
        payload = payload,
        domain = domain,
    )

    private fun plusAddressing(email: String) = PasswordGeneratorConfigBuilder2.EmailPlusAddressing(
        payload = payload,
        email = email,
    )

    private fun subdomainAddressing(email: String) = PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing(
        payload = payload,
        email = email,
    )

    private fun gpgKey(
        name: String,
        email: String,
    ) = PasswordGeneratorConfigBuilder2.GpgKey(
        config = GpgKeyConfig.Modern(userId = "unused"),
        name = name,
        email = email,
    )

    private fun PasswordGeneratorConfigBuilder2.output(): String? {
        val config = assertIs<PasswordGeneratorConfig.Composite>(build())
        return config.transform("abc")
    }

    @Test
    fun `catch-all domain must be a valid custom domain`() {
        assertEquals(GeneratorInputError.EMPTY, catchAll("").validateDomain())
        assertEquals(GeneratorInputError.EMPTY, catchAll("  ").validateDomain())
        assertEquals(GeneratorInputError.PUBLIC_DOMAIN, catchAll("gmail.com").validateDomain())
        assertEquals(GeneratorInputError.PUBLIC_DOMAIN, catchAll(" Gmail.com ").validateDomain())
        assertEquals(GeneratorInputError.INVALID_DOMAIN, catchAll("example").validateDomain())
        assertNull(catchAll(" example.com ").validateDomain())
    }

    @Test
    fun `catch-all does not generate while the domain has an error`() {
        assertNull(catchAll("gmail.com").output())
        assertNull(catchAll("example").output())
        assertEquals("abc@example.com", catchAll(" example.com ").output())
    }

    @Test
    fun `plus addressing email must be valid`() {
        assertEquals(GeneratorInputError.EMPTY, plusAddressing("").validateEmail())
        assertEquals(GeneratorInputError.INVALID_EMAIL, plusAddressing("user").validateEmail())
        assertNull(plusAddressing(" user@gmail.com ").validateEmail())
    }

    @Test
    fun `plus addressing does not generate while the email has an error`() {
        assertNull(plusAddressing("user").output())
        assertEquals("user+abc@gmail.com", plusAddressing(" user@gmail.com ").output())
    }

    @Test
    fun `subdomain addressing email must be on a valid custom domain`() {
        assertEquals(GeneratorInputError.EMPTY, subdomainAddressing("").validateEmail())
        assertEquals(GeneratorInputError.PUBLIC_DOMAIN, subdomainAddressing("user@gmail.com").validateEmail())
        assertEquals(GeneratorInputError.PUBLIC_DOMAIN, subdomainAddressing("user@Gmail.com").validateEmail())
        assertEquals(GeneratorInputError.INVALID_EMAIL, subdomainAddressing("user").validateEmail())
        assertNull(subdomainAddressing(" user@example.com ").validateEmail())
    }

    @Test
    fun `subdomain addressing does not generate while the email has an error`() {
        assertNull(subdomainAddressing("user@gmail.com").output())
        assertNull(subdomainAddressing("user").output())
        assertEquals("user@abc.example.com", subdomainAddressing(" user@example.com ").output())
    }

    @Test
    fun `gpg key name is required and must not contain reserved characters`() {
        assertEquals(GeneratorInputError.EMPTY, gpgKey(name = "", email = "").validateName())
        assertEquals(GeneratorInputError.EMPTY, gpgKey(name = "  ", email = "").validateName())
        assertEquals(GeneratorInputError.RESERVED_CHARS, gpgKey(name = "Bob <x", email = "").validateName())
        assertEquals(GeneratorInputError.RESERVED_CHARS, gpgKey(name = "Bob (work)", email = "").validateName())
        assertNull(gpgKey(name = " Bob ", email = "").validateName())
    }

    @Test
    fun `gpg key email is optional but must be valid`() {
        assertNull(gpgKey(name = "Bob", email = "").validateEmail())
        assertNull(gpgKey(name = "Bob", email = " bob@example.com ").validateEmail())
        assertEquals(GeneratorInputError.INVALID_EMAIL, gpgKey(name = "Bob", email = "bob").validateEmail())
    }

    @Test
    fun `is valid only when every input is valid`() {
        assertTrue(gpgKey(name = "Bob", email = "").isValid())
        assertTrue(gpgKey(name = "Bob", email = "bob@example.com").isValid())
        assertFalse(gpgKey(name = "", email = "bob@example.com").isValid())
        assertFalse(gpgKey(name = "Bob", email = "bob").isValid())
        assertFalse(catchAll("gmail.com").isValid())
        assertFalse(plusAddressing("").isValid())
        assertFalse(subdomainAddressing("user@gmail.com").isValid())
        assertTrue(catchAll("example.com").isValid())
        assertTrue(PasswordGeneratorConfigBuilder2.PinCode(length = 4).isValid())
    }
}
