package com.artemchep.keyguard.common.util

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpUrlHostTest {
    @Test
    fun `matches ktor for ordinary http urls`() {
        val urls = listOf(
            "http://example.com",
            "https://example.com/path/to/login",
            "https://subdomain.example.com?query=value",
            "https://127.0.0.1#fragment",
            "https://localhost/path",
            "https://www.example.com/path",
        )

        urls.forEach { url ->
            assertEquals(
                expected = Url(url).host,
                actual = parseHttpUrlHostOrNull(url),
                message = url,
            )
        }
    }

    @Test
    fun `falls back to ktor for complex authorities`() {
        val urls = listOf(
            "HTTPS://EXAMPLE.COM/path",
            "https://user:password@example.com/path",
            "https://example.com:8443/path",
            "https://[2001:db8::1]/path",
            "https://münchen.example/path",
        )

        urls.forEach { url ->
            assertEquals(
                expected = Url(url).host,
                actual = parseHttpUrlHostOrNull(url),
                message = url,
            )
        }
    }

    @Test
    fun `rejects unsupported urls and preserves ktor defaults`() {
        assertEquals(null, parseHttpUrlHostOrNull("androidapp://example.com"))
        assertEquals(null, parseHttpUrlHostOrNull("example.com"))
        assertEquals(Url("https://").host, parseHttpUrlHostOrNull("https://"))
    }

    @Test
    fun `preserves www prefix`() {
        assertEquals(
            "www.example.com",
            parseHttpUrlHostOrNull("https://www.example.com/path"),
        )
    }

    @Test
    fun `www prefix can be removed`() {
        assertEquals(
            "example.com",
            parseHttpUrlHostOrNull(
                url = "https://www.example.com/path",
                removeWww = true,
            ),
        )
    }
}
