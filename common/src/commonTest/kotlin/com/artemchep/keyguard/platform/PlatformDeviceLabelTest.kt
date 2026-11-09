package com.artemchep.keyguard.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PlatformDeviceLabelTest {
    @Test
    fun `a usable system name wins without looking up the model`() = runTest {
        val label = firstDeviceLabel(
            { "  Artem's phone  " },
            { error("The model must not be looked up") },
        )
        assertEquals("Artem's phone", label)
    }

    @Test
    fun `missing denied and invalid names fall through to the model`() = runTest {
        val label = firstDeviceLabel(
            { null },
            { throw IllegalStateException("Settings unavailable") },
            { " \n " },
            { "localhost" },
            { "unsafe\u202ename" },
            { "Google Pixel 9" },
        )
        assertEquals("Google Pixel 9", label)
        assertNull(firstDeviceLabel({ "unknown" }, { null }))
    }

    @Test
    fun `cancellation and fatal failures propagate`() = runTest {
        assertFailsWith<CancellationException> {
            firstDeviceLabel({ throw CancellationException() }, { "Fallback" })
        }
        assertFailsWith<AssertionError> {
            firstDeviceLabel({ throw AssertionError() }, { "Fallback" })
        }
    }

    @Test
    fun `names use the protocol code point limit without splitting emoji`() {
        val emoji = "\uD83D\uDCBB"
        assertEquals(emoji.repeat(64), emoji.repeat(65).normalizedDeviceLabel())
        assertEquals("a".repeat(63) + emoji, ("a".repeat(63) + emoji + "b").normalizedDeviceLabel())
        assertEquals("a".repeat(64), "a".repeat(65).normalizedDeviceLabel())
    }

    @Test
    fun `names reject controls direction overrides and lone surrogates`() {
        listOf("work\nlaptop", "work\u0000laptop", "work\u2028laptop", "work\u202elaptop", "work\uD83D").forEach {
            assertNull(it.normalizedDeviceLabel())
        }
        assertEquals("Ноутбук 👩‍💻", " Ноутбук 👩‍💻 ".normalizedDeviceLabel())
    }

    @Test
    fun `placeholder names are unavailable`() {
        listOf("", "   ", "UNKNOWN", "localhost", "LOCALHOST.LOCALDOMAIN", "(none)").forEach {
            assertNull(it.normalizedDeviceLabel())
        }
    }

    @Test
    fun `manufacturer is included once when a model is available`() {
        assertEquals("Google Pixel 9", manufacturerModelDeviceLabel("Google", "Pixel 9"))
        assertEquals("Samsung Galaxy S24", manufacturerModelDeviceLabel("samsung", "Samsung Galaxy S24"))
        assertEquals("Pixel 9", manufacturerModelDeviceLabel("unknown", "Pixel 9"))
        assertNull(manufacturerModelDeviceLabel("Google", "unknown"))
    }
}
