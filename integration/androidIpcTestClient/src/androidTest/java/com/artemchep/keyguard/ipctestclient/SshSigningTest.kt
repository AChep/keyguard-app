package com.artemchep.keyguard.ipctestclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artemchep.keyguard.ipctestclient.ipc.SshOperation
import com.artemchep.keyguard.ipctestclient.ipc.SshRequestSpec
import com.artemchep.keyguard.ipctestclient.ipc.parseSshSignatureFrame
import com.artemchep.keyguard.ipctestclient.ipc.sshHashAlgorithmName
import com.artemchep.keyguard.ipctestclient.support.KeyguardProviderRule
import com.artemchep.keyguard.ipctestclient.support.LocalCrypto
import com.artemchep.keyguard.ipctestclient.support.SuiteState
import com.artemchep.keyguard.ipctestclient.support.assertSshError
import com.artemchep.keyguard.ipctestclient.support.failWithExchangeLog
import com.artemchep.keyguard.ipctestclient.support.requireSshSuccess
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.SshAuthenticationApiError
import org.openintents.ssh.authentication.response.PublicKeyResponse
import org.openintents.ssh.authentication.response.SigningResponse

/**
 * Signing, checked against the provider's own public key.
 *
 * A signature is only evidence if something other than the provider can verify
 * it, so every case here is verified with the platform JCA using the SPKI blob
 * `GET_PUBLIC_KEY` returned.
 */
@RunWith(AndroidJUnit4::class)
class SshSigningTest {
    @get:Rule
    val provider = KeyguardProviderRule()

    private val state by lazy { SuiteState(provider) }

    @Test
    fun sha256SelectsTheRsaSha2Variant() {
        signAndVerify(SshAuthenticationApi.SHA256)
    }

    @Test
    fun sha512SelectsTheRsaSha2Variant() {
        signAndVerify(SshAuthenticationApi.SHA512)
    }

    /** SHA-1 is the legacy `ssh-rsa` signature, still part of the contract. */
    @Test
    fun sha1SelectsTheLegacySshRsaVariant() {
        signAndVerify(SshAuthenticationApi.SHA1)
    }

    /**
     * Ed25519 is PureEdDSA: the hash argument is accepted for compatibility and
     * has no effect, so all six API selectors must produce the same signature.
     */
    @Test
    fun ed25519IgnoresTheRequestedHashAlgorithm() {
        val algorithm = publicKeyAlgorithm()
        assumeTrue("The selected SSH key is not Ed25519.", algorithm == SshAuthenticationApi.EDDSA)
        val publicKey = publicKey()
        val frames = SshOperation.API_HASH_ALGORITHMS.map { hash ->
            sign(hash).also { frame ->
                assertEquals(LocalCrypto.SSH_ED25519, frame.algorithm)
                if (LocalCrypto.canVerify(frame.algorithm)) {
                    assertTrue(
                        "The ${sshHashAlgorithmName(hash)} Ed25519 signature does not verify",
                        LocalCrypto.verify(publicKey, frame, CHALLENGE),
                    )
                }
            }
        }
        frames.drop(1).forEach { frame ->
            assertArrayEquals(
                "The ignored hash selector changed the Ed25519 signature",
                frames.first().signature,
                frame.signature,
            )
        }
    }

    @Test
    fun rsaRejectsHashAlgorithmsWithoutAnSshSignatureVariant() {
        val algorithm = publicKeyAlgorithm()
        assumeTrue("The selected SSH key is not RSA.", algorithm == SshAuthenticationApi.RSA)
        SshOperation.RSA_UNSUPPORTED_HASH_ALGORITHMS.forEach { hash ->
            provider
                .sshRunner()
                .runOnce(
                    SshRequestSpec(
                        operation = SshOperation.SIGN,
                        keyId = state.sshKeyId(),
                        challenge = CHALLENGE,
                        hashAlgorithm = hash,
                    ),
                )
                .assertSshError(
                    errorCode = SshAuthenticationApiError.INVALID_HASH_ALGORITHM,
                    messageContains = "does not support the requested hash algorithm",
                )
        }
    }

    private fun signAndVerify(hash: Int) {
        val algorithm = publicKeyAlgorithm()
        val frame = sign(hash)
        val expected = if (algorithm == SshAuthenticationApi.RSA) {
            LocalCrypto.expectedRsaAlgorithm(hash)
        } else {
            LocalCrypto.SSH_ED25519
        }
        assertEquals(
            "Wrong signature algorithm for ${sshHashAlgorithmName(hash)}",
            expected,
            frame.algorithm,
        )
        assumeTrue(
            "This API level cannot verify ${frame.algorithm}.",
            LocalCrypto.canVerify(frame.algorithm),
        )
        assertTrue(
            "The signature does not verify against the provider's own public key",
            LocalCrypto.verify(publicKey(), frame, CHALLENGE),
        )
    }

    private fun sign(hash: Int) = provider
        .sshRunner()
        .run(
            SshRequestSpec(
                operation = SshOperation.SIGN,
                keyId = state.sshKeyId(),
                challenge = CHALLENGE,
                hashAlgorithm = hash,
            ),
        )
        .requireSshSuccess()
        .let { SigningResponse(it).signature }
        .let {
            parseSshSignatureFrame(it)
                ?: failWithExchangeLog("The signature is not an RFC 4253 blob")
        }

    private fun publicKeyResponse(): PublicKeyResponse = PublicKeyResponse(
        provider
            .sshRunner()
            .run(SshRequestSpec(SshOperation.GET_PUBLIC_KEY, keyId = state.sshKeyId()))
            .requireSshSuccess(),
    )

    private fun publicKeyAlgorithm(): Int = publicKeyResponse().keyAlgorithm

    private fun publicKey(): ByteArray = publicKeyResponse().encodedPublicKey

    private companion object {
        val CHALLENGE = "keyguard cross-package ssh challenge".encodeToByteArray()
    }
}
