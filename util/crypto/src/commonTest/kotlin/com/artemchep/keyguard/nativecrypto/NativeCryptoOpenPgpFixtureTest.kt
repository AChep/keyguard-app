package com.artemchep.keyguard.nativecrypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixed GnuPG fixtures exercised through every real Kotlin/native bridge target. */
class NativeCryptoOpenPgpFixtureTest {
    @Test
    fun parsesAuthenticatedCertificateAndExactGnuPgKeygrips() {
        val result = NativeCrypto.openPgp.parsePublicKeys(
            keyData = PUBLIC_KEY.encodeToByteArray(),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )

        val key = assertIs<NativeOpenPgpPublicKeyParseResult.Success>(result).keys.single()
        assertEquals(PRIMARY_FINGERPRINT, key.fingerprint)
        assertEquals(PRIMARY_KEYGRIP, key.keygrip)
        assertEquals("F83D947D29EFECF7", key.keyId)
        assertEquals("EDDSA", key.algorithm)
        assertEquals(256, key.bitStrength)
        assertEquals(listOf(USER_ID), key.userIds)
        assertEquals(listOf("cv25519@test.invalid"), key.emails)
        assertEquals(1_782_541_263L, key.createdAtEpochSeconds)
        assertNull(key.expiresAtEpochSeconds)
        assertFalse(key.revoked)
        assertTrue(key.canSign)
        assertTrue(key.canEncrypt)
        assertTrue(key.authenticated)
        assertEquals(NativeOpenPgpRenewalAuthorization.AUTHENTICATED, key.renewal)
        assertEquals(PUBLIC_KEY.trimEnd(), key.publicKeyArmored.trimEnd())

        val subkey = key.subkeys.single()
        assertEquals(SUBKEY_FINGERPRINT, subkey.fingerprint)
        assertEquals(SUBKEY_KEYGRIP, subkey.keygrip)
        assertEquals("77648D3E5D4E7699", subkey.keyId)
        assertEquals("ECDH", subkey.algorithm)
        assertEquals(256, subkey.bitStrength)
        assertFalse(subkey.canSign)
        assertTrue(subkey.canEncrypt)
        assertFalse(subkey.revoked)
        assertTrue(subkey.authenticated)
        assertEquals(1_782_541_292L, subkey.createdAtEpochSeconds)
        assertNull(subkey.expiresAtEpochSeconds)
    }

    @Test
    fun resolvesCanonicalMetadataFromSecretOrPublicFallback() {
        val metadata = NativeCrypto.openPgp.resolveMetadata(
            privateKeyData = SECRET_KEY.encodeToByteArray(),
            publicKeyData = null,
            normalizedFingerprint = SUBKEY_FINGERPRINT.lowercase(),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )

        requireNotNull(metadata)
        val certificate = metadata.certificates.single()
        assertEquals(PRIMARY_FINGERPRINT, certificate.index.primaryFingerprint)
        assertEquals(
            listOf(PRIMARY_FINGERPRINT, SUBKEY_FINGERPRINT),
            certificate.index.components.map { component -> component.fingerprint },
        )
        assertTrue(certificate.index.components.all { component -> component.storedSecretMaterial })
        assertEquals(
            setOf(NativeOpenPgpPolicyUse.SIGN_NEW_DATA),
            certificate.policy.single { it.fingerprint == PRIMARY_FINGERPRINT }.allowedNewDataUses,
        )
        assertEquals(
            setOf(NativeOpenPgpPolicyUse.ENCRYPT_NEW_DATA),
            certificate.policy.single { it.fingerprint == SUBKEY_FINGERPRINT }.allowedNewDataUses,
        )
        assertTrue(
            certificate.policy.all { policy ->
                policy.renewal == NativeOpenPgpRenewalAuthorization.AUTHENTICATED
            },
        )

        val fallback = NativeCrypto.openPgp.resolveMetadata(
            privateKeyData = "malformed private key".encodeToByteArray(),
            publicKeyData = PUBLIC_KEY.encodeToByteArray(),
            normalizedFingerprint = PRIMARY_FINGERPRINT,
            candidateRevocationKeys = listOf("ignored malformed candidate".encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        requireNotNull(fallback)
        assertEquals(certificate.policy, fallback.certificates.single().policy)
        assertTrue(
            fallback.certificates.single().index.components.none { component ->
                component.storedSecretMaterial
            },
        )
    }

    @Test
    fun verifiesFixedClearAndDetachedSignatures() {
        val publicKeys = listOf(PUBLIC_KEY.encodeToByteArray())
        val clear = NativeCrypto.openPgp.verifyClearSigned(
            signedDocument = CLEAR_SIGNED.encodeToByteArray(),
            publicKeys = publicKeys,
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        assertVerification(clear, createdAtEpochSeconds = 1_784_073_600L)

        val detached = NativeCrypto.openPgp.verifyDetached(
            content = DETACHED_BODY.encodeToByteArray(),
            signature = DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = publicKeys,
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        assertVerification(detached, createdAtEpochSeconds = 1_784_073_600L)

        val missing = NativeCrypto.openPgp.verifyDetached(
            content = DETACHED_BODY.encodeToByteArray(),
            signature = DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = emptyList(),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        assertEquals(NativeOpenPgpVerificationStatus.MISSING_PUBLIC_KEY, missing.status)
        assertEquals("F83D947D29EFECF7", missing.keyId)
        assertNull(missing.fingerprint)
        assertTrue(missing.userIds.isEmpty())
        assertEquals(1_784_073_600L, missing.createdAtEpochSeconds)
        assertTrue(missing.warnings.isEmpty())
    }

    @Test
    fun tiedDirectKeyPolicyDoesNotOverridePrimaryUserIdPolicy() {
        // This certificate carries two equally recent direct-key policy signatures that
        // project different key flags. Sequoia-compatible ordering picks a canonical
        // representative, while the V4 primary User ID remains authoritative for fields
        // it carries. Its signing flag therefore permits this data signature and the tie
        // produces no POLICY_CONFLICT warning.
        val result = NativeCrypto.openPgp.verifyDetached(
            content = DETACHED_BODY.encodeToByteArray(),
            signature = DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = listOf(POLICY_CONFLICT_PUBLIC_KEY.encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )

        assertEquals(NativeOpenPgpVerificationStatus.VALID, result.status)
        assertEquals("F83D947D29EFECF7", result.keyId)
        assertEquals(PRIMARY_FINGERPRINT, result.fingerprint)
        assertEquals(listOf(USER_ID), result.userIds)
        assertEquals(1_784_073_600L, result.createdAtEpochSeconds)
        // POLICY_CONFLICT is now reserved for unresolved revocation authority, which
        // this certificate does not have.
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun detachedVerificationStreamsAcrossArbitraryChunkBoundaries() {
        for (chunkSize in listOf(1, 7, 31, 64 * 1024)) {
            val session = NativeCrypto.openPgp.openDetachedVerification(
                signature = DETACHED_SIGNATURE.encodeToByteArray(),
                publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
                referenceTimeEpochSeconds = REFERENCE_TIME,
            )
            session.use {
                val body = DETACHED_BODY.encodeToByteArray()
                var offset = 0
                while (offset < body.size) {
                    val length = minOf(chunkSize, body.size - offset)
                    it.update(body, offset = offset, length = length)
                    offset += length
                }
                assertVerification(it.finish(), createdAtEpochSeconds = 1_784_073_600L)
            }
        }
    }

    @Test
    fun signedOnlyModeRejectsUnsignedLiteralPacket() {
        val plaintext = "unsigned literal payload".encodeToByteArray()
        val literalBody = byteArrayOf(
            'b'.code.toByte(),
            0, // Empty file name.
            0, 0, 0, 0, // Modification time.
        ) + plaintext
        val packet = byteArrayOf(
            0xcb.toByte(), // New-format literal-data packet tag.
            literalBody.size.toByte(),
        ) + literalBody

        val failure = assertFailsWith<NativeCryptoException> {
            NativeCrypto.openPgp.decrypt(
                content = packet,
                privateKeys = emptyList(),
                allowSignedOnly = true,
            )
        }

        assertEquals(NativeCryptoErrorCode.INVALID_ARGUMENT, failure.code)
    }

    @Test
    fun clearVerificationStreamsAcrossArbitraryChunkBoundaries() {
        val expectedBody = "OpenPGP clear text\n- dash-prefixed line\nfinal line"
        for (chunkSize in listOf(1, 7, 31, 64 * 1024)) {
            val session = NativeCrypto.openPgp.openClearVerification(
                publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
                referenceTimeEpochSeconds = REFERENCE_TIME,
            )
            session.use {
                val document = CLEAR_SIGNED.encodeToByteArray()
                val body = StringBuilder()
                var offset = 0
                while (offset < document.size) {
                    val length = minOf(chunkSize, document.size - offset)
                    body.append(it.update(document, offset = offset, length = length).decodeToString())
                    offset += length
                }
                val result = it.finish()
                assertEquals(expectedBody, body.toString())
                assertVerification(result.verification, createdAtEpochSeconds = 1_784_073_600L)
                assertTrue(result.bodyValidUtf8)
            }
        }
    }

    @Test
    fun streamedClearVerificationTamperIsRejectedAtFinishAndConsumesTheSession() {
        val session = NativeCrypto.openPgp.openClearVerification(
            publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        session.use {
            val tampered = CLEAR_SIGNED
                .replace("OpenPGP clear text", "OpenPGP sneak text")
                .encodeToByteArray()
            it.update(tampered)
            val result = it.finish()
            assertEquals(NativeOpenPgpVerificationStatus.INVALID, result.verification.status)

            val finishReuse = assertFailsWith<NativeCryptoException> { it.finish() }
            assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
            val updateReuse = assertFailsWith<NativeCryptoException> { it.update(byteArrayOf(0)) }
            assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
        }
    }

    @Test
    fun closingAPartialClearVerificationSessionCancelsItAndIsIdempotent() {
        val session = NativeCrypto.openPgp.openClearVerification(
            publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        session.update(CLEAR_SIGNED.encodeToByteArray(), length = 7)

        session.close()
        session.close()

        val updateReuse = assertFailsWith<NativeCryptoException> { session.update(byteArrayOf(0)) }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
        val finishReuse = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
    }

    @Test
    fun streamedTamperIsRejectedAtFinishAndConsumesTheSession() {
        val session = NativeCrypto.openPgp.openDetachedVerification(
            signature = DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        session.use {
            val tampered = DETACHED_BODY.encodeToByteArray().also { body ->
                body[0] = 'X'.code.toByte()
            }
            it.update(tampered)
            val verification = it.finish()
            assertEquals(NativeOpenPgpVerificationStatus.INVALID, verification.status)

            val finishReuse = assertFailsWith<NativeCryptoException> { it.finish() }
            assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
            val updateReuse = assertFailsWith<NativeCryptoException> { it.update(byteArrayOf(0)) }
            assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
        }
    }

    @Test
    fun closingAPartialVerificationSessionCancelsItAndIsIdempotent() {
        val session = NativeCrypto.openPgp.openDetachedVerification(
            signature = DETACHED_SIGNATURE.encodeToByteArray(),
            publicKeys = listOf(PUBLIC_KEY.encodeToByteArray()),
            referenceTimeEpochSeconds = REFERENCE_TIME,
        )
        session.update(DETACHED_BODY.encodeToByteArray(), length = 7)

        session.close()
        session.close()

        val updateReuse = assertFailsWith<NativeCryptoException> { session.update(byteArrayOf(0)) }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, updateReuse.code)
        val finishReuse = assertFailsWith<NativeCryptoException> { session.finish() }
        assertEquals(NativeCryptoErrorCode.INVALID_SESSION, finishReuse.code)
    }

    private fun assertVerification(
        result: NativeOpenPgpVerification,
        createdAtEpochSeconds: Long,
    ) {
        assertEquals(NativeOpenPgpVerificationStatus.VALID, result.status)
        assertEquals("F83D947D29EFECF7", result.keyId)
        assertEquals(PRIMARY_FINGERPRINT, result.fingerprint)
        assertEquals(listOf(USER_ID), result.userIds)
        assertEquals(createdAtEpochSeconds, result.createdAtEpochSeconds)
        assertTrue(result.warnings.isEmpty())
    }

    private companion object {
        const val REFERENCE_TIME = 1_784_073_601L
        const val PRIMARY_FINGERPRINT = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7"
        const val PRIMARY_KEYGRIP = "894264A490F8D55E3E28378A7E44373782806220"
        const val SUBKEY_FINGERPRINT = "93ABCF804D85EE79D6E1DB0E77648D3E5D4E7699"
        const val SUBKEY_KEYGRIP = "85C1DE785BEE9244BAFBA73A09E6085BA7A35C8E"
        const val USER_ID = "Keyguard Test CV25519 <cv25519@test.invalid>"

        val PUBLIC_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            mDMEaj9rzxYJKwYBBAHaRw8BAQdAbF/WEPrIP6KKXMDvdC38qJefWOzgPjl1oRjO
            Zq0b1Q60LEtleWd1YXJkIFRlc3QgQ1YyNTUxOSA8Y3YyNTUxOUB0ZXN0LmludmFs
            aWQ+iK8EExYKAFcWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCaj9rzxsUgAAAAAAE
            AA5tYW51MiwyLjUrMS4xMiwwLDMCGwMFCwkIBwICIgIGFQoJCAsCBBYCAwECHgcC
            F4AACgkQ+D2UfSnv7PezOQD+JMrO7BD9rfc1ciIZoSW5NCw9N+8tkU8fOxKsdFQ+
            0DEA/iZ7e3W2CRUGtt8UTHwzBLZOlgn5Ox4O/49/6/Cn92gEuDgEaj9r7BIKKwYB
            BAGXVQEFAQEHQFzTFZW3PHTv8qstyY8CdxMH7TZJnkpIutnhRc7xun12AwEIB4iU
            BBgWCgA8FiEE0LvPuyUNO7BljlOE+D2UfSnv7PcFAmo/a+wbFIAAAAAABAAObWFu
            dTIsMi41KzEuMTIsMCwzAhsMAAoJEPg9lH0p7+z3LpQA/09tlKbt7+j26p+QwbCs
            bu8oruCxbNY45226eyy6QxS9AQC6cwXPn1NewS7XjGGKea14CgjpvqstWe9PiyfJ
            Y7c+CA==
            =Kf2G
            -----END PGP PUBLIC KEY BLOCK-----
        """.trimIndent() + "\n"

        val POLICY_CONFLICT_PUBLIC_KEY = """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            mDMEaj9rzxYJKwYBBAHaRw8BAQdAbF/WEPrIP6KKXMDvdC38qJefWOzgPjl1oRjO
            Zq0b1Q7CbgQfFggAIAUCalTToxYhBNC7z7slDTuwZY5ThPg9lH0p7+z3AhsCAAAF
            3QD+J0BmEGkteEFvEL6+NcoMXJPA9f2dgASrhMOEx49GzncA/jKiGLIgwVV1Mn+5
            EEa+0emPNE8jV8Lr5dVrrCvImnUCwm4EHxYIACAFAmpU06MWIQTQu8+7JQ07sGWO
            U4T4PZR9Ke/s9wIbBAAAY1UA/03TajlkBrd4t80f2wUwFDP5W6yIWz5N62ysR85z
            K20+AP9Al5wCbGLEhCslgKdx+WrYdXUufex7d8/9AGp7ukRrBLQsS2V5Z3VhcmQg
            VGVzdCBDVjI1NTE5IDxjdjI1NTE5QHRlc3QuaW52YWxpZD6IrwQTFgoAVxYhBNC7
            z7slDTuwZY5ThPg9lH0p7+z3BQJqP2vPGxSAAAAAAAQADm1hbnUyLDIuNSsxLjEy
            LDAsMwIbAwULCQgHAgIiAgYVCgkICwIEFgIDAQIeBwIXgAAKCRD4PZR9Ke/s97M5
            AP4kys7sEP2t9zVyIhmhJbk0LD037y2RTx87Eqx0VD7QMQD+Jnt7dbYJFQa23xRM
            fDMEtk6WCfk7Hg7/j3/r8Kf3aAS4OARqP2vsEgorBgEEAZdVAQUBAQdAXNMVlbc8
            dO/yqy3JjwJ3EwftNkmeSki62eFFzvG6fXYDAQgHiJQEGBYKADwWIQTQu8+7JQ07
            sGWOU4T4PZR9Ke/s9wUCaj9r7BsUgAAAAAAEAA5tYW51MiwyLjUrMS4xMiwwLDMC
            GwwACgkQ+D2UfSnv7PculAD/T22Upu3v6Pbqn5DBsKxu7yiu4LFs1jjnbbp7LLpD
            FL0BALpzBc+fU17BLteMYYp5rXgKCOm+qy1Z70+LJ8ljtz4I
            =yjR5
            -----END PGP PUBLIC KEY BLOCK-----
        """.trimIndent() + "\n"

        val SECRET_KEY = """
            -----BEGIN PGP PRIVATE KEY BLOCK-----

            lFgEaj9rzxYJKwYBBAHaRw8BAQdAbF/WEPrIP6KKXMDvdC38qJefWOzgPjl1oRjO
            Zq0b1Q4AAP416BYYjfvazxmhBWie0YPQHmRv5DtZABE+5Eo8vsGC8BB2tCxLZXln
            dWFyZCBUZXN0IENWMjU1MTkgPGN2MjU1MTlAdGVzdC5pbnZhbGlkPoivBBMWCgBX
            FiEE0LvPuyUNO7BljlOE+D2UfSnv7PcFAmo/a88bFIAAAAAABAAObWFudTIsMi41
            KzEuMTIsMCwzAhsDBQsJCAcCAiICBhUKCQgLAgQWAgMBAh4HAheAAAoJEPg9lH0p
            7+z3szkA/iTKzuwQ/a33NXIiGaEluTQsPTfvLZFPHzsSrHRUPtAxAP4me3t1tgkV
            BrbfFEx8MwS2TpYJ+TseDv+Pf+vwp/doBJxdBGo/a+wSCisGAQQBl1UBBQEBB0Bc
            0xWVtzx07/KrLcmPAncTB+02SZ5KSLrZ4UXO8bp9dgMBCAcAAP934N+JD9z0Gkm1
            ZSVtLdTx8gIrDriwen2vkSJLUzL+UBCqiJQEGBYKADwWIQTQu8+7JQ07sGWOU4T4
            PZR9Ke/s9wUCaj9r7BsUgAAAAAAEAA5tYW51MiwyLjUrMS4xMiwwLDMCGwwACgkQ
            +D2UfSnv7PculAD/T22Upu3v6Pbqn5DBsKxu7yiu4LFs1jjnbbp7LLpDFL0BALpz
            Bc+fU17BLteMYYp5rXgKCOm+qy1Z70+LJ8ljtz4I
            =s3tp
            -----END PGP PRIVATE KEY BLOCK-----
        """.trimIndent() + "\n"

        val CLEAR_SIGNED = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA512

            OpenPGP clear text
            - - dash-prefixed line
            final line
            -----BEGIN PGP SIGNATURE-----

            iJEEARYKADkWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCalbNgBsUgAAAAAAEAA5t
            YW51MiwyLjUrMS4xMiwwLDMACgkQ+D2UfSnv7Pc7jwD/YyEWGkxCl4ifICqQ8jvm
            OCocw7qEVGdice9yXQN+/XkBAOKJ3GLSrJswbH3m3xEpKWkIAMYxOhtf9pdiYyap
            0EIN
            =tFvV
            -----END PGP SIGNATURE-----
        """.trimIndent() + "\n"

        val DETACHED_BODY = """
            Independent OpenPGP verification fixture.
            Second line.
        """.trimIndent() + "\n"

        val DETACHED_SIGNATURE = """
            -----BEGIN PGP SIGNATURE-----

            iJEEABYKADkWIQTQu8+7JQ07sGWOU4T4PZR9Ke/s9wUCalbNgBsUgAAAAAAEAA5t
            YW51MiwyLjUrMS4xMiwwLDMACgkQ+D2UfSnv7Pe4sQEAowtp7N4njm4eBEi+bgC1
            VxGYWoE70RB//wCTrwaVtggBAL3MVySwcv/iU0y9pM+91TaerHhzhSNnDjcJTS4d
            SOEL
            =6B1K
            -----END PGP SIGNATURE-----
        """.trimIndent() + "\n"
    }
}
