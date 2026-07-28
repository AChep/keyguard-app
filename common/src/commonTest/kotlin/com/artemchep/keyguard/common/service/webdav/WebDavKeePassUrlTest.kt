package com.artemchep.keyguard.common.service.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebDavKeePassUrlTest {
    @Test
    fun `parses base url and decoded file path`() {
        val result = parseWebDavKeePassFileUrl(
            "  https://example.com/dav/folder/vault%20file.kdbx?download=1#section  ",
        )

        assertEquals("https://example.com/dav/folder/", result.baseUrl)
        assertEquals("vault file.kdbx", result.path)
    }

    @Test
    fun `accepts uppercase keepass extension`() {
        val result = parseWebDavKeePassFileUrl(
            "https://example.com/dav/VAULT.KDBX",
        )

        assertEquals("VAULT.KDBX", result.path)
    }

    @Test
    fun `identifies valid keepass file url`() {
        assertTrue(
            "https://example.com/dav/vault.kdbx".isWebDavKeePassFileUrl(),
        )
    }

    @Test
    fun `rejects collection url`() {
        assertFalse(
            "https://example.com/dav/".isWebDavKeePassFileUrl(),
        )
        assertNull(
            parseWebDavKeePassFileUrlOrNull("https://example.com/dav/"),
        )
    }

    @Test
    fun `rejects url without http scheme`() {
        assertFalse(
            "file:///dav/vault.kdbx".isWebDavKeePassFileUrl(),
        )
        assertNull(
            parseWebDavKeePassFileUrlOrNull("file:///dav/vault.kdbx"),
        )
    }

    @Test
    fun `rejects url without database extension`() {
        assertFalse(
            "https://example.com/dav/vault".isWebDavKeePassFileUrl(),
        )
        assertNull(
            parseWebDavKeePassFileUrlOrNull("https://example.com/dav/vault"),
        )
    }
}
