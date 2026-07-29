package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NativeCryptoPasskeysTest {
    @Test
    fun `generated ES256 key inspects and signs through the native boundary`() {
        val generated = NativeCrypto.passkeys.generate(NativePasskeyAlgorithm.ES256)
        try {
            assertEquals(NativePasskeyKeyProfile.EC_P256, generated.profile)
            assertEquals(32, generated.publicKeyX.size)
            assertEquals(32, generated.publicKeyY.size)
            assertTrue(generated.publicKeySpki.isNotEmpty())

            val inspected = assertIs<NativePasskeyKeyInspectionResult.Success>(
                NativeCrypto.passkeys.inspect(generated.privateKeyPkcs8),
            ).keyMaterial
            try {
                assertEquals(NativePasskeyKeyProfile.EC_P256, inspected.profile)
                assertContentEquals(generated.privateKeyPkcs8, inspected.privateKeyPkcs8)
                assertContentEquals(generated.publicKeyX, inspected.publicKeyX)
                assertContentEquals(generated.publicKeyY, inspected.publicKeyY)
                assertContentEquals(generated.publicKeySpki, inspected.publicKeySpki)
            } finally {
                inspected.clear()
            }

            val signature = assertIs<NativePasskeySignResult.Success>(
                NativeCrypto.passkeys.sign(
                    algorithm = NativePasskeyAlgorithm.ES256,
                    privateKeyPkcs8 = generated.privateKeyPkcs8,
                    data = "authenticator-data".encodeToByteArray(),
                ),
            ).signatureDer
            try {
                assertTrue(signature.size in 8..80)
                assertEquals(0x30.toByte(), signature.first())
            } finally {
                signature.fill(0)
            }
        } finally {
            generated.clear()
        }
    }

    @Test
    fun `arbitrary decoded bytes are a malformed passkey key`() {
        assertEquals(
            NativePasskeyKeyInspectionResult.Error(NativePasskeyKeyError.MALFORMED),
            NativeCrypto.passkeys.inspect(byteArrayOf(0, 1, 2, 3, 4, 5)),
        )
    }

    @Test
    fun `contradictory inspection response scrubs unclaimed key material`() {
        val material = validMaterialProto()
        val error = assertFailsWith<NativeCryptoException> {
            NativeCryptoPasskeys.decodeInspectionResult(
                operation = "test_inspect",
                result = PasskeyKeyInspectionProto(
                    keyMaterial = material,
                    error = PasskeyKeyErrorProto.MALFORMED,
                ),
            )
        }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, error.code)
        assertScrubbed(material)
    }

    @Test
    fun `successful inspection transfers key material ownership`() {
        val material = validMaterialProto()
        val result = assertIs<NativePasskeyKeyInspectionResult.Success>(
            NativeCryptoPasskeys.decodeInspectionResult(
                operation = "test_inspect",
                result = PasskeyKeyInspectionProto(keyMaterial = material),
            ),
        )

        try {
            assertContentEquals(byteArrayOf(1, 2, 3), result.keyMaterial.privateKeyPkcs8)
            assertTrue(result.keyMaterial.publicKeyX.any { it != 0.toByte() })
            assertTrue(result.keyMaterial.publicKeyY.any { it != 0.toByte() })
            assertContentEquals(byteArrayOf(4, 5, 6), result.keyMaterial.publicKeySpki)
        } finally {
            result.keyMaterial.clear()
        }
    }

    @Test
    fun `contradictory sign response scrubs unclaimed signature`() {
        val signature = byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 1)
        val error = assertFailsWith<NativeCryptoException> {
            NativeCryptoPasskeys.decodeSignResult(
                operation = "test_sign",
                algorithm = NativePasskeyAlgorithm.ES256,
                result = PasskeySignResultProto(
                    signature = PasskeySignatureProto(
                        algorithm = PasskeyAlgorithmProto.ES256,
                        signatureDer = signature,
                    ),
                    error = PasskeyKeyErrorProto.MALFORMED,
                ),
            )
        }

        assertEquals(NativeCryptoErrorCode.MALFORMED_RESPONSE, error.code)
        assertTrue(signature.all { it == 0.toByte() })
    }

    private fun validMaterialProto(): PasskeyKeyMaterialProto = PasskeyKeyMaterialProto(
        profile = PasskeyKeyProfileProto.EC_P256,
        privateKeyPkcs8 = byteArrayOf(1, 2, 3),
        publicKeyX = ByteArray(32) { 0x11.toByte() },
        publicKeyY = ByteArray(32) { 0x22.toByte() },
        publicKeySpki = byteArrayOf(4, 5, 6),
    )

    private fun assertScrubbed(material: PasskeyKeyMaterialProto) {
        assertTrue(material.privateKeyPkcs8.all { it == 0.toByte() })
        assertTrue(material.publicKeyX.all { it == 0.toByte() })
        assertTrue(material.publicKeyY.all { it == 0.toByte() })
        assertTrue(material.publicKeySpki.all { it == 0.toByte() })
    }
}
