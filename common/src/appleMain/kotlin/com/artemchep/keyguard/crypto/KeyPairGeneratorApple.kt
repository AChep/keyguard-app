package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairRaw
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import org.kodein.di.DirectDI
import org.kodein.di.instance
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFNumberIntType
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeRSA

/**
 * SSH key generator for Apple platforms.
 *
 * RSA keys are generated natively through the `Security` framework. Ed25519 keys
 * are produced in Swift via CryptoKit and pushed into [AppleEd25519BridgeRegistry]
 * at startup, because neither `Security` nor Kotlin/Native cinterop can reach
 * CryptoKit's `Curve25519` types.
 *
 * The emitted byte formats match [com.artemchep.keyguard.crypto] on the JVM so
 * keys interoperate across synced platforms.
 */
@OptIn(ExperimentalForeignApi::class)
class KeyPairGeneratorApple(
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
) : KeyPairGenerator {
    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
    )

    override fun ed25519(): KeyParameterRawZero {
        val bridge = checkNotNull(AppleEd25519BridgeRegistry.bridge) {
            "Ed25519 generation is not available: the CryptoKit bridge is not registered."
        }
        val material = bridge.generate()
        require(material.seed.size == 32 && material.publicKey.size == 32) {
            "Unexpected Ed25519 key material size."
        }
        val publicWire = encodeEd25519PublicWire(material.publicKey)
        val privateBlob = encodeEd25519PrivateOpenSsh(
            seed = material.seed,
            publicKey = material.publicKey,
            checkInt = cryptoGenerator.random(),
        )
        return KeyPairRaw(
            type = KeyPair.Type.ED25519,
            privateKey = KeyPairRaw.KeyParameter(privateBlob),
            publicKey = KeyPairRaw.KeyParameter(publicWire),
        )
    }

    override fun rsa(
        length: KeyPairGenerator.RsaLength,
    ): KeyParameterRawZero {
        val key = generateRsaKey(length.size)
        // PKCS#1 RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }
        val reader = DerReader(key.publicPkcs1).readConstructed(0x30)
        val modulus = reader.readIntegerBytes()
        val exponent = reader.readIntegerBytes()
        val publicWire = encodeRsaPublicWire(
            modulus = modulus,
            exponent = exponent,
        )
        return KeyPairRaw(
            type = KeyPair.Type.RSA,
            privateKey = KeyPairRaw.KeyParameter(key.privatePkcs1),
            publicKey = KeyPairRaw.KeyParameter(publicWire),
        )
    }

    override fun parse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero {
        val encodedPublicKey = run {
            // The format is: key-type XXXXXX optional comment
            val encodedKeyBase64 = publicKey
                .substringAfter(' ')
                .substringBefore(' ')
                .trim()
            base64Service.decode(encodedKeyBase64)
        }
        val type = run {
            val keyType = SshWireReader(encodedPublicKey).readString()
            when (keyType) {
                "ssh-ed25519" -> KeyPair.Type.ED25519
                "ssh-rsa" -> KeyPair.Type.RSA
                else -> throw IllegalArgumentException("Unsupported SSH key type: $keyType")
            }
        }
        val encodedPrivateKey = decodePrivateKeyPem(privateKey)
        return KeyPairRaw(
            type = type,
            privateKey = KeyPairRaw.KeyParameter(encodedPrivateKey),
            publicKey = KeyPairRaw.KeyParameter(encodedPublicKey),
        )
    }

    override fun populate(
        keyPair: KeyParameterRawZero,
    ): KeyPair {
        val publicKey = run {
            val prefix = when (keyPair.type) {
                KeyPair.Type.ED25519 -> "ssh-ed25519"
                KeyPair.Type.RSA -> "ssh-rsa"
            }
            val encodedKeyBase64 = base64Service.encodeToString(keyPair.publicKey.encoded)
            KeyPair.KeyParameter(
                type = keyPair.type,
                encoded = keyPair.publicKey.encoded,
                ssh = "$prefix $encodedKeyBase64",
                fingerprint = encodeAsFingerprint(keyPair.publicKey.encoded),
            )
        }
        val privateKey = run {
            val ssh = encodePrivateKeyPemApple(
                base64Service = base64Service,
                type = keyPair.type,
                encodedPrivateKey = keyPair.privateKey.encoded,
            )
            KeyPair.KeyParameter(
                type = keyPair.type,
                encoded = keyPair.privateKey.encoded,
                ssh = ssh,
                fingerprint = encodeAsFingerprint(keyPair.privateKey.encoded),
            )
        }
        return KeyPair(
            type = keyPair.type,
            publicKey = publicKey,
            privateKey = privateKey,
        )
    }

    override fun getPrivateKeyLengthOrNull(
        keyPair: KeyParameterRawZero,
    ): Int? = rsaModulusBitLengthOrNull(keyPair.privateKey.encoded)

    override fun getPrivateKeyLengthOrNull(
        privateKey: String,
    ): Int? = runCatching { decodePrivateKeyPem(privateKey) }
        .getOrNull()
        ?.let(::rsaModulusBitLengthOrNull)

    /**
     * Best-effort RSA modulus bit length from an RSA private key DER blob.
     *
     * Handles both the PKCS#1 `RSAPrivateKey` we emit ourselves and the PKCS#8
     * `PrivateKeyInfo` wrapper that Desktop's import path stores for some keys
     * (e.g. PuTTY-originated). Returns `null` for Ed25519 or anything that does
     * not parse as one of these.
     */
    private fun rsaModulusBitLengthOrNull(
        encodedPrivateKey: ByteArray,
    ): Int? = runCatching {
        val seq = DerReader(encodedPrivateKey).readConstructed(0x30)
        seq.readIntegerBytes() // version (0 for both PKCS#1 and PKCS#8)
        when (seq.peekTag()) {
            // PKCS#1 RSAPrivateKey ::= SEQUENCE { version, modulus INTEGER, ... }
            0x02 -> bitLengthOfBigEndian(seq.readIntegerBytes())
            // PKCS#8 PrivateKeyInfo ::= SEQUENCE { version, AlgorithmIdentifier,
            //   privateKey OCTET STRING } where the octet string is the PKCS#1 blob.
            0x30 -> {
                seq.readConstructed(0x30) // privateKeyAlgorithm — skip
                val inner = DerReader(seq.readOctetString()).readConstructed(0x30)
                inner.readIntegerBytes() // version
                bitLengthOfBigEndian(inner.readIntegerBytes()) // modulus
            }

            else -> null
        }
    }.getOrNull()

    private fun encodeAsFingerprint(
        data: ByteArray,
    ): String {
        val hashBase64 = base64Service.encodeToString(cryptoGenerator.hashSha256(data))
        return "SHA256:$hashBase64"
    }

    private fun decodePrivateKeyPem(
        privateKey: String,
    ): ByteArray {
        val encodedKeyBase64 = privateKey
            .replace("-{1,5}(BEGIN|END) (|RSA |OPENSSH )PRIVATE KEY-{1,5}".toRegex(), "")
            .lineSequence()
            .map { it.trim() }
            .joinToString(separator = "")
        return base64Service.decode(encodedKeyBase64)
    }

    private class RsaKeyBytes(
        val privatePkcs1: ByteArray,
        val publicPkcs1: ByteArray,
    )

    private fun generateRsaKey(
        bits: Int,
    ): RsaKeyBytes = memScoped {
        val attrs = CFDictionaryCreateMutable(
            allocator = null,
            capacity = 0,
            keyCallBacks = null,
            valueCallBacks = null,
        )
        checkNotNull(attrs) {
            "Could not allocate Security.framework key attributes."
        }

        val sizeVar = alloc<IntVar>()
        sizeVar.value = bits
        val sizeNumber = CFNumberCreate(null, kCFNumberIntType, sizeVar.ptr)
        checkNotNull(sizeNumber) {
            "Could not allocate the RSA key size."
        }

        try {
            CFDictionarySetValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeRSA)
            CFDictionarySetValue(attrs, kSecAttrKeySizeInBits, sizeNumber)
            CFDictionarySetValue(attrs, kSecAttrIsPermanent, kCFBooleanFalse)

            val error = alloc<CFErrorRefVar>()
            val privateKey = SecKeyCreateRandomKey(attrs, error.ptr)
                ?: error("Could not generate the RSA key pair.")
            try {
                val publicKey = SecKeyCopyPublicKey(privateKey)
                    ?: error("Could not derive the RSA public key.")
                try {
                    val privateData = SecKeyCopyExternalRepresentation(privateKey, error.ptr)
                        ?: error("Could not export the RSA private key.")
                    try {
                        val publicData = SecKeyCopyExternalRepresentation(publicKey, error.ptr)
                            ?: error("Could not export the RSA public key.")
                        try {
                            RsaKeyBytes(
                                privatePkcs1 = privateData.toByteArray(),
                                publicPkcs1 = publicData.toByteArray(),
                            )
                        } finally {
                            CFRelease(publicData)
                        }
                    } finally {
                        CFRelease(privateData)
                    }
                } finally {
                    CFRelease(publicKey)
                }
            } finally {
                CFRelease(privateKey)
            }
        } finally {
            CFRelease(sizeNumber)
            CFRelease(attrs)
        }
    }
}
