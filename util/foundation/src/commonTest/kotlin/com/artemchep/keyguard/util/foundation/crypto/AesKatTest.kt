package com.artemchep.keyguard.util.foundation.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AesKatTest {
    private val crypto = PlatformCryptoPrimitives()

    @Test
    fun aesEcbNoPaddingFips197() {
        val key = "000102030405060708090a0b0c0d0e0f".hexToByteArray()
        val pt = "00112233445566778899aabbccddeeff".hexToByteArray()
        val expectedCtHex = "69c4e0d86a7b0430d8cdb78070b4c55a"
        assertEquals(expectedCtHex, crypto.aesEcbNoPaddingEncrypt(key, pt).toHex())
    }

    @Test
    fun aesEcbNoPaddingSp80038aF11Aes128() {
        val key = "2b7e151628aed2a6abf7158809cf4f3c".hexToByteArray()
        val pt = "6bc1bee22e409f96e93d7e117393172a".hexToByteArray()
        val expectedCtHex = "3ad77bb40d7a3660a89ecaf32466ef97"
        assertEquals(expectedCtHex, crypto.aesEcbNoPaddingEncrypt(key, pt).toHex())
    }

    @Test
    fun aesEcbNoPaddingSp80038aF13Aes192() {
        val key = "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b".hexToByteArray()
        val pt = "6bc1bee22e409f96e93d7e117393172a".hexToByteArray()
        val expectedCtHex = "bd334f1d6e45f25ff712a214571fa5cc"
        assertEquals(expectedCtHex, crypto.aesEcbNoPaddingEncrypt(key, pt).toHex())
    }

    @Test
    fun aesEcbNoPaddingSp80038aF15Aes256() {
        val key =
            "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"
                .hexToByteArray()
        val pt = "6bc1bee22e409f96e93d7e117393172a".hexToByteArray()
        val expectedCtHex = "f3eed1bdb5d2a03c064b5a7e3db181f8"
        assertEquals(expectedCtHex, crypto.aesEcbNoPaddingEncrypt(key, pt).toHex())
    }

    @Test
    fun aesCbcPkcs7Rfc3602Case2Aes128() {
        val key = "c286696d887c9aa0611bbb3e2025a45a".hexToByteArray()
        val iv = "562e17996d093d28ddb3ba695a2e6f58".hexToByteArray()
        val pt =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                .hexToByteArray()
        val expectedCtHex =
            "d296cd94c2cccf8a3a863028b5e1dc0a7586602d253cfff91b8266bea6d61ab1" +
                "bcfd81022202366bde6dd260a15841a1"
        assertEquals(expectedCtHex, crypto.aesCbcPkcs7Encrypt(key, iv, pt).toHex())
        assertContentEquals(pt, crypto.aesCbcPkcs7Decrypt(key, iv, expectedCtHex.hexToByteArray()))
    }

    @Test
    fun aesCbcPkcs7Sp80038aF25Aes256() {
        val key =
            "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4"
                .hexToByteArray()
        val iv = "000102030405060708090a0b0c0d0e0f".hexToByteArray()
        val pt =
            (
                "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                    "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
            ).hexToByteArray()
        val expectedCtHex =
            "f58c4c04d6e5f1ba779eabfb5f7bfbd69cfc4e967edb808d679f777bc6702c7d" +
                "39f23369a9d9bacfa530e26304231461b2eb05e2c39be9fcda6c19078c6a9d1b" +
                "3f461796d6b0d6b2e0c2a72b4d80e644"
        assertEquals(expectedCtHex, crypto.aesCbcPkcs7Encrypt(key, iv, pt).toHex())
        assertContentEquals(pt, crypto.aesCbcPkcs7Decrypt(key, iv, expectedCtHex.hexToByteArray()))
    }

    @Test
    fun aesCbcPkcs7NonAlignedRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val data = "hello".encodeToByteArray()
        val ct = crypto.aesCbcPkcs7Encrypt(key, iv, data)
        val rt = crypto.aesCbcPkcs7Decrypt(key, iv, ct)
        assertContentEquals(data, rt)
    }
}
