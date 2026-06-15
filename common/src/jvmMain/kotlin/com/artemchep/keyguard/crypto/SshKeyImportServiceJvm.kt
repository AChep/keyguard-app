package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.SshKeyImportError
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.PublicKeyFactory
import org.bouncycastle.util.encoders.Base64
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.IOException
import java.nio.ByteBuffer
import java.security.PrivateKey
import java.security.PublicKey

class SshKeyImportServiceJvm(
    private val cryptoGenerator: CryptoGenerator,
) : SshKeyImportService {
    constructor(
        directDI: DirectDI,
    ) : this(
        cryptoGenerator = directDI.instance(),
    )

    override fun import(
        request: SshKeyImportRequest,
    ): SshKeyImportResult {
        val content = request.content.trim()
        val format = detectFormat(content)
            ?: return SshKeyImportResult.Error(SshKeyImportError.UnsupportedFormat)

        if (request.passphrase == null && format.isEncrypted) {
            return SshKeyImportResult.NeedsPassphrase(format.label)
        }

        val provider = createProvider(format.keyFormat)
        val passwordFinder = request.passphrase?.let(::OneOffPasswordFinder)
        return runCatching {
            provider.init(content, null, passwordFinder)
            val publicKey = provider.public
            val privateKey = provider.private
            normalize(
                privateKey = privateKey,
                publicKey = publicKey,
            )
        }.fold(
            onSuccess = { SshKeyImportResult.Success(it) },
            onFailure = { error ->
                when {
                    request.passphrase == null && isMissingPassphrase(error) ->
                        SshKeyImportResult.NeedsPassphrase(format.label)

                    isInvalidPassphrase(error) ->
                        SshKeyImportResult.Error(SshKeyImportError.InvalidPassphrase)

                    isUnsupportedAlgorithm(error) ->
                        SshKeyImportResult.Error(SshKeyImportError.UnsupportedAlgorithm)

                    else ->
                        SshKeyImportResult.Error(SshKeyImportError.MalformedKey)
                }
            },
        )
    }

    private fun normalize(
        privateKey: PrivateKey,
        publicKey: PublicKey,
    ): KeyPair {
        val publicKeyParameter = PublicKeyFactory.createKey(publicKey.encoded)
        val type = when (publicKeyParameter) {
            is RSAKeyParameters -> KeyPair.Type.RSA
            is Ed25519PublicKeyParameters -> KeyPair.Type.ED25519
            else -> throw UnsupportedOperationException("Unsupported SSH key algorithm")
        }
        val encodedPublicKey = OpenSSHPublicKeyUtil.encodePublicKey(publicKeyParameter)
        val publicKeySsh = buildPublicKeySsh(
            type = type,
            encoded = encodedPublicKey,
        )
        val encodedPrivateKey = when (type) {
            KeyPair.Type.ED25519 -> {
                val privateKeyParameter = PrivateKeyFactory.createKey(privateKey.encoded)
                OpenSSHPrivateKeyUtil.encodePrivateKey(privateKeyParameter)
            }

            // Some imported RSA keys, such as PuTTY keys, only carry n+d and are
            // exposed by JCA as PKCS#8. Preserve that encoding instead of forcing
            // a CRT-style PKCS#1 blob that later becomes unusable for signing.
            KeyPair.Type.RSA -> privateKey.encoded
        }
        return KeyPair(
            type = type,
            publicKey = KeyPair.KeyParameter(
                encoded = encodedPublicKey,
                type = type,
                ssh = publicKeySsh,
                fingerprint = encodedPublicKey.encodeAsFingerprint(),
            ),
            privateKey = KeyPair.KeyParameter(
                encoded = encodedPrivateKey,
                type = type,
                ssh = createPrivateKeyPem(
                    type = type,
                    encodedPrivateKey = encodedPrivateKey,
                ),
                fingerprint = encodedPrivateKey.encodeAsFingerprint(),
            ),
        )
    }

    private fun detectFormat(
        content: String,
    ): DetectedFormat? {
        val keyFormat = runCatching {
            KeyProviderUtil.detectKeyFileFormat(content, false)
        }.getOrNull() ?: return null
        if (keyFormat == KeyFormat.Unknown) {
            return null
        }

        val isEncrypted = when (keyFormat) {
            KeyFormat.OpenSSHv1 -> isEncryptedOpenSshV1(content)
            KeyFormat.PKCS8 -> isEncryptedPem(content)
            KeyFormat.PuTTY -> isEncryptedPutty(content)
            else -> false
        }
        val label = when (keyFormat) {
            KeyFormat.OpenSSHv1 -> "OpenSSH"
            KeyFormat.PKCS8 -> "PEM"
            KeyFormat.PuTTY -> "PuTTY"
            else -> "SSH"
        }
        return DetectedFormat(
            keyFormat = keyFormat,
            label = label,
            isEncrypted = isEncrypted,
        )
    }

    private fun createProvider(
        format: KeyFormat,
    ): FileKeyProvider = when (format) {
        KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
        KeyFormat.PKCS8 -> PKCS8KeyFile()
        KeyFormat.PuTTY -> PuTTYKeyFile()
        else -> throw IllegalArgumentException("Unsupported SSH key format")
    }

    private fun isEncryptedPem(
        content: String,
    ): Boolean = content.contains("BEGIN ENCRYPTED PRIVATE KEY") ||
            content.contains("Proc-Type: 4,ENCRYPTED")

    private fun isEncryptedPutty(
        content: String,
    ): Boolean = content.lineSequence()
        .firstOrNull { it.startsWith("Encryption:") }
        ?.substringAfter(':')
        ?.trim()
        ?.lowercase()
        ?.let { it != "none" }
        ?: false

    private fun isEncryptedOpenSshV1(
        content: String,
    ): Boolean = runCatching {
        val encodedKeyBase64 = content
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .map { it.trim() }
            .joinToString(separator = "")
        val bytes = Base64.decode(encodedKeyBase64)
        val authMagic = "openssh-key-v1\u0000".encodeToByteArray()
        if (bytes.size <= authMagic.size || !bytes.copyOfRange(0, authMagic.size).contentEquals(authMagic)) {
            return@runCatching false
        }

        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(authMagic.size)
        val cipherNameLength = buffer.int
        val cipherNameBytes = ByteArray(cipherNameLength)
        buffer.get(cipherNameBytes)
        val cipherName = cipherNameBytes.decodeToString()
        cipherName != "none"
    }.getOrDefault(false)

    private fun isMissingPassphrase(
        error: Throwable,
    ): Boolean {
        val message = error.message.orEmpty()
        return error is KeyDecryptionFailedException &&
                message.contains("Password not provided", ignoreCase = true)
    }

    private fun isInvalidPassphrase(
        error: Throwable,
    ): Boolean {
        val message = error.message.orEmpty()
        return error is KeyDecryptionFailedException ||
                error is IOException && message.contains("Invalid passphrase", ignoreCase = true)
    }

    private fun isUnsupportedAlgorithm(
        error: Throwable,
    ): Boolean {
        val message = error.message.orEmpty()
        return error is UnsupportedOperationException ||
                message.contains("algorithm", ignoreCase = true) &&
                message.contains("not supported", ignoreCase = true)
    }

    private fun buildPublicKeySsh(
        type: KeyPair.Type,
        encoded: ByteArray,
    ): String {
        val prefix = when (type) {
            KeyPair.Type.ED25519 -> "ssh-ed25519"
            KeyPair.Type.RSA -> "ssh-rsa"
        }
        return "$prefix ${encoded.encodeAsBase64()}"
    }

    private fun ByteArray.encodeAsFingerprint(): String {
        val hashBase64 = cryptoGenerator.hashSha256(this)
            .encodeAsBase64()
        return "SHA256:$hashBase64"
    }

    private fun ByteArray.encodeAsBase64(): String = Base64
        .encode(this)
        .toString(Charsets.UTF_8)

    private data class DetectedFormat(
        val keyFormat: KeyFormat,
        val label: String,
        val isEncrypted: Boolean,
    )

    private class OneOffPasswordFinder(
        private val passphrase: String,
    ) : PasswordFinder {
        override fun reqPassword(
            resource: Resource<*>?,
        ): CharArray = passphrase.toCharArray()

        override fun shouldRetry(
            resource: Resource<*>?,
        ) = false
    }
}
