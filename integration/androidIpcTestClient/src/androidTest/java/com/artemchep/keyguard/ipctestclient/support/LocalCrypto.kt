package com.artemchep.keyguard.ipctestclient.support

import android.os.Build
import com.artemchep.keyguard.ipctestclient.ipc.SshSignatureFrame
import org.openintents.ssh.authentication.SshAuthenticationApi
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Provider-independent verification.
 *
 * A signature the provider produced is only proof of anything if something other
 * than the provider can check it, so these use the platform JCA against the
 * SPKI public key the provider itself handed back.
 */
object LocalCrypto {
    const val SSH_RSA = "ssh-rsa"
    const val RSA_SHA2_256 = "rsa-sha2-256"
    const val RSA_SHA2_512 = "rsa-sha2-512"
    const val SSH_ED25519 = "ssh-ed25519"

    /** The signature algorithm Keyguard must pick for an RSA key and [hash]. */
    fun expectedRsaAlgorithm(hash: Int): String = when (hash) {
        SshAuthenticationApi.SHA1 -> SSH_RSA
        SshAuthenticationApi.SHA256 -> RSA_SHA2_256
        SshAuthenticationApi.SHA512 -> RSA_SHA2_512
        else -> error("Unsupported hash algorithm $hash")
    }

    private fun jcaNames(sshAlgorithm: String): Pair<String, String>? = when (sshAlgorithm) {
        SSH_RSA -> "RSA" to "SHA1withRSA"
        RSA_SHA2_256 -> "RSA" to "SHA256withRSA"
        RSA_SHA2_512 -> "RSA" to "SHA512withRSA"
        SSH_ED25519 -> "Ed25519" to "Ed25519"
        else -> null
    }

    /** Ed25519 only reached the platform JCA in API 33. */
    fun canVerify(sshAlgorithm: String): Boolean = when (sshAlgorithm) {
        SSH_ED25519 -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        else -> jcaNames(sshAlgorithm) != null
    }

    fun verify(
        spki: ByteArray,
        frame: SshSignatureFrame,
        challenge: ByteArray,
    ): Boolean {
        val (keyAlgorithm, signatureAlgorithm) = jcaNames(frame.algorithm)
            ?: return false
        val publicKey = KeyFactory
            .getInstance(keyAlgorithm)
            .generatePublic(X509EncodedKeySpec(spki))
        return Signature.getInstance(signatureAlgorithm).run {
            initVerify(publicKey)
            update(challenge)
            verify(frame.signature)
        }
    }

    /**
     * The OpenPGP packet tag of binary output, so a non-armored result can be
     * told apart from an armored one without parsing the whole message.
     *
     * RFC 4880 §4.2: bit 7 always set, bit 6 selects the new packet format.
     */
    fun packetTag(bytes: ByteArray): Int? {
        val header = bytes.firstOrNull()?.toInt()?.and(BYTE_MASK) ?: return null
        return when {
            header and FORMAT_MARKER == 0 -> null
            header and NEW_FORMAT_MARKER != 0 -> header and NEW_FORMAT_TAG_MASK
            else -> (header shr OLD_FORMAT_TAG_SHIFT) and OLD_FORMAT_TAG_MASK
        }
    }

    const val TAG_PUBLIC_KEY = 6
    const val TAG_SIGNATURE = 2
    const val TAG_PUBLIC_KEY_ENCRYPTED_SESSION_KEY = 1

    private const val BYTE_MASK = 0xFF
    private const val FORMAT_MARKER = 0x80
    private const val NEW_FORMAT_MARKER = 0x40
    private const val NEW_FORMAT_TAG_MASK = 0x3F
    private const val OLD_FORMAT_TAG_SHIFT = 2
    private const val OLD_FORMAT_TAG_MASK = 0x0F
}
