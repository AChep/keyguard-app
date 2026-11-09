package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCryptoCtapPrfTest {
    @Test
    fun protocol1MatchesIndependentVectorAcrossBridge() =
        assertBridge(
            protocol = 1,
            encrypted =
                "5f468b3f91d5f68d0182007347ec276da4fdf7d1ea57e733271004b9ccc97a75" +
                    "410fd3fcfe9a16ea03a50cd9b0da842df6765ca94ec9cc34e7b748e75120201a",
            authentication = "c38c3b023c10bfb4ec2b35391f45f82d",
            expectedKeys =
                "eea14853f0d2996406327d017b3a083c6a5602e7dd43fe71e50d8200f3cc4dc4" +
                    "eea14853f0d2996406327d017b3a083c6a5602e7dd43fe71e50d8200f3cc4dc4",
        )

    @Test
    fun protocol2MatchesIndependentVectorAcrossBridge() =
        assertBridge(
            protocol = 2,
            encrypted =
                "03030303030303030303030303030303636a69320dba2de8d2aae8301833cb8f" +
                    "db1e033dcb5b6b6ad9b5e47f2cf35dd6088dcbebf2ad072da730b49d1698e989" +
                    "0b89e533c79aa4740b5ea078143a2ba7",
            authentication = "219037efbc1b782951b50f8d2bbf8cc69dfddafc3cc6f059b8709ea6fff7ae0d",
            expectedKeys =
                "1697f446d0f999cb5c8412e6860ae8d9b1906a602d2d9a29c1e8a82aa0603ff5" +
                    "6d5d9935f17b2b28e5e3d287797094cf27519327f199e2f23f257428f5e559d5",
        )

    /** Decrypts an independent vector, rejects a tampered tag, and round-trips encrypted outputs. */
    private fun assertBridge(
        protocol: Int,
        encrypted: String,
        authentication: String,
        expectedKeys: String,
    ) {
        val privateKey = ByteArray(32) { 1 }
        val salts = encrypted.hexToByteArray()
        val tag = authentication.hexToByteArray()
        val keys = expectedKeys.hexToByteArray()
        val decoded = NativeCryptoCtapPrf.decrypt(protocol, privateKey, PEER, salts, tag)
        assertContentEquals(keys + ByteArray(64) { it.toByte() }, decoded)
        tag[0] = (tag[0].toInt() xor 1).toByte()
        val failure =
            assertFailsWith<NativeCryptoException> {
                NativeCryptoCtapPrf.decrypt(protocol, privateKey, PEER, salts, tag)
            }
        assertEquals(NativeCryptoErrorCode.AUTHENTICATION_FAILED, failure.code)
        val results = RESULTS.hexToByteArray()
        val response = NativeCryptoCtapPrf.encrypt(protocol, keys, results)
        val responseTag = NativeCrypto.primitives.hmac(keys.copyOf(32), response, NativeHashAlgorithm.SHA_256)
        // Protocol 1 truncates the authentication tag to 16 bytes.
        val sent = if (protocol == 1) responseTag.copyOf(16) else responseTag
        val opened = NativeCryptoCtapPrf.decrypt(protocol, privateKey, PEER, response, sent)
        assertContentEquals(keys + results, opened)
        decoded.fill(0)
        opened.fill(0)
        keys.fill(0)
        results.fill(0)
    }

    private companion object {
        val PEER =
            (
                "04550f471003f3df97c3df506ac797f6721fb1a1fb7b8f6f83d224498a65c88e" +
                    "24136093d7012e509a73715cbd0b00a3cc0ff4b5c01b3ffa196ab1fb327036b8" +
                    "e6"
            ).hexToByteArray()
        const val RESULTS =
            "1f8dabf24c4078e5686392adfa224e013b769519f0918ae0d70ccd93b85c9772" +
                "9b94a219a504a4c9aedbc8dccaaa650a83b5ae605120a65c0aabcf3325cbce96"
    }
}
