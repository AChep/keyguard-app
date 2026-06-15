package com.artemchep.keyguard.provider.bitwarden.usecase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CipherUnsecureUrlCheckImplTest {
    private val unsecureUrlCheck = CipherUnsecureUrlCheckImpl()

    @Test
    fun `public http url is insecure`() {
        assertTrue(unsecureUrlCheck("http://example.com/path"))
    }

    @Test
    fun `public websocket url is insecure`() {
        assertTrue(unsecureUrlCheck("ws://example.com/socket"))
    }

    @Test
    fun `secure protocols are not insecure`() {
        assertFalse(unsecureUrlCheck("https://example.com/path"))
        assertFalse(unsecureUrlCheck("wss://example.com/socket"))
    }

    @Test
    fun `local http urls are not insecure`() {
        assertFalse(unsecureUrlCheck("http://localhost/path"))
        assertFalse(unsecureUrlCheck("http://192.168.1.10/path"))
    }

    @Test
    fun `unsupported protocols are not insecure`() {
        assertFalse(unsecureUrlCheck("ftp://example.com/file"))
    }
}
