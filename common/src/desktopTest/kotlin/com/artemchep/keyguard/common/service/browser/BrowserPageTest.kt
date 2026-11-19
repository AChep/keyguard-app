package com.artemchep.keyguard.common.service.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class BrowserPageTest {
    @Test
    fun `only absolute http and https URLs with a host are pages`() {
        for (url in listOf("example.com", "/sign-in", "file:///etc/passwd", "chrome://settings", "about:blank", "")) {
            assertNull(BrowserPage.parse(url), url)
        }
        val page = checkNotNull(BrowserPage.parse("https://user:pass@Login.Example.com:8443/sign-in?next=/#top"))
        assertEquals("https", page.scheme)
        assertEquals("login.example.com", page.host)
        // Without the credentials, the path, the query and the fragment.
        assertEquals("https://login.example.com:8443", page.origin.lowercase())
    }

    @Test
    fun `a URL without a host after the scheme is not a page of localhost`() {
        for (url in listOf(
            "https:/sign-in",
            "https:sign-in",
            "https:///sign-in",
            "https://",
            "https://:8443/sign-in",
            "https://user@/sign-in",
            "https://?next=/",
            "https://#top",
        )) {
            assertNull(BrowserPage.parse(url), url)
        }
        // A host that is spelled out still is one.
        assertEquals("localhost", BrowserPage.parse("http://localhost:8080/")?.host)
        assertEquals("example.com", BrowserPage.parse("https://user:pass@example.com/")?.host)
        assertEquals("http://[::1]:8080", BrowserPage.parse("http://[::1]:8080/sign-in")?.origin)
    }

    @Test
    fun `an origin is a scheme, a host and an optional port, as browsers write it`() {
        for (origin in listOf(
            "https://example.com",
            "http://localhost:8080",
            "https://xn--bcher-kva.example",
            "http://192.168.1.10",
            "http://[::1]:8080",
        )) {
            assertEquals(origin, BrowserPage.parseOrigin(origin)?.origin, origin)
        }
        for (origin in listOf(
            "",
            "null",
            "example.com",
            "https://example.com/",
            "https://example.com/sign-in",
            "https://example.com?next=1",
            "https://example.com#top",
            "https://user@example.com",
            "file:///etc/passwd",
            "chrome-extension://abcdefghijklmnopabcdefghijklmnop",
        )) {
            assertNull(BrowserPage.parseOrigin(origin), origin)
        }
    }

    @Test
    fun `an empty query leaves no question mark in the origin`() {
        assertEquals("https://example.com", BrowserPage.parse("https://example.com/sign-in?")?.origin)
        assertEquals("https://example.com:8443", BrowserPage.parse("https://example.com:8443?")?.origin)
        // Nor is the URL an origin.
        assertNull(BrowserPage.parseOrigin("https://example.com?"))
    }

    @Test
    fun `the matching sees the host in lowercase, without a trailing dot`() {
        val page = checkNotNull(BrowserPage.parse("https://Bank.COM./sign-in?next=1"))
        assertEquals("bank.com", page.host)
        assertEquals("https://bank.com/sign-in?next=1", page.link.url.toString())
        assertEquals("https://bank.com", page.link.frontPageUrl.toString())
        // A host of dots only is no host.
        assertNull(BrowserPage.parse("https://./"))
    }

    @Test
    fun `the site is the scheme and the registrable domain`() = runTest {
        suspend fun site(url: String) = checkNotNull(BrowserPage.parse(url)).site(LastTwoLabelsTldService)

        assertEquals("https://example.com", site("https://login.example.com/"))
        assertEquals("https://example.com", site("https://example.com:8443/"))
        assertEquals("http://example.com", site("http://www.example.com/"))
        // An address is a site of its own.
        assertEquals("http://192.168.1.10", site("http://192.168.1.10/admin"))
        assertEquals("http://10.0.1.10", site("http://10.0.1.10/"))
        assertEquals("http://localhost", site("http://localhost:8080/"))
    }
}
