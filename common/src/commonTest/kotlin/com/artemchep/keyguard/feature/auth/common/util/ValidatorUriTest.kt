package com.artemchep.keyguard.feature.auth.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidatorUriTest {
    @Test
    fun `accepts common web uris`() {
        val uris = listOf(
            "https://google.com",
            "http://22.231.113.64/",
            "my-sub.exa-mple.co.uk:8080/x?y=z#f",
            "user:pw@example.com",
            "https://example.com/" + "a".repeat(20_000),
        )
        uris.forEach { uri ->
            assertEquals(ValidationUri.OK, validateUri(uri), uri)
        }
    }

    @Test
    fun `rejects malformed web uris`() {
        val uris = listOf(
            "https://example",
            "https://-example.com",
            "https://example-.com",
            "https://exa mple.com",
        )
        uris.forEach { uri ->
            assertEquals(ValidationUri.ERROR_INVALID, validateUri(uri), uri)
        }
    }

    @Test
    fun `reports blank input`() {
        assertEquals(ValidationUri.ERROR_EMPTY, validateUri(" "))
        assertEquals(ValidationUri.OK, validateUri(" ", allowBlank = true))
    }

    @Test
    fun `rejects pathological hosts without overflowing the stack`() {
        val uris = listOf(
            "https://" + "a".repeat(5_000) + "!",
            "https://" + "a-".repeat(20_000) + "a!",
            "https://" + "a-".repeat(20_000) + "a.com",
            "https://" + "a.".repeat(20_000) + "a!",
            "https://" + "a.".repeat(20_000) + "com",
        )
        uris.forEach { uri ->
            assertEquals(ValidationUri.ERROR_INVALID, validateUri(uri), uri.take(24))
        }
    }

    /**
     * The host labels used to be written as `(?:X-*)*X+`, which is
     * ambiguous. Check that the rewritten pattern matches the same
     * strings over an alphabet that exercises every label boundary.
     */
    @Test
    fun `host pattern matches the same short strings as the legacy pattern`() {
        val alphabet = listOf('a', '1', '-', '.')
        var strings = listOf("")
        var matched = 0
        repeat(6) {
            strings = strings.flatMap { prefix -> alphabet.map { prefix + it } }
            strings.forEach { host ->
                val uri = "https://$host"
                val expected = LEGACY_REGEX_WEB_URI.matches(uri)
                assertEquals(expected, REGEX_WEB_URI.matches(uri), uri)
                if (expected) matched += 1
            }
        }
        assertTrue(matched > 0)
    }

    private companion object {
        private val LEGACY_REGEX_WEB_URI = (
                "^(?:(?:[A-Za-z][+-.\\w^_]*:/{2})?(?:\\S+(?::\\S*)?@)?" +
                        "(?:(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
                        "(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)" +
                        "|(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)" +
                        "(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*" +
                        "(?:\\.(?:[a-z\\u00a1-\\uffff]{2,})))" +
                        "(?::\\d{2,5})?(?:/\\S*)?)$"
                ).toRegex()
    }
}
