package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentCrypto
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyNotFoundException
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentUnsupportedAlgorithmException
import com.artemchep.keyguard.common.service.gpgagent.GpgCanonicalSExpr
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.util.toHex
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.DigestInfo
import org.bouncycastle.bcpg.ECDHPublicBCPGKey
import org.bouncycastle.bcpg.ECSecretBCPGKey
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.RFC3394WrapEngine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.openpgp.operator.RFC6637Utils
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyConverter
import org.kodein.di.DirectDI
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import javax.crypto.Cipher
import org.bouncycastle.jce.interfaces.ECPrivateKey as BcECPrivateKey

class GpgAgentCryptoJvm() : GpgAgentCrypto {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun signHash(
        privateKeyArmored: String,
        metadataKey: GpgAgentKeyMetadataKey,
        hashAlgorithm: String,
        hash: ByteArray,
    ): GpgAgentMessages.SignHashResponse {
        val secretKey = findSecretKey(
            privateKeyArmored = privateKeyArmored,
            metadataKey = metadataKey,
            usable = { it.isSigningKey },
        ) ?: throw GpgAgentKeyNotFoundException()

        return when (secretKey.publicKey.algorithm) {
            PublicKeyAlgorithmTags.RSA_GENERAL,
            PublicKeyAlgorithmTags.RSA_SIGN,
                -> signRsa(
                privateKey = extractJcaPrivateKey(secretKey),
                hashAlgorithm = hashAlgorithm,
                hash = hash,
            )

            PublicKeyAlgorithmTags.ECDSA,
                -> signEcdsa(
                privateKey = extractJcaPrivateKey(secretKey),
                hash = hash,
            )

            // EdDSA over Curve25519 — both the legacy (algorithm 22) and the
            // RFC 9580 native Ed25519 (algorithm 27) keys use the same Ed25519
            // primitive and the same (sig-val(eddsa ...)) response shape.
            PublicKeyAlgorithmTags.EDDSA_LEGACY,
            PublicKeyAlgorithmTags.Ed25519,
                -> signEddsa(
                privateKey = extractJcaPrivateKey(secretKey),
                hash = hash,
            )

            else -> throw GpgAgentUnsupportedAlgorithmException(
                "Unsupported OpenPGP public-key algorithm: ${secretKey.publicKey.algorithm}",
            )
        }
    }

    // Returns the libgcrypt decryption result as an advanced-format `(value #HEX#)`
    // (the desktop agent transport converts it to canonical form for gpg). The work
    // split matches the real gpg-agent, verified by the integration E2E test:
    //  - RSA: return the bare modular-exponentiation result (m = c^d mod n); gpg
    //    strips the PKCS#1 padding itself.
    //  - ECDH: old gpg clients use plain PKDECRYPT and expect the shared-secret
    //    value so gpg can do RFC 6637 KDF + unwrap. Newer clients use
    //    PKDECRYPT --kem=PGP and expect the agent to return the unwrapped
    //    session-key block.
    override fun pkdecrypt(
        privateKeyArmored: String,
        metadataKey: GpgAgentKeyMetadataKey,
        ciphertext: ByteArray,
        unwrapEcdh: Boolean,
    ): GpgAgentMessages.PkdecryptResponse {
        val secretKey = findSecretKey(
            privateKeyArmored = privateKeyArmored,
            metadataKey = metadataKey,
            usable = { it.publicKey.isEncryptionKey },
        ) ?: throw GpgAgentKeyNotFoundException()

        val (algo, params) = GpgCanonicalSExpr.parseEncVal(
            GpgCanonicalSExpr.parse(ciphertext),
        )

        return when (algo) {
            "rsa" -> decryptRsa(
                privateKey = extractJcaPrivateKey(secretKey),
                params = params,
            )

            "ecdh", "ecc" -> decryptEcdh(
                secretKey = secretKey,
                params = params,
                unwrapEcdh = unwrapEcdh,
            )

            else -> throw GpgAgentUnsupportedAlgorithmException(
                "Unsupported PKDECRYPT algorithm: $algo",
            )
        }
    }

    private fun decryptRsa(
        privateKey: PrivateKey,
        params: Map<String, ByteArray>,
    ): GpgAgentMessages.PkdecryptResponse {
        val rsaPrivateKey = privateKey as? RSAPrivateKey
            ?: throw GpgAgentUnsupportedAlgorithmException("RSA private key has unexpected type")
        // gpg sends the RSA ciphertext as the MPI `a`; the agent returns the raw
        // m = c^d mod n without removing the PKCS#1 padding (gpg does that). Use a JCA
        // cipher rather than a bare BigInteger.modPow so BouncyCastle applies RSA blinding
        // and CRT, hardening the private-key operation against timing side channels.
        val c = params["a"]
            ?: throw IllegalArgumentException("RSA enc-val is missing the 'a' parameter")
        // The ciphertext is an MPI and can be shorter than the modulus, but the cipher
        // expects a full modulus-width block, so left-pad the magnitude with zeros.
        val modulusBytes = (rsaPrivateKey.modulus.bitLength() + 7) / 8
        val magnitude = c.stripUnsignedMagnitudePadding()
        require(magnitude.size <= modulusBytes) {
            "RSA ciphertext (${magnitude.size} bytes) is longer than the modulus ($modulusBytes bytes)"
        }
        val block = ByteArray(modulusBytes)
        magnitude.copyInto(block, destinationOffset = modulusBytes - magnitude.size)

        val cipher = Cipher.getInstance("RSA/ECB/NoPadding", gpgBouncyCastleProvider)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        val m = BigInteger(1, cipher.doFinal(block))
        return GpgAgentMessages.PkdecryptResponse(
            valueSexp = "(value #${m.toUnsignedHex()}#)",
        )
    }

    private fun decryptEcdh(
        secretKey: PGPSecretKey,
        params: Map<String, ByteArray>,
        unwrapEcdh: Boolean,
    ): GpgAgentMessages.PkdecryptResponse {
        // gpg sends the ECDH enc-val with two MPIs:
        //  - `e`: the ephemeral point, from which the shared secret is derived.
        //  - `s`: the AES-key-wrapped session key, prefixed with a one-byte length.
        // gpg-agent computes the ECDH shared secret, derives the KEK with the
        // RFC 6637 KDF, AES-unwraps `s`, and returns the unwrapped (still
        // PKCS#5-padded) session-key block as `(value ...)`. gpg then strips the
        // padding itself.
        val e = params["e"]
            ?: throw IllegalArgumentException("ECDH enc-val is missing the 'e' parameter")
        val wrappedKey = params["s"]
            ?.let { stripEcdhWrappedKeyLengthPrefix(it) }
            ?: throw IllegalArgumentException("ECDH enc-val is missing the 's' (wrapped key) parameter")

        // The RFC 6637 KDF reads the shared X coordinate (NIST) / shared u-coordinate
        // (X25519) as the 32/48-byte secret.
        val privateKey = extractJcaPrivateKey(secretKey)
        val sharedSecret = when (privateKey) {
            is BcECPrivateKey -> ecdhSharedSecretNist(privateKey, e)
            else -> ecdhSharedSecretX25519(secretKey, e)
        }
        if (!unwrapEcdh) {
            return GpgAgentMessages.PkdecryptResponse(
                valueSexp = "(value #${sharedSecret.legacyValue.toHex().uppercase()}#)",
            )
        }

        val ecdhPublicKey = secretKey.publicKey.publicKeyPacket.key as? ECDHPublicBCPGKey
            ?: throw GpgAgentUnsupportedAlgorithmException("ECDH key has unexpected public-key packet")
        val kek = rfc6637DeriveKek(
            publicKeyPacket = secretKey.publicKey.publicKeyPacket,
            ecdhPublicKey = ecdhPublicKey,
            sharedSecret = sharedSecret.kdfInput,
        )
        val unwrapped = aesUnwrap(kek = kek, wrapped = wrappedKey)

        return GpgAgentMessages.PkdecryptResponse(
            valueSexp = "(value #${unwrapped.toHex().uppercase()}#)",
        )
    }

    private data class EcdhSharedSecret(
        val kdfInput: ByteArray,
        val legacyValue: ByteArray,
    )

    // gpg encodes the ECDH wrapped key MPI as `<len-byte> || <wrapped key bytes>`;
    // the leading byte is the length of the remaining (wrapped) bytes.
    private fun stripEcdhWrappedKeyLengthPrefix(s: ByteArray): ByteArray {
        if (s.isEmpty()) {
            throw IllegalArgumentException("ECDH wrapped key is empty")
        }
        val declaredLength = s[0].toInt() and 0xFF
        val body = s.copyOfRange(1, s.size)
        require(declaredLength == body.size) {
            "ECDH wrapped key length prefix ($declaredLength) does not match body size (${body.size})"
        }
        return body
    }

    private fun ecdhSharedSecretNist(
        privateKey: BcECPrivateKey,
        ephemeralPoint: ByteArray,
    ): EcdhSharedSecret {
        // `e` is the uncompressed point 0x04||X||Y; multiply by our scalar d. The
        // RFC 6637 KDF consumes the fixed-width X coordinate of the shared point.
        val curve = privateKey.parameters.curve
        val point = curve.decodePoint(ephemeralPoint)
        val shared = point.multiply(privateKey.d).normalize()
        return EcdhSharedSecret(
            kdfInput = shared.affineXCoord.encoded,
            legacyValue = shared.getEncoded(false),
        )
    }

    private fun ecdhSharedSecretX25519(
        secretKey: PGPSecretKey,
        ephemeralPoint: ByteArray,
    ): EcdhSharedSecret {
        // The OpenPGP MPI carries the X25519 point prefixed with 0x40 (33 bytes);
        // strip it to get the 32-byte u-coordinate.
        val u = if (ephemeralPoint.size == X25519_POINT_SIZE + 1 &&
            ephemeralPoint[0] == X25519_POINT_PREFIX
        ) {
            ephemeralPoint.copyOfRange(1, ephemeralPoint.size)
        } else {
            ephemeralPoint
        }
        require(u.size == X25519_POINT_SIZE) {
            "Unexpected X25519 ephemeral point length: ${u.size}"
        }

        // Read the raw private scalar from the OpenPGP key. It is stored as an MPI
        // (big-endian); X25519 expects the 32-byte clamped scalar in little-endian,
        // so reverse the byte order.
        val privateKeyPacket = secretKey.extractPrivateKeyEmptyPassphrase().privateKeyDataPacket as? ECSecretBCPGKey
            ?: throw GpgAgentUnsupportedAlgorithmException("Curve25519 key has unexpected private-key packet")
        val scalarBigEndian = privateKeyPacket.x.toByteArray().stripUnsignedMagnitudePadding()
        val scalar = ByteArray(X25519_POINT_SIZE)
        // Right-align the big-endian magnitude, then reverse into little-endian.
        val copyLength = minOf(scalarBigEndian.size, X25519_POINT_SIZE)
        for (index in 0 until copyLength) {
            scalar[index] = scalarBigEndian[scalarBigEndian.size - 1 - index]
        }

        val privateKeyParameters = X25519PrivateKeyParameters(scalar, 0)
        val publicKeyParameters = X25519PublicKeyParameters(u, 0)
        val agreement = X25519Agreement()
        agreement.init(privateKeyParameters)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(publicKeyParameters, shared, 0)
        return EcdhSharedSecret(
            kdfInput = shared,
            legacyValue = byteArrayOf(X25519_POINT_PREFIX) + shared,
        )
    }

    // RFC 6637 §7/§8: KEK = leftmost(keyLen, Hash(00 00 00 01 || sharedX || Param)),
    // where Param is the user keying material derived from the recipient's public key.
    private fun rfc6637DeriveKek(
        publicKeyPacket: org.bouncycastle.bcpg.PublicKeyPacket,
        ecdhPublicKey: ECDHPublicBCPGKey,
        sharedSecret: ByteArray,
    ): ByteArray {
        val userKeyingMaterial = RFC6637Utils.createUserKeyingMaterial(
            publicKeyPacket,
            JcaKeyFingerprintCalculator(),
        )
        val hashAlgorithm = ecdhPublicKey.hashAlgorithm.toInt() and 0xFF
        val symmetricKeyAlgorithm = ecdhPublicKey.symmetricKeyAlgorithm.toInt() and 0xFF
        val keyLen = aesKeyWrapKeyLength(symmetricKeyAlgorithm)

        val digest = MessageDigest.getInstance(
            hashAlgorithmJcaName(hashAlgorithm),
            gpgBouncyCastleProvider,
        )
        digest.update(byteArrayOf(0x00, 0x00, 0x00, 0x01))
        digest.update(sharedSecret)
        digest.update(userKeyingMaterial)
        val md = digest.digest()
        require(md.size >= keyLen) {
            "KDF hash output (${md.size}) shorter than KEK length ($keyLen)"
        }
        return md.copyOfRange(0, keyLen)
    }

    private fun aesKeyWrapKeyLength(
        symmetricKeyAlgorithm: Int,
    ): Int = when (symmetricKeyAlgorithm) {
        SymmetricKeyAlgorithmTags.AES_128 -> 16
        SymmetricKeyAlgorithmTags.AES_192 -> 24
        SymmetricKeyAlgorithmTags.AES_256 -> 32
        else -> throw GpgAgentUnsupportedAlgorithmException(
            "Unsupported ECDH KEK algorithm: $symmetricKeyAlgorithm",
        )
    }

    private fun hashAlgorithmJcaName(
        hashAlgorithm: Int,
    ): String = when (hashAlgorithm) {
        HashAlgorithmTags.SHA256 -> "SHA-256"
        HashAlgorithmTags.SHA384 -> "SHA-384"
        HashAlgorithmTags.SHA512 -> "SHA-512"
        else -> throw GpgAgentUnsupportedAlgorithmException(
            "Unsupported ECDH KDF hash algorithm: $hashAlgorithm",
        )
    }

    // RFC 3394 AES key unwrap (the "AESKeyWrap" used by RFC 6637).
    private fun aesUnwrap(
        kek: ByteArray,
        wrapped: ByteArray,
    ): ByteArray {
        val engine = RFC3394WrapEngine(AESEngine.newInstance())
        engine.init(false, KeyParameter(kek))
        return engine.unwrap(wrapped, 0, wrapped.size)
    }

    private fun findSecretKey(
        privateKeyArmored: String,
        metadataKey: GpgAgentKeyMetadataKey,
        usable: (PGPSecretKey) -> Boolean,
    ): PGPSecretKey? {
        val collection = try {
            parseGpgSecretKeyRingCollection(privateKeyArmored)
        } catch (error: GpgUnsupportedKeyVersionException) {
            throw GpgAgentUnsupportedAlgorithmException(error.message.orEmpty()).apply {
                initCause(error)
            }
        }
        val expectedFingerprint = metadataKey.fingerprint
            .takeIf { it.isNotBlank() }
            ?.normalizeGpgFingerprint()
        val fallback = mutableListOf<PGPSecretKey>()

        val ringIterator = collection.keyRings
        while (ringIterator.hasNext()) {
            val ring = ringIterator.next()
            val keyIterator = ring.secretKeys
            while (keyIterator.hasNext()) {
                val secretKey = keyIterator.next()
                if (!usable(secretKey)) {
                    continue
                }
                val fingerprint = secretKey.fingerprintHex()
                if (expectedFingerprint != null && fingerprint == expectedFingerprint) {
                    return secretKey
                }
                fallback += secretKey
            }
        }
        // The operation always carries a specific keygrip, and the metadata
        // key matched against it is the authoritative selector. Never fall
        // back to an arbitrary key:
        //  - if a fingerprint is known but absent from the ring, fail closed;
        //  - if the metadata fingerprint is blank, accept a single unambiguous
        //    candidate only, rather than guessing among several keys.
        return when {
            expectedFingerprint != null -> null
            fallback.size == 1 -> fallback.single()
            else -> null
        }
    }

    private fun extractJcaPrivateKey(
        secretKey: PGPSecretKey,
    ): PrivateKey = JcaPGPKeyConverter()
        .setProvider(gpgBouncyCastleProvider)
        .getPrivateKey(secretKey.extractPrivateKeyEmptyPassphrase())

    private fun signRsa(
        privateKey: PrivateKey,
        hashAlgorithm: String,
        hash: ByteArray,
    ): GpgAgentMessages.SignHashResponse {
        val digestInfo = DigestInfo(
            AlgorithmIdentifier(
                hashAlgorithmOid(hashAlgorithm),
                DERNull.INSTANCE,
            ),
            hash,
        ).encoded
        val signer = Signature.getInstance("NONEwithRSA", gpgBouncyCastleProvider)
        signer.initSign(privateKey)
        signer.update(digestInfo)
        val signature = signer.sign()
            .stripUnsignedMagnitudePadding()
        return GpgAgentMessages.SignHashResponse(
            sexp = "(sig-val(rsa(s #${signature.toHex().uppercase()}#)))",
        )
    }

    private fun signEcdsa(
        privateKey: PrivateKey,
        hash: ByteArray,
    ): GpgAgentMessages.SignHashResponse {
        // gpg sends the bare digest for ECDSA (no DigestInfo wrapping). BouncyCastle's
        // NONEwithECDSA treats the input as the pre-computed hash and truncates it to the
        // curve order's bit length, exactly as ECDSA requires.
        val signer = Signature.getInstance("NONEwithECDSA", gpgBouncyCastleProvider)
        signer.initSign(privateKey)
        signer.update(hash)
        val derSignature = signer.sign()
        // The JCA signature is DER: SEQUENCE { INTEGER r, INTEGER s }. gpg reads ecdsa r/s
        // as MPIs (value-based), so emit them as minimal unsigned big-endian.
        val sequence = ASN1Sequence.getInstance(derSignature)
        val r = (sequence.getObjectAt(0) as ASN1Integer).value
        val s = (sequence.getObjectAt(1) as ASN1Integer).value
        return GpgAgentMessages.SignHashResponse(
            sexp = "(sig-val(ecdsa(r #${r.toUnsignedHex()}#)(s #${s.toUnsignedHex()}#)))",
        )
    }

    private fun signEddsa(
        privateKey: PrivateKey,
        hash: ByteArray,
    ): GpgAgentMessages.SignHashResponse {
        // Ed25519 signs the supplied digest directly (the agent never sees the message).
        val signer = Signature.getInstance("Ed25519", gpgBouncyCastleProvider)
        signer.initSign(privateKey)
        signer.update(hash)
        val signature = signer.sign()
        require(signature.size == ED25519_SIGNATURE_SIZE) {
            "Unexpected Ed25519 signature length: ${signature.size}"
        }
        // gpg reads eddsa r/s as opaque fixed-width data, so emit the full 32 bytes each
        // without stripping leading zeros (R and S are encoded points/scalars, not MPIs).
        val r = signature.copyOfRange(0, ED25519_COMPONENT_SIZE)
        val s = signature.copyOfRange(ED25519_COMPONENT_SIZE, ED25519_SIGNATURE_SIZE)
        return GpgAgentMessages.SignHashResponse(
            sexp = "(sig-val(eddsa(r #${r.toHex().uppercase()}#)(s #${s.toHex().uppercase()}#)))",
        )
    }

    // Minimal unsigned big-endian hex of a non-negative integer, matching how libgcrypt
    // renders an MPI (BigInteger.toByteArray() may carry a leading 0x00 sign byte).
    private fun BigInteger.toUnsignedHex(): String =
        toUnsignedBytes().toHex().uppercase()

    private fun hashAlgorithmOid(
        hashAlgorithm: String,
    ) = when (hashAlgorithm.lowercase()) {
        "sha1" -> OIWObjectIdentifiers.idSHA1
        "sha224" -> NISTObjectIdentifiers.id_sha224
        "sha256" -> NISTObjectIdentifiers.id_sha256
        "sha384" -> NISTObjectIdentifiers.id_sha384
        "sha512" -> NISTObjectIdentifiers.id_sha512
        else -> throw IllegalArgumentException("Unsupported hash algorithm: $hashAlgorithm")
    }

    companion object {
        // An RFC 8032 Ed25519 signature is the 64-byte concatenation R(32) || S(32).
        private const val ED25519_COMPONENT_SIZE = 32
        private const val ED25519_SIGNATURE_SIZE = ED25519_COMPONENT_SIZE * 2

        // An X25519 point is the 32-byte u-coordinate; OpenPGP prefixes it with 0x40
        // to mark the libgcrypt "native point" encoding.
        private const val X25519_POINT_SIZE = 32
        private const val X25519_POINT_PREFIX = 0x40.toByte()
    }
}
