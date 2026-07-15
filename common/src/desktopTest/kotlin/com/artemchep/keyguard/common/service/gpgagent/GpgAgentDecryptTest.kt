package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.crypto.GpgTestKeyFixtures
import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.crypto.NativeGpgAgentCrypto
import org.bouncycastle.bcpg.ECDHPublicBCPGKey
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.RFC3394WrapEngine
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.jce.interfaces.ECPublicKey
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.RFC6637Utils
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-trip tests for the production native GPG PKDECRYPT implementation.
 *
 * The fixtures are real, unprotected (passphrase-less) OpenPGP secret keys
 * exported by GnuPG, each carrying an encryption (sub)key — exactly the shape the
 * agent receives in production.
 *
 * The work split matches a real gpg-agent (E2E-verified against gpg 2.5), and it is NOT
 * the same for the two algorithms:
 *  - RSA: the agent does only the raw private-key operation, returning m = c^d mod n with
 *    the PKCS#1 padding intact; gpg strips the padding itself. The test PKCS#1-encrypts a
 *    session key, decrypts it through the agent, then unpads m and checks it round-trips.
 *  - ECDH: newer `PKDECRYPT --kem=PGP` requests expect the agent to perform the
 *    full RFC 6637 KDF + AES unwrap and return the still-PKCS#5-padded session-key
 *    block. Legacy plain `PKDECRYPT` requests expect the shared-secret value so gpg
 *    can do the KDF + unwrap itself.
 */
class GpgAgentDecryptTest {
    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `canonical s-expression parser is byte-accurate`() {
        // (3:abc(1:x2:yz)) — an atom and a nested list.
        val bytes = "(3:abc(1:x2:yz))".encodeToByteArray()
        val node = GpgCanonicalSExpr.parse(bytes) as GpgSExpr.Listt
        assertEquals(2, node.items.size)
        assertEquals("abc", (node.items[0] as GpgSExpr.Atom).bytes.decodeToString())
        val nested = node.items[1] as GpgSExpr.Listt
        assertEquals("x", (nested.items[0] as GpgSExpr.Atom).bytes.decodeToString())
        assertEquals("yz", (nested.items[1] as GpgSExpr.Atom).bytes.decodeToString())
    }

    @Test
    fun `scoped canonical s-expression parsing clears nested atoms after success`() {
        val copiedAtoms = mutableListOf<ByteArray>()
        val formatted = GpgCanonicalSExpr.useParsed(
            "(6:public(6:secret4:more))".encodeToByteArray(),
        ) { node ->
            copiedAtoms += node.atomCopies()
            copiedAtoms.joinToString(separator = ":") { value -> value.decodeToString() }
        }

        assertEquals("public:secret:more", formatted)
        assertTrue(copiedAtoms.all { value -> value.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun `scoped canonical s-expression parsing clears nested atoms after failure`() {
        val copiedAtoms = mutableListOf<ByteArray>()

        assertFailsWith<IllegalStateException> {
            GpgCanonicalSExpr.useParsed(
                "(6:public(6:secret4:more))".encodeToByteArray(),
            ) { node ->
                copiedAtoms += node.atomCopies()
                throw IllegalStateException("formatting failed")
            }
        }

        assertTrue(copiedAtoms.all { value -> value.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun `parseEncVal extracts algorithm and params and skips flags`() {
        // (enc-val(flags pkcs1)(rsa(a3:abc)))
        val bytes = canonicalSExpr(
            list(
                atom("enc-val"),
                list(atom("flags"), atom("pkcs1")),
                list(atom("rsa"), list(atom("a"), atom("abc"))),
            ),
        )
        val (algo, params) = GpgCanonicalSExpr.parseEncVal(GpgCanonicalSExpr.parse(bytes))
        assertEquals("rsa", algo)
        assertEquals("abc", params["a"]?.decodeToString())
    }

    @Test
    fun `parseEncVal handles binary atom values with zero bytes`() {
        val value = byteArrayOf(0x00, 0x40, 0x00, 0x7f)
        val bytes = canonicalSExpr(
            list(
                atom("enc-val"),
                list(atom("ecdh"), list(atom("e"), atom(value))),
            ),
        )
        val (algo, params) = GpgCanonicalSExpr.parseEncVal(GpgCanonicalSExpr.parse(bytes))
        assertEquals("ecdh", algo)
        assertTrue(value.contentEquals(params["e"]!!), "binary value must survive untouched")
    }

    @Test
    fun `rsa pkdecrypt returns the raw modular exponentiation`() {
        val encryptionKey = encryptionPublicKey(GpgTestKeyFixtures.RSA)
        val publicKey = JcaPGPKeyConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getPublicKey(encryptionKey) as RSAPublicKey

        // gpg encrypts a random session key with PKCS#1 v1.5 padding; reproduce that.
        val sessionKey = ByteArray(19).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", BouncyCastleProvider.PROVIDER_NAME)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val ciphertext = cipher.doFinal(sessionKey)

        val encVal = canonicalSExpr(
            list(
                atom("enc-val"),
                list(
                    atom("rsa"),
                    list(atom("a"), atom(ciphertext)),
                ),
            ),
        )

        val response = NativeGpgAgentCrypto.pkdecrypt(
            privateKeyArmored = GpgTestKeyFixtures.RSA,
            metadataKey = GpgAgentKeyMetadataKey(keygrip = "", fingerprint = encryptionFingerprint(GpgTestKeyFixtures.RSA)),
            ciphertext = encVal,
            unwrapEcdh = false,
        )

        // The agent returns the raw m = c^d mod n with the PKCS#1 padding intact.
        val m = BigInteger(1, parseValue(response.valueSexp))
        val unpadded = pkcs1Unpad(m, publicKey.modulus.bitLength())
        assertTrue(sessionKey.contentEquals(unpadded), "PKCS#1-unpadded m must equal the session key")
    }

    @Test
    fun `cv25519 pkdecrypt performs the rfc6637 kdf and aes key unwrap`() {
        val encryptionKey = encryptionPublicKey(GpgTestKeyFixtures.CV25519)
        // The recipient public point is 0x40 || U; strip the prefix to get the 32-byte U.
        val recipientU = stripPointPrefix(curve25519PublicPoint(encryptionKey))

        // Ephemeral keypair on Curve25519.
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(SecureRandom()))
        val ephemeral = generator.generateKeyPair()
        val ephemeralPrivate = ephemeral.private as X25519PrivateKeyParameters
        val ephemeralPublic = ephemeral.public as X25519PublicKeyParameters

        // Sender-side ECDH: shared = ephemeral_private × recipient_public.
        val agreement = X25519Agreement()
        agreement.init(ephemeralPrivate)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(recipientU, 0), sharedSecret, 0)

        val sessionKeyBlock = randomSessionKeyBlock()
        val kek = rfc6637Kek(encryptionKey, sharedSecret)
        val wrapped = aesWrap(kek, sessionKeyBlock)

        // OpenPGP encodes the ephemeral point as 0x40 || U; `s` is `<len> || wrapped`.
        val e = byteArrayOf(0x40) + ephemeralPublic.encoded
        val encVal = canonicalSExpr(
            list(
                atom("enc-val"),
                list(
                    atom("ecdh"),
                    list(atom("s"), atom(byteArrayOf(wrapped.size.toByte()) + wrapped)),
                    list(atom("e"), atom(e)),
                ),
            ),
        )

        val response = NativeGpgAgentCrypto.pkdecrypt(
            privateKeyArmored = GpgTestKeyFixtures.CV25519,
            metadataKey = GpgAgentKeyMetadataKey(keygrip = "", fingerprint = encryptionFingerprint(GpgTestKeyFixtures.CV25519)),
            ciphertext = encVal,
            unwrapEcdh = true,
        )

        // The agent must recover the original (still PKCS#5-padded) session-key block.
        assertTrue(
            sessionKeyBlock.contentEquals(parseValue(response.valueSexp)),
            "cv25519 agent must AES-unwrap the session key block",
        )
    }

    @Test
    fun `cv25519 legacy pkdecrypt returns prefixed shared secret`() {
        val encryptionKey = encryptionPublicKey(GpgTestKeyFixtures.CV25519)
        val recipientU = stripPointPrefix(curve25519PublicPoint(encryptionKey))

        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(SecureRandom()))
        val ephemeral = generator.generateKeyPair()
        val ephemeralPrivate = ephemeral.private as X25519PrivateKeyParameters
        val ephemeralPublic = ephemeral.public as X25519PublicKeyParameters

        val agreement = X25519Agreement()
        agreement.init(ephemeralPrivate)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(recipientU, 0), sharedSecret, 0)

        val sessionKeyBlock = randomSessionKeyBlock()
        val kek = rfc6637Kek(encryptionKey, sharedSecret)
        val wrapped = aesWrap(kek, sessionKeyBlock)

        val e = byteArrayOf(0x40) + ephemeralPublic.encoded
        val encVal = canonicalSExpr(
            list(
                atom("enc-val"),
                list(
                    atom("ecdh"),
                    list(atom("s"), atom(byteArrayOf(wrapped.size.toByte()) + wrapped)),
                    list(atom("e"), atom(e)),
                ),
            ),
        )

        val response = NativeGpgAgentCrypto.pkdecrypt(
            privateKeyArmored = GpgTestKeyFixtures.CV25519,
            metadataKey = GpgAgentKeyMetadataKey(keygrip = "", fingerprint = encryptionFingerprint(GpgTestKeyFixtures.CV25519)),
            ciphertext = encVal,
            unwrapEcdh = false,
        )

        assertTrue(
            (byteArrayOf(0x40) + sharedSecret).contentEquals(parseValue(response.valueSexp)),
            "legacy cv25519 agent response must be 0x40 || shared_u",
        )
    }

    @Test
    fun `nistp256 pkdecrypt performs the rfc6637 kdf and aes key unwrap`() {
        val encryptionKey = encryptionPublicKey(GpgTestKeyFixtures.NISTP256)
        val publicKey = JcaPGPKeyConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getPublicKey(encryptionKey) as ECPublicKey

        val generator = publicKey.parameters.g
        val order = publicKey.parameters.n

        // Ephemeral keypair on the same curve.
        val random = SecureRandom()
        val ephemeralPrivate = BigInteger(order.bitLength(), random).mod(order)
        val ephemeralPoint = generator.multiply(ephemeralPrivate).normalize()

        // Sender-side ECDH: shared = recipient_public × ephemeral_private; the KDF
        // consumes the shared point's fixed-width X coordinate.
        val sharedPoint = publicKey.q.multiply(ephemeralPrivate).normalize()
        val sharedSecret = sharedPoint.affineXCoord.encoded

        val sessionKeyBlock = randomSessionKeyBlock()
        val kek = rfc6637Kek(encryptionKey, sharedSecret)
        val wrapped = aesWrap(kek, sessionKeyBlock)

        // OpenPGP encodes the ephemeral point uncompressed: 0x04 || X || Y.
        val e = ephemeralPoint.getEncoded(false)
        val encVal = canonicalSExpr(
            list(
                atom("enc-val"),
                list(
                    atom("ecdh"),
                    list(atom("s"), atom(byteArrayOf(wrapped.size.toByte()) + wrapped)),
                    list(atom("e"), atom(e)),
                ),
            ),
        )

        val response = NativeGpgAgentCrypto.pkdecrypt(
            privateKeyArmored = GpgTestKeyFixtures.NISTP256,
            metadataKey = GpgAgentKeyMetadataKey(keygrip = "", fingerprint = encryptionFingerprint(GpgTestKeyFixtures.NISTP256)),
            ciphertext = encVal,
            unwrapEcdh = true,
        )

        assertTrue(
            sessionKeyBlock.contentEquals(parseValue(response.valueSexp)),
            "nistp256 agent must AES-unwrap the session key block",
        )
    }

    @Test
    fun `nistp256 legacy pkdecrypt returns shared point`() {
        val encryptionKey = encryptionPublicKey(GpgTestKeyFixtures.NISTP256)
        val publicKey = JcaPGPKeyConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getPublicKey(encryptionKey) as ECPublicKey

        val generator = publicKey.parameters.g
        val order = publicKey.parameters.n

        val random = SecureRandom()
        val ephemeralPrivate = BigInteger(order.bitLength(), random).mod(order)
        val ephemeralPoint = generator.multiply(ephemeralPrivate).normalize()

        val sharedPoint = publicKey.q.multiply(ephemeralPrivate).normalize()
        val sharedSecret = sharedPoint.affineXCoord.encoded

        val sessionKeyBlock = randomSessionKeyBlock()
        val kek = rfc6637Kek(encryptionKey, sharedSecret)
        val wrapped = aesWrap(kek, sessionKeyBlock)

        val e = ephemeralPoint.getEncoded(false)
        val encVal = canonicalSExpr(
            list(
                atom("enc-val"),
                list(
                    atom("ecdh"),
                    list(atom("s"), atom(byteArrayOf(wrapped.size.toByte()) + wrapped)),
                    list(atom("e"), atom(e)),
                ),
            ),
        )

        val response = NativeGpgAgentCrypto.pkdecrypt(
            privateKeyArmored = GpgTestKeyFixtures.NISTP256,
            metadataKey = GpgAgentKeyMetadataKey(keygrip = "", fingerprint = encryptionFingerprint(GpgTestKeyFixtures.NISTP256)),
            ciphertext = encVal,
            unwrapEcdh = false,
        )

        assertTrue(
            sharedPoint.getEncoded(false).contentEquals(parseValue(response.valueSexp)),
            "legacy nistp256 agent response must be the uncompressed shared point",
        )
    }

    /**
     * A representative OpenPGP ECDH session-key block: algo byte || key || 2-byte
     * checksum, PKCS#5-padded to a multiple of 8 (so AES key wrap accepts it). The
     * agent returns it verbatim; gpg strips the padding itself.
     */
    private fun randomSessionKeyBlock(): ByteArray {
        val sessionKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val checksum = sessionKey.fold(0) { acc, b -> acc + (b.toInt() and 0xff) } and 0xffff
        val body = byteArrayOf(0x09.toByte()) + sessionKey +
            byteArrayOf((checksum ushr 8).toByte(), (checksum and 0xff).toByte())
        val padLen = 8 - (body.size % 8)
        return body + ByteArray(padLen) { padLen.toByte() }
    }

    private fun rfc6637Kek(
        encryptionKey: PGPPublicKey,
        sharedSecret: ByteArray,
    ): ByteArray {
        val ecdhKey = encryptionKey.publicKeyPacket.key as ECDHPublicBCPGKey
        val ukm = RFC6637Utils.createUserKeyingMaterial(
            encryptionKey.publicKeyPacket,
            JcaKeyFingerprintCalculator(),
        )
        val hashName = when (ecdhKey.hashAlgorithm.toInt() and 0xff) {
            HashAlgorithmTags.SHA256 -> "SHA-256"
            HashAlgorithmTags.SHA384 -> "SHA-384"
            HashAlgorithmTags.SHA512 -> "SHA-512"
            else -> error("unsupported KDF hash ${ecdhKey.hashAlgorithm}")
        }
        val keyLen = when (ecdhKey.symmetricKeyAlgorithm.toInt() and 0xff) {
            SymmetricKeyAlgorithmTags.AES_128 -> 16
            SymmetricKeyAlgorithmTags.AES_192 -> 24
            SymmetricKeyAlgorithmTags.AES_256 -> 32
            else -> error("unsupported KEK algo ${ecdhKey.symmetricKeyAlgorithm}")
        }
        val digest = MessageDigest.getInstance(hashName, BouncyCastleProvider.PROVIDER_NAME)
        digest.update(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        digest.update(sharedSecret)
        digest.update(ukm)
        return digest.digest().copyOfRange(0, keyLen)
    }

    private fun aesWrap(
        kek: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val engine = RFC3394WrapEngine(AESEngine.newInstance())
        engine.init(true, KeyParameter(kek))
        return engine.wrap(data, 0, data.size)
    }

    private fun pkcs1Unpad(
        m: BigInteger,
        modulusBits: Int,
    ): ByteArray {
        // Reconstruct the fixed-width EM (00 02 PS 00 M) and strip the padding.
        val byteLength = (modulusBits + 7) / 8
        val em = ByteArray(byteLength)
        val magnitude = m.toByteArray().let {
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        System.arraycopy(magnitude, 0, em, byteLength - magnitude.size, magnitude.size)
        assertEquals(0x00, em[0].toInt() and 0xff, "EM[0] must be 0x00")
        assertEquals(0x02, em[1].toInt() and 0xff, "EM[1] must be 0x02 (PKCS#1 v1.5)")
        var index = 2
        while (index < em.size && em[index] != 0.toByte()) {
            index++
        }
        return em.copyOfRange(index + 1, em.size)
    }

    private fun parseValue(sexp: String): ByteArray {
        assertTrue(sexp.startsWith("(value #") && sexp.endsWith("#)"), "Unexpected value S-expression: $sexp")
        val hex = sexp.removePrefix("(value #").removeSuffix("#)")
        return hex.hexToByteArray()
    }

    private fun stripPointPrefix(point: ByteArray): ByteArray =
        if (point.size == 33 && point[0] == 0x40.toByte()) point.copyOfRange(1, point.size) else point

    private fun curve25519PublicPoint(key: PGPPublicKey): ByteArray {
        // The Curve25519 (algorithm 18, ECDH) public key stores the MPI 0x40 || U.
        // BigInteger.toByteArray() can prepend a sign byte for the high 0x40, so
        // right-align to the expected 33-byte prefixed point.
        val bcpgKey = key.publicKeyPacket.key as ECDHPublicBCPGKey
        val raw = bcpgKey.encodedPoint.toByteArray()
        val point = if (raw.size == 34 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
        require(point.size == 33 && point[0] == 0x40.toByte()) {
            "Unexpected Curve25519 public point length: ${point.size}"
        }
        return point
    }

    private fun encryptionPublicKey(armored: String): PGPPublicKey {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val encryptionKeys = collection.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .filter { it.isEncryptionKey }
            .toList()
        // Prefer a dedicated encryption subkey over the master key.
        return encryptionKeys.firstOrNull { !it.isMasterKey }
            ?: encryptionKeys.first()
    }

    private fun encryptionFingerprint(armored: String): String =
        encryptionPublicKey(armored).fingerprint.toHex().uppercase()

    // --- Canonical S-expression builders -------------------------------------

    private fun atom(value: String): GpgSExpr = GpgSExpr.Atom(value.encodeToByteArray())
    private fun atom(value: ByteArray): GpgSExpr = GpgSExpr.Atom(value)
    private fun list(vararg items: GpgSExpr): GpgSExpr = GpgSExpr.Listt(items.toList())

    private fun GpgSExpr.atomCopies(): List<ByteArray> = when (this) {
        is GpgSExpr.Atom -> listOf(bytes)
        is GpgSExpr.Listt -> items.flatMap { item -> item.atomCopies() }
    }

    private fun canonicalSExpr(node: GpgSExpr): ByteArray = node.encodeCanonical()
}
