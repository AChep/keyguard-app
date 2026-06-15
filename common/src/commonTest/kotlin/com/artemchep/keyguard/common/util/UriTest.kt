package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals

class UriTest {
    @Test
    fun `keeps blank input unchanged`() {
        assertEquals("", ensureUrlScheme(""))
        assertEquals(" ", ensureUrlScheme(" "))
    }

    @Test
    fun `adds https scheme to schemeless url`() {
        assertEquals("https://example.com", ensureUrlScheme("example.com"))
    }

    @Test
    fun `keeps http and https urls unchanged`() {
        assertEquals("http://example.com", ensureUrlScheme("http://example.com"))
        assertEquals("https://example.com", ensureUrlScheme("https://example.com"))
    }

    @Test
    fun `keeps existing scheme separator behavior for non hierarchical schemes`() {
        assertEquals("https://mailto:test@example.com", ensureUrlScheme("mailto:test@example.com"))
    }
}
