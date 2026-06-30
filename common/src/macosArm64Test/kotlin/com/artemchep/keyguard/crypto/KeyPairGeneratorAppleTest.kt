package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairRaw
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parity tests for [KeyPairGeneratorApple].
 *
 * The expected values are ground truth captured from the system tools so the
 * Apple output is verified against OpenSSH itself (and, transitively, the JVM
 * generator which targets the same byte formats):
 * ```
 * ssh-keygen -t ed25519 -N "" -C "" -f k_ed
 * ssh-keygen -t rsa -b 2048 -m PEM -N "" -C "" -f k_rsa
 * ```
 * Fingerprints use padded base64 (matching `Base64Service`/the JVM), so they
 * carry a trailing `=` that `ssh-keygen -l` omits.
 */
class KeyPairGeneratorAppleTest {
    private val base64: Base64Service = Base64ServiceImpl()
    private val generator = KeyPairGeneratorApple(
        cryptoGenerator = CryptoGeneratorApple(),
        base64Service = base64,
    )

    @AfterTest
    fun tearDown() {
        AppleEd25519BridgeRegistry.bridge = null
    }

    // --- Ed25519 ---

    @Test
    fun ed25519PublicWireMatchesOpenSsh() {
        val wire = encodeEd25519PublicWire(hex(ED_PUB_HEX))
        assertEquals(ED_PUB_TOKEN, base64.encodeToString(wire))
    }

    @Test
    fun ed25519PrivateBlobMatchesOpenSshByteForByte() {
        val blob = encodeEd25519PrivateOpenSsh(
            seed = hex(ED_SEED_HEX),
            publicKey = hex(ED_PUB_HEX),
            checkInt = ED_CHECK_INT,
        )
        assertEquals(ED_PRIV_BLOB_B64, base64.encodeToString(blob))
    }

    @Test
    fun ed25519GenerateAndPopulateMatchesOpenSsh() {
        AppleEd25519BridgeRegistry.bridge = FakeEd25519Bridge(
            seed = hex(ED_SEED_HEX),
            publicKey = hex(ED_PUB_HEX),
        )
        val keyPair = generator.populate(generator.ed25519())
        assertEquals(KeyPair.Type.ED25519, keyPair.type)
        assertEquals("ssh-ed25519 $ED_PUB_TOKEN", keyPair.publicKey.ssh)
        assertEquals(ED_FINGERPRINT, keyPair.publicKey.fingerprint)
        assertTrue(keyPair.privateKey.ssh.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertTrue(keyPair.privateKey.ssh.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
    }

    // --- RSA wire / fingerprint ---

    @Test
    fun rsaPublicWireMatchesOpenSsh() {
        val wire = encodeRsaPublicWire(
            modulus = hex(RSA_MODULUS_HEX),
            exponent = byteArrayOf(0x01, 0x00, 0x01),
        )
        assertEquals(RSA_PUB_TOKEN, base64.encodeToString(wire))
    }

    @Test
    fun rsaPopulateProducesOpenSshStringAndFingerprint() {
        val wire = encodeRsaPublicWire(
            modulus = hex(RSA_MODULUS_HEX),
            exponent = byteArrayOf(0x01, 0x00, 0x01),
        )
        val raw = KeyPairRaw(
            type = KeyPair.Type.RSA,
            privateKey = KeyPairRaw.KeyParameter(ByteArray(0)),
            publicKey = KeyPairRaw.KeyParameter(wire),
        )
        val keyPair = generator.populate(raw)
        assertEquals("ssh-rsa $RSA_PUB_TOKEN", keyPair.publicKey.ssh)
        assertEquals(RSA_FINGERPRINT, keyPair.publicKey.fingerprint)
    }

    // --- getPrivateKeyLengthOrNull: PKCS#1 + PKCS#8 ---

    @Test
    fun rsaLengthFromPkcs1() {
        // RSAPrivateKey ::= SEQUENCE { version, modulus, ... } — the reader only
        // needs version + modulus, so a two-field sequence is enough.
        val der = derSeq(derInt(byteArrayOf(0)) + derInt(unsigned(hex(RSA_MODULUS_HEX))))
        assertEquals(2048, generator.getPrivateKeyLengthOrNull(pem("RSA PRIVATE KEY", der)))
    }

    @Test
    fun rsaLengthFromPkcs8() {
        // PrivateKeyInfo ::= SEQUENCE { version, AlgorithmIdentifier, privateKey OCTET STRING }
        val pkcs1 = derSeq(derInt(byteArrayOf(0)) + derInt(unsigned(hex(RSA_MODULUS_HEX))))
        val pkcs8 = derSeq(derInt(byteArrayOf(0)) + derSeq(ByteArray(0)) + derOctet(pkcs1))
        assertEquals(2048, generator.getPrivateKeyLengthOrNull(pem("PRIVATE KEY", pkcs8)))
    }

    @Test
    fun ed25519PrivateKeyReportsNoRsaLength() {
        val pem = pem("OPENSSH PRIVATE KEY", base64.decode(ED_PRIV_BLOB_B64))
        assertNull(generator.getPrivateKeyLengthOrNull(pem))
    }

    // --- Real RSA generation via the Security framework ---

    @Test
    fun rsaGenerationRoundTrips() {
        val raw = generator.rsa(KeyPairGenerator.RsaLength.B2048)
        assertEquals(KeyPair.Type.RSA, raw.type)
        assertEquals(2048, generator.getPrivateKeyLengthOrNull(raw))

        val keyPair = generator.populate(raw)
        assertTrue(keyPair.publicKey.ssh.startsWith("ssh-rsa "))
        assertTrue(keyPair.privateKey.ssh.startsWith("-----BEGIN RSA PRIVATE KEY-----"))

        val reparsed = generator.parse(
            privateKey = keyPair.privateKey.ssh,
            publicKey = keyPair.publicKey.ssh,
        )
        assertEquals(KeyPair.Type.RSA, reparsed.type)
        assertEquals(2048, generator.getPrivateKeyLengthOrNull(reparsed))
    }

    // --- bitLengthOfBigEndian ---

    @Test
    fun bitLengthOfBigEndianHandlesLeadingZeros() {
        assertEquals(0, bitLengthOfBigEndian(byteArrayOf(0, 0)))
        assertEquals(1, bitLengthOfBigEndian(byteArrayOf(0x01)))
        assertEquals(8, bitLengthOfBigEndian(byteArrayOf(0xff.toByte())))
        assertEquals(2048, bitLengthOfBigEndian(hex(RSA_MODULUS_HEX)))
        assertEquals(2048, bitLengthOfBigEndian(byteArrayOf(0) + hex(RSA_MODULUS_HEX)))
    }

    private class FakeEd25519Bridge(
        private val seed: ByteArray,
        private val publicKey: ByteArray,
    ) : AppleEd25519Bridge {
        override fun generate(): AppleEd25519KeyMaterial =
            AppleEd25519KeyMaterial(seed = seed, publicKey = publicKey)
    }

    private companion object {
        const val ED_SEED_HEX = "66c9549f97399d21e3d8ae3b725e03aedfba1384f54bfd643c732eac3f94e4fc"
        const val ED_PUB_HEX = "b8e2b9a67d07f9f1b983ff7c50a9e7d9c93f74b4471c63935558035f052d753f"
        const val ED_CHECK_INT = 62239998
        const val ED_PUB_TOKEN = "AAAAC3NzaC1lZDI1NTE5AAAAILjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/"
        const val ED_PRIV_BLOB_B64 =
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZWQyNTUxOQ" +
                "AAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAIgDtbT+A7W0/gAAAAtzc2gtZWQ" +
                "yNTUxOQAAACC44rmmfQf58bmD/3xQqefZyT90tEccY5NVWANfBS11PwAAAEBmyVSflzmdIePYrjty" +
                "XgOu37oThPVL/WQ8cy6sP5Tk/LjiuaZ9B/nxuYP/fFCp59nJP3S0Rxxjk1VYA18FLXU/AAAAAAECAwQF"
        const val ED_FINGERPRINT = "SHA256:VMsjBVFrX4BnedkIhA3m9moiqwP6x2OcTGScAhS1TDw="

        const val RSA_MODULUS_HEX =
            "BBE7400AD3BF4EF2C120C4679FB4A4477B233EFD05E8AEF60F4063177E9A6B491AE11FD11380968F" +
                "5DDAA43A0C60F9B71F3BDAE4EF3F8AE89330B9E6581A5D83F7704FF266C2DE326396F9A0039FE623" +
                "F840406C3D5E5C6667A8B8B934478C1436F14829FB8B50B8FB7585EECBC0EF95A45367014BB2BF3B" +
                "950BA527030B90B80D147D15370F72C2F24622053FBCAD5F44335DE092DF94D4B9BDFABB49888CE5" +
                "301A969BA22FA84DEB2F3E43F1EADD9563FA03CB7E145CC0F38A4E3AE129F5D903C4E9DB0C8B00EF" +
                "761805A5C272CA583974A6B95F362C83896184E7A75F73B54D96388D0651BA03DB6656372199BB8C" +
                "D9749AC1F45ED8B1C5F1BB7278F185A9"
        const val RSA_PUB_TOKEN =
            "AAAAB3NzaC1yc2EAAAADAQABAAABAQC750AK079O8sEgxGeftKRHeyM+/QXorvYPQGMXfpprSRrhH9ET" +
                "gJaPXdqkOgxg+bcfO9rk7z+K6JMwueZYGl2D93BP8mbC3jJjlvmgA5/mI/hAQGw9XlxmZ6i4uTRHjBQ" +
                "28Ugp+4tQuPt1he7LwO+VpFNnAUuyvzuVC6UnAwuQuA0UfRU3D3LC8kYiBT+8rV9EM13gkt+U1Lm9+r" +
                "tJiIzlMBqWm6IvqE3rLz5D8erdlWP6A8t+FFzA84pOOuEp9dkDxOnbDIsA73YYBaXCcspYOXSmuV82L" +
                "IOJYYTnp19ztU2WOI0GUboD22ZWNyGZu4zZdJrB9F7YscXxu3J48YWp"
        const val RSA_FINGERPRINT = "SHA256:H+1+AXGzsT443DSlRyysBggaEEEGQU8gK2mu3IvAH6Y="

        fun hex(value: String): ByteArray = ByteArray(value.length / 2) { i ->
            ((value[i * 2].digitToInt(16) shl 4) or value[i * 2 + 1].digitToInt(16)).toByte()
        }

        /** Big-endian magnitude as a signed DER INTEGER body (leading 0x00 when top bit set). */
        fun unsigned(magnitude: ByteArray): ByteArray =
            if (magnitude.isNotEmpty() && (magnitude[0].toInt() and 0x80) != 0) {
                byteArrayOf(0) + magnitude
            } else {
                magnitude
            }

        fun derLength(length: Int): ByteArray {
            if (length < 0x80) {
                return byteArrayOf(length.toByte())
            }
            val bytes = ArrayList<Byte>()
            var value = length
            while (value > 0) {
                bytes.add(0, (value and 0xff).toByte())
                value = value ushr 8
            }
            return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }

        fun derTlv(tag: Int, content: ByteArray): ByteArray =
            byteArrayOf(tag.toByte()) + derLength(content.size) + content

        fun derSeq(content: ByteArray): ByteArray = derTlv(0x30, content)
        fun derOctet(content: ByteArray): ByteArray = derTlv(0x04, content)
        fun derInt(content: ByteArray): ByteArray = derTlv(0x02, content)

        fun pem(header: String, der: ByteArray): String {
            val body = Base64ServiceImpl().encodeToString(der)
                .chunked(64)
                .joinToString(separator = "\n")
            return "-----BEGIN $header-----\n$body\n-----END $header-----\n"
        }
    }
}
