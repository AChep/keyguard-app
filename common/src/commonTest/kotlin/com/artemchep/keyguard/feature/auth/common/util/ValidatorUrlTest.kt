package com.artemchep.keyguard.feature.auth.common.util

import com.artemchep.keyguard.common.util.normalizeServerUrl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUnsecureUrlCheckImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidatorUrlTest {
    @Test
    fun `accepts server urls`() {
        val urls = listOf(
            "https://myserver",
            "https://myserver:8443",
            "http://localhost:8080",
            "https://[fd00::5]",
            "https://[::1]",
            "https://[::ffff:10.0.0.1]",
            "https://vault.xn--p1ai",
            "https://пример.рф",
            "HTTPS://vault.example.com",
            "https://vault.example.com.",
            "https://my_server.lan",
            "https://10.0.0.5",
            "https://vault.example.com/bitwarden/",
        )
        urls.forEach { url ->
            assertEquals(ValidationUrl.OK, validateUrl(url), url)
        }
    }

    @Test
    fun `rejects malformed server urls`() {
        val urls = listOf(
            "vault.example.com",
            "rtsp://vault.example.com",
            "https://vault.example.com:99999",
            "https:///api/",
            "https://exa mple.com",
            "https://",
            "https://vault..example.com",
            "https://.vault.example.com",
            "https://" + "a".repeat(64) + ".com",
            "https://[fd00::5",
            "https://[zz::1]",
            "https://[1::2::3]",
            "https://[fe80::1%25en0]",
        )
        urls.forEach { url ->
            assertEquals(ValidationUrl.ERROR_INVALID, validateUrl(url), url)
        }
    }

    @Test
    fun `rejects a query or a fragment in a base url`() {
        val urls = listOf(
            "https://vault.example.com?region=eu",
            "https://vault.example.com/?",
            "https://vault.example.com#sign-in",
        )
        urls.forEach { url ->
            assertEquals(ValidationUrl.ERROR_INVALID, validateUrl(url), url)
        }
    }

    @Test
    fun `server url check allows a query`() {
        assertTrue(isServerUrl("https://ice.example.com/?key=1", setOf("https")))
    }

    @Test
    fun `reports blank input`() {
        assertEquals(ValidationUrl.ERROR_EMPTY, validateUrl(null))
        assertEquals(ValidationUrl.ERROR_EMPTY, validateUrl(" "))
        assertEquals(ValidationUrl.OK, validateUrl(null, allowBlank = true))
        assertEquals(ValidationUrl.OK, validateUrl(" ", allowBlank = true))
    }

    @Test
    fun `accepts only the given schemes`() {
        val url = "wss://relay.example.com"
        assertEquals(ValidationUrl.ERROR_INVALID, validateUrl(url))
        assertEquals(ValidationUrl.OK, validateUrl(url, schemes = setOf("wss", "https")))
        assertEquals(
            ValidationUrl.ERROR_INVALID,
            validateUrl("https://relay.example.com", schemes = setOf("wss")),
        )
    }

    @Test
    fun `normalizes the scheme of a server url`() {
        assertEquals("https://myserver", normalizeServerUrl("myserver"))
        assertEquals("http://Vault.example.com", normalizeServerUrl("HTTP://Vault.example.com"))
        assertEquals("https://https.example.com", normalizeServerUrl("https.example.com"))
        assertEquals("https://httpbin.org", normalizeServerUrl("httpbin.org"))
        assertEquals("", normalizeServerUrl(""))
    }

    @Test
    fun `keeps a mistyped scheme of a server url`() {
        val urls = listOf(
            "http:/vault.example.com",
            "https//vault.example.com",
            "HTTPS:/vault.example.com",
        )
        urls.forEach { url ->
            assertEquals(url, normalizeServerUrl(url))
        }
        assertEquals(
            ValidationUrl.ERROR_INVALID,
            validateUrl(normalizeServerUrl("http:/vault.example.com")),
        )
    }

    @Test
    fun `accepts a host once the scheme defaults to https`() {
        assertEquals(ValidationUrl.OK, validateUrl(normalizeServerUrl("myserver")))
    }

    @Test
    fun `flags an uppercase http scheme as unsecure`() {
        val url = normalizeServerUrl("HTTP://vault.example.com")
        assertTrue(CipherUnsecureUrlCheckImpl()(url))
    }
}
