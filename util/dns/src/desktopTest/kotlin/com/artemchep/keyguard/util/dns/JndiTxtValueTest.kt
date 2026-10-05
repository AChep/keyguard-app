package com.artemchep.keyguard.util.dns

import kotlin.test.Test
import kotlin.test.assertEquals

class JndiTxtValueTest {
    @Test
    fun `keeps a plain value`() {
        assertEquals("https://vault.example.com", decodeJndiTxtValue("https://vault.example.com"))
    }

    @Test
    fun `unquotes a value that contains spaces`() {
        assertEquals("a b", decodeJndiTxtValue("\"a b\""))
    }

    @Test
    fun `unescapes quotes and backslashes`() {
        assertEquals("x\"y", decodeJndiTxtValue("\"x\\\"y\""))
        assertEquals("x\\y", decodeJndiTxtValue("\"x\\\\y\""))
    }

    @Test
    fun `decodes an empty string`() {
        assertEquals("", decodeJndiTxtValue("\"\""))
    }

    @Test
    fun `concatenates multiple character strings`() {
        assertEquals("https://vault.example.com", decodeJndiTxtValue("https://vault. example.com"))
        assertEquals("a bc", decodeJndiTxtValue("\"a b\" c"))
    }

    @Test
    fun `decodes utf8 bytes that the provider mapped to latin1 chars`() {
        val encoded = "bücher".toByteArray(Charsets.UTF_8)
            .toString(Charsets.ISO_8859_1)

        assertEquals("bücher", decodeJndiTxtValue(encoded))
    }
}
