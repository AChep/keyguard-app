package com.artemchep.keyguard.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `parses exact android app package`() {
        assertEquals(
            "com.example.app",
            parseAndroidAppPackageNameOrNull("  androidapp://com.example.app  "),
        )
    }

    @Test
    fun `rejects malformed android app uri`() {
        assertNull(parseAndroidAppPackageNameOrNull("androidapp://com.example.app/path"))
        assertNull(parseAndroidAppPackageNameOrNull("https://com.example.app"))
        assertNull(parseAndroidAppPackageNameOrNull("androidapp://com.example.app.evil?x=1"))
    }
}
