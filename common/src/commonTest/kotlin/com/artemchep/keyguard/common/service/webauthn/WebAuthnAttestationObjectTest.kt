package com.artemchep.keyguard.common.service.webauthn

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the byte contract of the shared WebAuthn encoders that Android, iOS, and macOS all
 * depend on. The expected bytes are spelled out independently (not re-derived from the
 * functions under test), so a regression in the COSE prefix, the attestation-object CBOR
 * map, or the byte-string length header fails here.
 */
class WebAuthnAttestationObjectTest {
    @Test
    fun `COSE_Key for ES256 has the canonical RFC 9052 layout`() {
        val x = ByteArray(32) { it.toByte() } // 00 01 .. 1f
        val y = ByteArray(32) { (it + 32).toByte() } // 20 21 .. 3f

        // a5            map(5)
        //   01 02       kty: EC2 (2)
        //   03 26       alg: ES256 (-7)
        //   20 01       crv: P-256 (1)
        //   21 58 20 X  x-coordinate, byte string(32)
        //   22 58 20 Y  y-coordinate, byte string(32)
        val expected = "a5010203262001215820" +
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" +
            "225820" +
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"

        assertEquals(expected, coseKeyEs256(x, y).toHex())
    }

    @Test
    fun `none attestation object wraps authData in the fmt-none CBOR map`() {
        val authData = ByteArray(148) { 0x11.toByte() } // realistic registration authData size

        // a3                                   map(3)
        //   63 "fmt"      64 "none"
        //   67 "attStmt"  a0 {}
        //   68 "authData" 58 94 <148 bytes>     (byte string, length 0x94 = 148)
        val expected = "a363666d74646e6f6e656761747453746d74a0686175746844617461" +
            "5894" +
            "11".repeat(148)

        assertEquals(expected, webAuthnNoneAttestationObject(authData).toHex())
    }

    @Test
    fun `none attestation object uses a single-byte CBOR header for short authData`() {
        val authData = ByteArray(4) { 0xab.toByte() }

        // …"authData" then 44 (= 0x40 | 4) <4 bytes>
        val expected = "a363666d74646e6f6e656761747453746d74a0686175746844617461" +
            "44" +
            "ab".repeat(4)

        assertEquals(expected, webAuthnNoneAttestationObject(authData).toHex())
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
