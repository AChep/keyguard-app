package com.artemchep.keyguard.util.dns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DnsNameTest {
    @Test
    fun `accepts underscore labels and normalizes case and the root dot`() {
        assertEquals("_bitwarden.example.com", requireValidDnsName("_bitwarden.example.com"))
        assertEquals("_vaultwarden.example.com", requireValidDnsName("_Vaultwarden.Example.COM."))
        assertEquals("a-b.example", requireValidDnsName("a-b.example"))
        assertEquals("a".repeat(63) + ".com", requireValidDnsName("a".repeat(63) + ".com"))
    }

    @Test
    fun `rejects empty names and empty labels`() {
        assertFailsWith<IllegalArgumentException> { requireValidDnsName("") }
        assertFailsWith<IllegalArgumentException> { requireValidDnsName(".") }
        assertFailsWith<IllegalArgumentException> { requireValidDnsName("a..b") }
        assertFailsWith<IllegalArgumentException> { requireValidDnsName(".example.com") }
    }

    @Test
    fun `rejects labels that are too long or names that are too long`() {
        assertFailsWith<IllegalArgumentException> { requireValidDnsName("a".repeat(64) + ".com") }
        val longName = List(4) { "a".repeat(63) }.joinToString(".")
        assertFailsWith<IllegalArgumentException> { requireValidDnsName(longName) }
    }

    @Test
    fun `rejects characters outside the ascii label alphabet`() {
        assertFailsWith<IllegalArgumentException> { requireValidDnsName("bücher.example") }
        assertFailsWith<IllegalArgumentException> { requireValidDnsName("exa mple.com") }
        assertFailsWith<IllegalArgumentException> { requireValidDnsName("example.com/path") }
        assertFailsWith<IllegalArgumentException> { requireValidDnsName("user@example.com") }
    }
}
