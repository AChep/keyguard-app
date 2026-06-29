package app.keemobile.kotpass.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Base64CompatibilityTest {
    @Test
    fun encodeBase64UsesStandardPaddedAlphabet() {
        assertEquals("/wD+AA==", byteArrayOf(-1, 0, -2, 0).encodeBase64())
    }

    @Test
    fun encodeBase64UrlSafeUsesUrlAlphabet() {
        assertEquals("_wD-AA==", byteArrayOf(-1, 0, -2, 0).encodeBase64UrlSafe())
    }

    @Test
    fun decodeAcceptsStandardUrlSafeUnpaddedAndWhitespace() {
        val expected = byteArrayOf(-1, 0, -2, 0)

        assertContentEquals(expected, "/wD+AA==".decodeBase64ToArray())
        assertContentEquals(expected, "_wD-AA==".decodeBase64ToArray())
        assertContentEquals(expected, "_wD-AA".decodeBase64ToArray())
        assertContentEquals(expected, "\n _wD-\tAA== \r".decodeBase64ToArray())
    }

    @Test
    fun decodeRejectsTruncatedSingleSymbolTail() {
        assertFailsWith<IllegalArgumentException> {
            "A".decodeBase64ToArray()
        }
    }
}
