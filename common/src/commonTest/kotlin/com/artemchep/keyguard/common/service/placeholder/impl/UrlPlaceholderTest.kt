package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.bind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlPlaceholderTest {
    @Test
    fun `parameter lookup ignores the case of the placeholder prefix`() = runTest {
        val placeholder = UrlPlaceholder("https://example.test/?token=abc")
        assertEquals("abc", placeholder.resolve("url:parameter:token"))
        assertEquals("abc", placeholder.resolve("URL:PARAMETER:token"))
        assertEquals("abc", placeholder.resolve("base:parameter:token"))
        assertEquals("abc", placeholder.resolve("BASE:PARAMETER:token"))
    }

    @Test
    fun `parameter lookup keeps the case of the parameter name`() = runTest {
        val placeholder = UrlPlaceholder("https://example.test/?Token=abc")
        assertEquals("abc", placeholder.resolve("URL:PARAMETER:Token"))
    }

    @Test
    fun `scheme removal cuts only the leading scheme`() = runTest {
        val placeholder = UrlPlaceholder("https://example.test/?next=https://other.test/")
        assertEquals(
            "example.test/?next=https://other.test/",
            placeholder.resolve("url:rmvscm"),
        )
        assertEquals(
            "example.test/?next=https://other.test/",
            placeholder.resolve("BASE:RMVSCM"),
        )
    }

    @Test
    fun `scheme removal matches documented example`() = runTest {
        val placeholder = UrlPlaceholder("https://user:pw@keepass.info:80/path/example.php?q=e&s=t")
        assertEquals(
            "user:pw@keepass.info:80/path/example.php?q=e&s=t",
            placeholder.resolve("url:rmvscm"),
        )
    }

    private suspend fun UrlPlaceholder.resolve(key: String): String? =
        requireNotNull(get(key = key)).bind()
}
