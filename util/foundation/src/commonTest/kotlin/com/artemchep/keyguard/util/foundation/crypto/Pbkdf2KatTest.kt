package com.artemchep.keyguard.util.foundation.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class Pbkdf2KatTest {
    private val crypto = PlatformCryptoPrimitives()

    @Test
    fun c1DkLen32() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 1, 32)
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            result.toHex(),
        )
    }

    @Test
    fun c1DkLen20() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 1, 20)
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c9",
            result.toHex(),
        )
    }

    @Test
    fun c2DkLen20() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 2, 20)
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8e",
            result.toHex(),
        )
    }

    @Test
    fun c2DkLen32() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 2, 32)
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            result.toHex(),
        )
    }

    @Test
    fun c4096DkLen20() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 4096, 20)
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a0",
            result.toHex(),
        )
    }

    @Test
    fun c4096DkLen32() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 4096, 32)
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            result.toHex(),
        )
    }

    @Test
    fun multiBlockC4096DkLen40() {
        val p = "passwordPASSWORDpassword".encodeToByteArray()
        val s = "saltSALTsaltSALTsaltSALTsaltSALTsalt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 4096, 40)
        assertEquals(
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
            result.toHex(),
        )
    }

    @Test
    fun embeddedNulBinarySafe() {
        val p = "pass\u0000word".encodeToByteArray()
        val s = "sa\u0000lt".encodeToByteArray()
        val result = crypto.pbkdf2Sha256(p, s, 4096, 16)
        assertEquals(
            "89b69d0516f829893c696226650a8687",
            result.toHex(),
        )
    }

    @Test
    fun prefixInvariance() {
        val p = "password".encodeToByteArray()
        val s = "salt".encodeToByteArray()
        assertContentEquals(
            crypto.pbkdf2Sha256(p, s, 2, 20),
            crypto.pbkdf2Sha256(p, s, 2, 32).copyOf(20),
        )
        assertContentEquals(
            crypto.pbkdf2Sha256(p, s, 4096, 32),
            crypto.pbkdf2Sha256(p, s, 4096, 40).copyOf(32),
        )
    }
}
