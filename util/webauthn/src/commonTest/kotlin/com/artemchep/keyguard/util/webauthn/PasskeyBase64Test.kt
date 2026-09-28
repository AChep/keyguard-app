package com.artemchep.keyguard.util.webauthn

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PasskeyBase64Test {
    @Test
    fun `stored keys accept both alphabets and optional padding`() {
        val expected = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        for (encoded in listOf("+/8=", "+/8", "-_8=", "-_8")) {
            assertContentEquals(expected, PasskeyBase64.decodeStoredKeyOrNull(encoded))
        }
        assertNull(PasskeyBase64.decodeStoredKeyOrNull("%%%"))
    }

    @Test
    fun `storage decoding does not relax WebAuthn wire decoding`() {
        assertFailsWith<IllegalArgumentException> { PasskeyBase64.decode("+/8=") }
    }
}
