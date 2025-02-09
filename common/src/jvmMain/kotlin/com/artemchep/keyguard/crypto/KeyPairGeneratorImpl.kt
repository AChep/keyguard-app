package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.exception.crypto.SshKeyDecodeException
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairRaw
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.platform.util.isRelease
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.AsymmetricCipherKeyPairGenerator
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.util.encoders.Base64
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.math.BigInteger
import java.security.SecureRandom


class KeyPairGeneratorJvm(
    private val cryptoGenerator: CryptoGenerator,
) : KeyPairGenerator {
    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
    )

    override fun rsa(
        length: KeyPairGenerator.RsaLength,
    ): KeyParameterRawZero {
        val random = SecureRandom()
        val gen = RSAKeyPairGenerator()
        val params = RSAKeyGenerationParameters(
            BigInteger.valueOf(0x10001),
            random,
            length.size,
            256,
        )
        gen.init(params)
        return createKeyPair(gen)
    }

    override fun ed25519(): KeyParameterRawZero {
        val random = SecureRandom()
        val gen: AsymmetricCipherKeyPairGenerator = Ed25519KeyPairGenerator()
        gen.init(KeyGenerationParameters(random, 255))
        return createKeyPair(gen)
    }

    override fun parse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero = kotlin.runCatching {
        performParse(
            privateKey = privateKey,
            publicKey = publicKey,
        )
    }.getOrElse { e ->
        // Try to extract the type of the key.
        val header = kotlin.run {
            val headerRegex = "-{1,5}BEGIN (.*)-{1,5}".toRegex()
            headerRegex.find(privateKey)?.groupValues?.firstOrNull()
        }
        // Throw the original exception, that possibly
        // contains some sensitive info.
        if (!isRelease) {
            val newException = SshKeyDecodeException(header, e = e)
            throw newException
        }

        throw SshKeyDecodeException(header)
    }

    private fun performParse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero {
        val encodedPublicKey = kotlin.run {
            // The format is
            // key-type XXXXXX optional comment
            val encodedKeyBase64 = publicKey
                .substringAfter(' ')
                .substringBefore(' ')
                .trim()
            val encodedKey = encodedKeyBase64
                .decodeAsBase64()
            encodedKey
        }
        val parsedPublicKey = OpenSSHPublicKeyUtil.parsePublicKey(encodedPublicKey)
        val encodedPrivateKey = kotlin.run {
            val encodedKeyBase64 = privateKey
                .replace("-{1,5}(BEGIN|END) (|RSA |OPENSSH )PRIVATE KEY-{1,5}".toRegex(), "")
                // Remove all line breaks
                .lineSequence()
                .map { it.trim() }
                .joinToString(separator = "")
            val encodedKey = encodedKeyBase64
                .decodeAsBase64()
            encodedKey
        }
        // We parse it to confirm that we do understand
        // it and can manipulate it in the future if we
        // need to.
        val parsedPrivateKey = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(encodedPrivateKey)
        return KeyPairRaw(
            type = when (parsedPublicKey) {
                is RSAKeyParameters -> KeyPair.Type.RSA
                is Ed25519PublicKeyParameters -> KeyPair.Type.ED25519
                else -> throw IllegalArgumentException()
            },
            privateKey = KeyPairRaw.KeyParameter(encodedPrivateKey),
            publicKey = KeyPairRaw.KeyParameter(encodedPublicKey),
        )
    }

    override fun populate(
        keyPair: KeyParameterRawZero,
    ): KeyPair {
        val publicKey = kotlin.run {
            val ssh = kotlin.run {
                val prefix = when (keyPair.type) {
                    KeyPair.Type.ED25519 -> "ssh-ed25519"
                    KeyPair.Type.RSA -> "ssh-rsa"
                }

                val encodedKeyBase64 = keyPair.publicKey.encoded.encodeAsBase64()
                "$prefix $encodedKeyBase64"
            }
            val fingerprint = keyPair.publicKey.encoded.encodeAsFingerprint()
            KeyPair.KeyParameter(
                type = keyPair.type,
                encoded = keyPair.publicKey.encoded,
                ssh = ssh,
                fingerprint = fingerprint,
            )
        }
        val privateKey = kotlin.run {
            val ssh = kotlin.run {
                // TODO: Current OpenSSHPrivateKeyUtil.encodePrivateKey(key) generates
                //  a key in RSA format for the RSA key. Would be nice to always export
                //  it as OPENSSH.
                val (lineLength, headerType) = when (keyPair.type) {
                    KeyPair.Type.ED25519 -> 70 to "OPENSSH PRIVATE KEY"
                    KeyPair.Type.RSA -> 64 to "RSA PRIVATE KEY"
                }

                val encodedKeyBase64SingleLine = keyPair.privateKey.encoded.encodeAsBase64()
                val encodedKeyBase64MultiLineList = encodedKeyBase64SingleLine
                    .windowedSequence(lineLength, step = lineLength, partialWindows = true)
                buildString {
                    append("-----BEGIN $headerType-----")
                    appendLine()
                    encodedKeyBase64MultiLineList.forEach { line ->
                        append(line)
                        appendLine()
                    }
                    append("-----END $headerType-----")
                    appendLine()
                }
            }
            val fingerprint = keyPair.privateKey.encoded.encodeAsFingerprint()
            KeyPair.KeyParameter(
                type = keyPair.type,
                encoded = keyPair.privateKey.encoded,
                ssh = ssh,
                fingerprint = fingerprint,
            )
        }
        return KeyPair(
            type = keyPair.type,
            publicKey = publicKey,
            privateKey = privateKey,
        )
    }

    private fun createKeyPair(
        generator: AsymmetricCipherKeyPairGenerator,
    ): KeyParameterRawZero {
        val keyPair = generator.generateKeyPair()
        return createKeyPair(keyPair)
    }

    private fun createKeyPair(
        keyPair: AsymmetricCipherKeyPair,
    ): KeyParameterRawZero {
        val type = when (keyPair.public) {
            is RSAKeyParameters -> KeyPair.Type.RSA
            is Ed25519PublicKeyParameters -> KeyPair.Type.ED25519
            else -> throw IllegalArgumentException("Unsupported asymmetric cipher key pair")
        }
        return KeyPairRaw(
            type = type,
            privateKey = createKeyPrivate(keyPair.private),
            publicKey = createKeyPublic(keyPair.public),
        )
    }

    private fun createKeyPublic(
        publicKey: AsymmetricKeyParameter,
    ): KeyPairRaw.KeyParameter {
        val encodedKey = OpenSSHPublicKeyUtil.encodePublicKey(publicKey)
        return KeyPairRaw.KeyParameter(
            encoded = encodedKey,
        )
    }

    private fun createKeyPrivate(
        publicKey: AsymmetricKeyParameter,
    ): KeyPairRaw.KeyParameter {
        val encodedKey = OpenSSHPrivateKeyUtil.encodePrivateKey(publicKey)
        return KeyPairRaw.KeyParameter(
            encoded = encodedKey,
        )
    }

    /**
     * Encodes the given data chunk as a fingerprint. By
     * default it uses SHA-256 and should be compatible with
     * what the following command outputs:
     * ```
     * ssh-keygen -E sha256 -lf key-pub
     * ```
     */
    private fun ByteArray.encodeAsFingerprint() = kotlin.run {
        val hashBase64 = cryptoGenerator.hashSha256(this)
            .encodeAsBase64()
        "SHA256:$hashBase64"
    }

    private fun ByteArray.encodeAsBase64() = Base64
        .encode(this)
        .toString(Charsets.UTF_8)

    private fun String.decodeAsBase64() = Base64
        .decode(this)

    override fun getPrivateKeyLengthOrNull(
        keyPair: KeyParameterRawZero,
    ): Int? = kotlin.runCatching {
        val key = OpenSSHPrivateKeyUtil
            .parsePrivateKeyBlob(keyPair.privateKey.encoded)
        when (key) {
            is RSAPrivateCrtKeyParameters -> key.modulus.bitLength()
            else -> null
        }
    }.getOrNull()
}
