package com.artemchep.keyguard.common.service.placeholder.impl

import com.artemchep.keyguard.common.io.bind
import kotlinx.coroutines.test.runTest
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TextTransformPlaceholderTest {
    private val placeholder = TextTransformPlaceholder()

    @Test
    fun `base64 transform uses URL safe alphabet without padding`() = runTest {
        assertEquals("SGVsbG8_", transform("Hello?", "base64"))
        assertEquals("Zg", transform("f", "base64"))
    }

    @Test
    fun `uri decode handles form spaces and utf8 percent escapes`() = runTest {
        assertEquals("hello world", transform("hello+world", "uri-dec"))
        assertEquals("caf\u00E9", transform("caf%C3%A9", "uri-dec"))
    }

    @Test
    fun `uri decode keeps raw unicode characters intact`() = runTest {
        assertEquals("caf\u00E9", transform("caf\u00E9", "uri-dec"))
    }

    @Test
    fun `uri encode keeps existing form encoding behavior`() = runTest {
        assertEquals("a+b*%7E", transform("a b*~", "uri"))
    }

    @Test
    fun `uri encode percent escapes utf8 bytes`() = runTest {
        assertEquals("caf%C3%A9", transform("caf\u00E9", "uri"))
    }

    @Test
    fun `uri encode matches java net url encoder utf8`() = runTest {
        urlEncodingSamples.forEach { value ->
            assertEquals(
                URLEncoder.encode(value, StandardCharsets.UTF_8),
                transform(value, "uri"),
                "Unexpected URI encoding for '$value'.",
            )
        }
    }

    @Test
    fun `uri decode matches java net url decoder utf8`() = runTest {
        urlDecodingSamples.forEach { value ->
            assertEquals(
                URLDecoder.decode(value, StandardCharsets.UTF_8),
                transform(value, "uri-dec"),
                "Unexpected URI decoding for '$value'.",
            )
        }
    }

    @Test
    fun `uri decode reports malformed percent escapes as illegal arguments`() {
        assertFailsWith<IllegalArgumentException> {
            placeholder.get(key("%", "uri-dec"))
        }
        assertFailsWith<IllegalArgumentException> {
            placeholder.get(key("%GG", "uri-dec"))
        }
    }

    @Test
    fun `unknown transform returns null value`() = runTest {
        assertNull(transform("value", "unknown"))
    }

    private suspend fun transform(
        value: String,
        command: String,
    ): String? {
        return requireNotNull(placeholder.get(key(value, command))).bind()
    }

    private fun key(
        value: String,
        command: String,
    ): String {
        val separator = listOf(':', '|', '#', '$', '^', '~', '\u001F')
            .first { it !in value && it !in command }
        return "t-conv:$separator$value$separator$command$separator"
    }

    private companion object {
        val urlEncodingSamples = listOf(
            "",
            "abcXYZ012",
            "a b",
            "a+b",
            "-_.*~",
            "!@#\$&'()=/?;,[]",
            "100%",
            "caf\u00E9",
            "emoji \uD83D\uDE00",
            "\u0000\n\t",
        )

        val urlDecodingSamples = listOf(
            "",
            "abcXYZ012",
            "hello+world",
            "a%2Bb",
            "-_.*%7E",
            "%21%40%23%24%26%27%28%29%3D%2F%3F%3B%2C%5B%5D",
            "100%25",
            "caf%C3%A9",
            "emoji+%F0%9F%98%80",
            "%00%0A%09",
            "raw caf\u00E9",
        )
    }
}
