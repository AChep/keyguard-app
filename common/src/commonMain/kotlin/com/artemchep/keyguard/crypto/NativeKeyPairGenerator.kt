package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.exception.crypto.SshKeyDecodeException
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.KeyPairRaw
import com.artemchep.keyguard.common.model.KeyParameterRawZero
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeSshKeyMaterial
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import com.artemchep.keyguard.platform.util.isRelease
import org.kodein.di.DirectDI
import org.kodein.di.instance

class NativeKeyPairGenerator(
    private val base64Service: Base64Service,
) : KeyPairGenerator {
    constructor(
        directDI: DirectDI,
    ) : this(
        base64Service = directDI.instance(),
    )

    override fun ed25519(): KeyParameterRawZero = NativeCrypto.ssh
        .generate(type = NativeSshKeyType.ED25519)
        .toDomain()

    override fun rsa(
        length: KeyPairGenerator.RsaLength,
    ): KeyParameterRawZero = NativeCrypto.ssh
        .generate(
            type = NativeSshKeyType.RSA,
            rsaBits = length.size,
        )
        .toDomain()

    override fun parse(
        privateKey: String,
        publicKey: String,
    ): KeyParameterRawZero = runCatching {
        NativeCrypto.ssh
            .parse(
                privateKeyPem = privateKey,
                publicKeyOpenSsh = publicKey,
            )
            .toDomain()
    }.getOrElse { e ->
        val header = run {
            val headerRegex = "-{1,5}BEGIN (.*)-{1,5}".toRegex()
            headerRegex.find(privateKey)?.groupValues?.firstOrNull()
        }
        if (!isRelease) {
            throw SshKeyDecodeException(header, e = e)
        }
        throw SshKeyDecodeException(header)
    }

    override fun populate(
        keyPair: KeyParameterRawZero,
    ): KeyPair {
        val description = NativeCrypto.ssh.describe(
            type = keyPair.type.toNative(),
            privateKey = keyPair.privateKey.encoded,
            publicKey = keyPair.publicKey.encoded,
        )
        return KeyPair(
            type = keyPair.type,
            publicKey = KeyPair.KeyParameter(
                type = keyPair.type,
                encoded = keyPair.publicKey.encoded,
                ssh = description.publicKeyOpenSsh,
                fingerprint = description.publicFingerprint,
            ),
            privateKey = KeyPair.KeyParameter(
                type = keyPair.type,
                encoded = keyPair.privateKey.encoded,
                ssh = description.privateKeyPem,
                fingerprint = description.privateFingerprint,
            ),
        )
    }

    override fun getPrivateKeyLengthOrNull(
        keyPair: KeyParameterRawZero,
    ): Int? = runCatching {
        NativeCrypto.ssh.privateKeyRsaBits(keyPair.privateKey.encoded)
    }.getOrNull()

    override fun getPrivateKeyLengthOrNull(
        privateKey: String,
    ): Int? {
        val decodedPrivateKey = runCatching {
            decodePrivateKeyPem(privateKey)
        }.getOrNull() ?: return null
        return try {
            runCatching {
                NativeCrypto.ssh.privateKeyRsaBits(decodedPrivateKey)
            }.getOrNull()
        } finally {
            decodedPrivateKey.fill(0)
        }
    }

    private fun decodePrivateKeyPem(privateKey: String): ByteArray {
        val encodedKeyBase64 = privateKey
            .replace("-{1,5}(BEGIN|END) (|RSA |OPENSSH )PRIVATE KEY-{1,5}".toRegex(), "")
            .lineSequence()
            .map { it.trim() }
            .joinToString(separator = "")
        return base64Service.decode(encodedKeyBase64)
    }
}

private fun NativeSshKeyMaterial.toDomain(): KeyParameterRawZero = KeyPairRaw(
    type = type.toDomain(),
    privateKey = KeyPairRaw.KeyParameter(privateKey),
    publicKey = KeyPairRaw.KeyParameter(publicKey),
)
