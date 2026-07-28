package com.artemchep.keyguard.util.foundation.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

class HashKatTest {
    private val crypto = PlatformCryptoPrimitives()

    @Test
    fun sha256EmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            crypto.sha256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun sha256Abc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto.sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha1EmptyInput() {
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            crypto.sha1(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun sha1Abc() {
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            crypto.sha1("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha512EmptyInput() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            crypto.sha512(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun sha512Abc() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            crypto.sha512("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun md5EmptyInput() {
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            crypto.md5(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun md5Abc() {
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            crypto.md5("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha256MultiBlockNistMessage() {
        val message =
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
                .encodeToByteArray()
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            crypto.sha256(message).toHex(),
        )
    }

    @Test
    fun digestLengths() {
        val x = "abc".encodeToByteArray()
        assertEquals(20, crypto.sha1(x).size)
        assertEquals(32, crypto.sha256(x).size)
        assertEquals(64, crypto.sha512(x).size)
        assertEquals(16, crypto.md5(x).size)
    }
}
