package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.service.crypto.GpgLegacyKeyFixtures
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GpgCertificateInspectorJvmTest {
    private val generator = GpgKeyGeneratorJvm()

    @Test
    fun `legacy V2 and V3 certificates are rejected`() {
        GpgLegacyKeyFixtures.versions.forEach { version ->
            assertNull(
                GpgCertificateInspectorJvm.inspect(
                    GpgLegacyKeyFixtures.publicRing(version),
                ),
            )
        }
    }

    @Test
    fun `certificate containing a legacy subkey is rejected`() {
        val primary = publicRing(generate("Modern <modern@test.invalid>")).publicKey
        val mixedRing = PGPPublicKeyRing(
            listOf(
                primary,
                GpgLegacyKeyFixtures.publicSubkey(version = 3),
            ),
        )

        assertNull(GpgCertificateInspectorJvm.inspect(mixedRing))
        assertFailsWith<GpgUnsupportedKeyVersionException> {
            parseGpgPublicKeyRingCollection(mixedRing.armored())
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `designated revoker authenticates primary and subkey revocations when its key is supplied`() {
        val victim = generate("Victim <victim@test.invalid>")
        val revoker = generate("Revoker <revoker@test.invalid>")
        val victimRing = publicRing(victim)
        val victimSecretRing = secretRing(victim)
        val victimPrimary = victimRing.publicKey
        val victimSubkey = victimRing.publicKeys.asSequence().first { !it.isMasterKey }
        val revokerPrimary = publicRing(revoker).publicKey
        val revokerPrivate = secretRing(revoker).secretKey.extractPrivateKeyEmptyPassphrase()

        val authorization = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = victimSecretRing.secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setRevocationKey(
                        false,
                        revokerPrimary.algorithm,
                        revokerPrimary.fingerprint,
                    )
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val primaryRevocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = revokerPrivate,
        ).generateCertification(victimPrimary)
        val subkeyRevocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.SUBKEY_REVOCATION,
            privateKey = revokerPrivate,
        ).generateCertification(victimPrimary, victimSubkey)

        val primaryWithSignatures = PGPPublicKey.addCertification(
            PGPPublicKey.addCertification(victimPrimary, authorization),
            primaryRevocation,
        )
        val subkeyWithRevocation = PGPPublicKey.addCertification(victimSubkey, subkeyRevocation)
        var revokedRing = PGPPublicKeyRing.insertPublicKey(victimRing, primaryWithSignatures)
        revokedRing = PGPPublicKeyRing.insertPublicKey(revokedRing, subkeyWithRevocation)

        val authorizationOnlyRing = PGPPublicKeyRing.insertPublicKey(
            victimRing,
            PGPPublicKey.addCertification(victimPrimary, authorization),
        )
        val authorizationOnly = assertNotNull(
            GpgCertificateInspectorJvm.inspect(authorizationOnlyRing),
        )
        assertEquals(GpgRevocationStatusJvm.NotRevoked, authorizationOnly.primary.revocationStatus)

        val unresolved = assertNotNull(GpgCertificateInspectorJvm.inspect(revokedRing))
        assertFalse(unresolved.primary.revoked)
        assertIs<GpgRevocationStatusJvm.Unresolved>(unresolved.primary.revocationStatus)
        assertFalse(
            unresolved.subkeys.single {
                it.publicKey.fingerprint.contentEquals(victimSubkey.fingerprint)
            }.revoked,
        )
        assertIs<GpgRevocationStatusJvm.Unresolved>(
            unresolved.subkeys.single {
                it.publicKey.fingerprint.contentEquals(victimSubkey.fingerprint)
            }.revocationStatus,
        )

        val resolved = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = revokedRing,
                candidateRevocationKeys = listOf(revokerPrimary),
            ),
        )
        assertTrue(resolved.primary.revoked)
        assertEquals(GpgRevocationStatusJvm.Revoked, resolved.primary.revocationStatus)
        assertTrue(
            resolved.subkeys.single {
                it.publicKey.fingerprint.contentEquals(victimSubkey.fingerprint)
            }.revoked,
        )

        val unresolvedMetadata = assertNotNull(
            GpgKeyMetadataResolverJvm().resolve(
                privateKeyArmored = null,
                publicKeyArmored = revokedRing.armored(),
                fingerprint = victim.fingerprint,
            ),
        )
        assertTrue(
            unresolvedMetadata.keys.any { key ->
                key.fingerprint == victimSubkey.fingerprintHex()
            },
        )
        val resolvedMetadata = assertNotNull(
            GpgKeyMetadataResolverJvm().resolve(
                privateKeyArmored = null,
                publicKeyArmored = revokedRing.armored(),
                fingerprint = victim.fingerprint,
                candidateRevocationKeys = listOf(
                    GpgOpenPgpPublicKey(revoker.publicKeyArmored),
                ),
            ),
        )
        assertFalse(
            resolvedMetadata.keys.any { key ->
                key.fingerprint == victimSubkey.fingerprintHex()
            },
        )
    }

    @Test
    @Suppress("DEPRECATION")
    fun `user id self signature cannot authorize a designated revoker`() {
        val victim = generate("Victim <victim@test.invalid>")
        val revoker = generate("Revoker <revoker@test.invalid>")
        val victimRing = publicRing(victim)
        val victimSecretRing = secretRing(victim)
        val victimPrimary = victimRing.publicKey
        val rawUserId = victimPrimary.rawUserIDs.asSequence().single()
        val revokerPrimary = publicRing(revoker).publicKey

        val authorization = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.POSITIVE_CERTIFICATION,
            privateKey = victimSecretRing.secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setRevocationKey(
                        false,
                        revokerPrimary.algorithm,
                        revokerPrimary.fingerprint,
                    )
                }.generate(),
            )
        }.generateCertification(rawUserId.decodeToString(), victimPrimary)
        val primaryRevocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = secretRing(revoker).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(victimPrimary)
        val primaryWithSignatures = PGPPublicKey.addCertification(
            PGPPublicKey.addCertification(victimPrimary, rawUserId, authorization),
            primaryRevocation,
        )
        val revokedRing = PGPPublicKeyRing.insertPublicKey(victimRing, primaryWithSignatures)

        val inspected = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = revokedRing,
                candidateRevocationKeys = listOf(revokerPrimary),
            ),
        )
        assertFalse(inspected.primary.revoked)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `spoofed issuer hint cannot impersonate a declared revoker`() {
        val victim = generate("Victim <victim@test.invalid>")
        val revoker = generate("Revoker <revoker@test.invalid>")
        val foreign = generate("Foreign <foreign@test.invalid>")
        val victimRing = publicRing(victim)
        val victimPrimary = victimRing.publicKey
        val revokerPrimary = publicRing(revoker).publicKey
        val foreignPrimary = publicRing(foreign).publicKey

        val authorization = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = secretRing(victim).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setRevocationKey(
                        false,
                        revokerPrimary.algorithm,
                        revokerPrimary.fingerprint,
                    )
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val forgedRevocation = signatureGenerator(
            signingKey = foreignPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = secretRing(foreign).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setIssuerFingerprint(false, revokerPrimary)
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val primaryWithSignatures = PGPPublicKey.addCertification(
            PGPPublicKey.addCertification(victimPrimary, authorization),
            forgedRevocation,
        )
        val poisonedRing = PGPPublicKeyRing.insertPublicKey(victimRing, primaryWithSignatures)

        val unresolved = assertNotNull(GpgCertificateInspectorJvm.inspect(poisonedRing))
        assertFalse(unresolved.primary.revoked)
        assertIs<GpgRevocationStatusJvm.Unresolved>(unresolved.primary.revocationStatus)

        val inspected = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = poisonedRing,
                candidateRevocationKeys = listOf(revokerPrimary, foreignPrimary),
            ),
        )
        assertFalse(inspected.primary.revoked)
        assertEquals(GpgRevocationStatusJvm.NotRevoked, inspected.primary.revocationStatus)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `missing revoker stays unresolved with no hint or a conflicting fingerprint`() {
        val victim = generate("Victim <victim@test.invalid>")
        val revoker = generate("Revoker <revoker@test.invalid>")
        val foreign = generate("Foreign <foreign@test.invalid>")
        val victimRing = publicRing(victim)
        val victimPrimary = victimRing.publicKey
        val revokerPrimary = publicRing(revoker).publicKey
        val revokerPrivate = secretRing(revoker).secretKey.extractPrivateKeyEmptyPassphrase()
        val authorization = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = secretRing(victim).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setRevocationKey(
                        false,
                        revokerPrimary.algorithm,
                        revokerPrimary.fingerprint,
                    )
                }.generate(),
            )
        }.generateCertification(victimPrimary)

        val noHintRevocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = revokerPrivate,
        ).apply {
            setUnhashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setIssuerKeyID(false, 0L)
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val noHintRing = PGPPublicKeyRing.insertPublicKey(
            victimRing,
            PGPPublicKey.addCertification(
                PGPPublicKey.addCertification(victimPrimary, authorization),
                noHintRevocation,
            ),
        )
        val noHintUnresolved = assertNotNull(GpgCertificateInspectorJvm.inspect(noHintRing))
        assertIs<GpgRevocationStatusJvm.Unresolved>(
            noHintUnresolved.primary.revocationStatus,
        )

        val conflictingFingerprintRevocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = revokerPrivate,
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setIssuerFingerprint(false, publicRing(foreign).publicKey)
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val conflictingFingerprintRing = PGPPublicKeyRing.insertPublicKey(
            victimRing,
            PGPPublicKey.addCertification(
                PGPPublicKey.addCertification(victimPrimary, authorization),
                conflictingFingerprintRevocation,
            ),
        )
        val conflictingUnresolved = assertNotNull(
            GpgCertificateInspectorJvm.inspect(conflictingFingerprintRing),
        )
        assertIs<GpgRevocationStatusJvm.Unresolved>(
            conflictingUnresolved.primary.revocationStatus,
        )

        listOf(noHintRing, conflictingFingerprintRing).forEach { ring ->
            val resolved = assertNotNull(
                GpgCertificateInspectorJvm.inspect(
                    ring = ring,
                    candidateRevocationKeys = listOf(revokerPrimary),
                ),
            )
            assertEquals(GpgRevocationStatusJvm.Revoked, resolved.primary.revocationStatus)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `designated revoker certification revocation remains supported`() {
        val victim = generate("Victim <victim@test.invalid>")
        val revoker = generate("Revoker <revoker@test.invalid>")
        val victimRing = publicRing(victim)
        val victimPrimary = victimRing.publicKey
        val rawUserId = victimPrimary.rawUserIDs.asSequence().single()
        val revokerPrimary = publicRing(revoker).publicKey

        val authorization = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = secretRing(victim).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setRevocationKey(
                        false,
                        revokerPrimary.algorithm,
                        revokerPrimary.fingerprint,
                    )
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val certificationRevocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.CERTIFICATION_REVOCATION,
            privateKey = secretRing(revoker).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(rawUserId.decodeToString(), victimPrimary)
        var primaryWithSignatures = PGPPublicKey.addCertification(victimPrimary, authorization)
        primaryWithSignatures = PGPPublicKey.addCertification(
            primaryWithSignatures,
            rawUserId,
            certificationRevocation,
        )
        val revokedRing = PGPPublicKeyRing.insertPublicKey(victimRing, primaryWithSignatures)

        val unresolved = assertNotNull(GpgCertificateInspectorJvm.inspect(revokedRing))
        assertIs<GpgRevocationStatusJvm.Unresolved>(
            unresolved.userIdRevocationStatus(rawUserId),
        )

        val resolved = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = revokedRing,
                candidateRevocationKeys = listOf(revokerPrimary),
            ),
        )
        assertEquals(
            GpgRevocationStatusJvm.Revoked,
            resolved.userIdRevocationStatus(rawUserId),
        )
        assertFalse(rawUserId.decodeToString() in resolved.verifiedUserIds)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `public parser resolves a designated revoker from another ring in the collection`() {
        val victim = generate("Victim <victim@test.invalid>")
        val revoker = generate("Revoker <revoker@test.invalid>")
        val victimRing = publicRing(victim)
        val victimPrimary = victimRing.publicKey
        val revokerRing = publicRing(revoker)
        val revokerPrimary = revokerRing.publicKey

        val authorization = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = secretRing(victim).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setRevocationKey(
                        false,
                        revokerPrimary.algorithm,
                        revokerPrimary.fingerprint,
                    )
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val revocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = secretRing(revoker).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(victimPrimary)
        val primaryWithSignatures = PGPPublicKey.addCertification(
            PGPPublicKey.addCertification(victimPrimary, authorization),
            revocation,
        )
        val revokedRing = PGPPublicKeyRing.insertPublicKey(victimRing, primaryWithSignatures)

        val result = GpgPublicKeyParserJvm().parse(
            armoredCollection(revokedRing, revokerRing),
        )

        assertTrue(result is GpgPublicKeyParseResult.Success)
        val parsedVictim = result.keys.single { key -> key.fingerprint == victim.fingerprint }
        assertTrue(parsedVictim.revoked)
        assertFalse(parsedVictim.canEncrypt)
    }

    @Test
    fun `forged primary and subkey revocations are ignored`() {
        val victim = generate("Victim <victim@test.invalid>")
        val foreign = generate("Foreign <foreign@test.invalid>")
        val victimRing = publicRing(victim)
        val victimSecretRing = secretRing(victim)
        val victimPrimary = victimRing.publicKey
        val victimPrimaryPrivate = victimSecretRing.secretKey.extractPrivateKeyEmptyPassphrase()
        val foreignRing = publicRing(foreign)

        val invalidPrimaryRevocation = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = victimPrimaryPrivate,
        ).generateCertification(foreignRing.publicKey)
        val primaryWithForgedRevocation = PGPPublicKey.addCertification(
            victimPrimary,
            invalidPrimaryRevocation,
        )

        val victimSubkey = victimRing.publicKeys.asSequence().first { !it.isMasterKey }
        val foreignSubkey = foreignRing.publicKeys.asSequence().first { !it.isMasterKey }
        val invalidSubkeyRevocation = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.SUBKEY_REVOCATION,
            privateKey = victimPrimaryPrivate,
        ).generateCertification(victimPrimary, foreignSubkey)
        val subkeyWithForgedRevocation = PGPPublicKey.addCertification(
            victimSubkey,
            invalidSubkeyRevocation,
        )

        var poisonedRing = PGPPublicKeyRing.insertPublicKey(
            victimRing,
            primaryWithForgedRevocation,
        )
        poisonedRing = PGPPublicKeyRing.insertPublicKey(poisonedRing, subkeyWithForgedRevocation)

        assertTrue(primaryWithForgedRevocation.hasRevocation())
        assertTrue(subkeyWithForgedRevocation.hasRevocation())
        val inspected = assertNotNull(GpgCertificateInspectorJvm.inspect(poisonedRing))
        assertFalse(inspected.primary.revoked)
        assertFalse(
            inspected.subkeys.single {
                it.publicKey.fingerprint.contentEquals(victimSubkey.fingerprint)
            }.revoked,
        )
    }

    @Test
    fun `newer invalid self signature cannot replace authenticated expiry or key flags`() {
        val victim = generate("Victim <victim@test.invalid>")
        val victimRing = publicRing(victim)
        val victimSecretRing = secretRing(victim)
        val victimPrimary = victimRing.publicKey
        val rawUserId = victimPrimary.rawUserIDs.asSequence().single()
        val original = assertNotNull(GpgCertificateInspectorJvm.inspect(victimRing)).primary
        val originalEffectiveSignature = assertNotNull(original.effectiveSignature)

        val hashed = PGPSignatureSubpacketGenerator().apply {
            setSignatureCreationTime(
                true,
                Date(originalEffectiveSignature.creationTime.time + 86_400_000L),
            )
            setKeyExpirationTime(true, 60L)
            setKeyFlags(true, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
        }
        val invalidSelfSignature = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.POSITIVE_CERTIFICATION,
            privateKey = victimSecretRing.secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(hashed.generate())
        }.generateCertification("Different identity <other@test.invalid>", victimPrimary)
        val poisonedPrimary = PGPPublicKey.addCertification(
            victimPrimary,
            rawUserId,
            invalidSelfSignature,
        )
        val poisonedRing = PGPPublicKeyRing.insertPublicKey(victimRing, poisonedPrimary)

        assertFalse(invalidSelfSignature.verifiesUserIdCertification(rawUserId, victimPrimary))
        val inspected = assertNotNull(GpgCertificateInspectorJvm.inspect(poisonedRing)).primary
        assertEquals(original.validSeconds, inspected.validSeconds)
        assertEquals(original.keyFlags, inspected.keyFlags)
        assertTrue(
            originalEffectiveSignature.encoded.contentEquals(inspected.effectiveSignature?.encoded),
        )
    }

    @Test
    fun `expired direct key signature is ignored for effective policy`() {
        val generated = generate("Expired direct <expired-direct@test.invalid>")
        val ring = publicRing(generated)
        val primary = ring.publicKey
        val primaryPrivate = secretRing(generated).secretKey.extractPrivateKeyEmptyPassphrase()
        val original = assertNotNull(GpgCertificateInspectorJvm.inspect(ring)).primary
        val originalEffectiveSignature = assertNotNull(original.effectiveSignature)
        val expiredCreationSeconds = originalEffectiveSignature.creationTime.time / 1_000L + 1L
        val expiredDirectSignature = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = primaryPrivate,
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setSignatureCreationTime(true, Date(expiredCreationSeconds * 1_000L))
                    setSignatureExpirationTime(true, 1L)
                    setKeyExpirationTime(true, 60L)
                    setKeyFlags(true, KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE)
                }.generate(),
            )
        }.generateCertification(primary)
        val poisonedRing = PGPPublicKeyRing.insertPublicKey(
            ring,
            PGPPublicKey.addCertification(primary, expiredDirectSignature),
        )
        val referenceTime = Instant.fromEpochSeconds(expiredCreationSeconds + 1L)

        assertTrue(expiredDirectSignature.verifiesDirectKeyCertification(primary))
        val baseline = assertNotNull(
            GpgCertificateInspectorJvm.inspect(ring, referenceTime = referenceTime),
        ).primary
        val inspected = assertNotNull(
            GpgCertificateInspectorJvm.inspect(poisonedRing, referenceTime = referenceTime),
        )
        assertTrue(
            inspected.verifiedDirectKeySignatures().any { signature ->
                signature.encoded.contentEquals(expiredDirectSignature.encoded)
            },
        )
        assertFalse(
            inspected.effectiveDirectKeySignature()
                ?.encoded
                ?.contentEquals(expiredDirectSignature.encoded) == true,
        )
        assertTrue(
            baseline.effectiveSignature?.encoded.contentEquals(
                inspected.primary.effectiveSignature?.encoded,
            ),
        )
        assertEquals(baseline.keyFlags, inspected.primary.keyFlags)
        assertEquals(baseline.validSeconds, inspected.primary.validSeconds)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `expired direct key signature still authorizes its designated revoker`() {
        val victim = generate("Expired authorization <expired-authorization@test.invalid>")
        val revoker = generate("Expired authorization revoker <revoker@test.invalid>")
        val victimRing = publicRing(victim)
        val victimPrimary = victimRing.publicKey
        val revokerPrimary = publicRing(revoker).publicKey
        val originalEffectiveSignature = assertNotNull(
            GpgCertificateInspectorJvm.inspect(victimRing),
        ).primary.effectiveSignature
        val authorizationCreationSeconds =
            assertNotNull(originalEffectiveSignature).creationTime.time / 1_000L + 1L
        val authorization = signatureGenerator(
            signingKey = victimPrimary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = secretRing(victim).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setSignatureCreationTime(true, Date(authorizationCreationSeconds * 1_000L))
                    setSignatureExpirationTime(true, 1L)
                    setRevocationKey(
                        false,
                        revokerPrimary.algorithm,
                        revokerPrimary.fingerprint,
                    )
                }.generate(),
            )
        }.generateCertification(victimPrimary)
        val revocation = signatureGenerator(
            signingKey = revokerPrimary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = secretRing(revoker).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(victimPrimary)
        val revokedRing = PGPPublicKeyRing.insertPublicKey(
            victimRing,
            PGPPublicKey.addCertification(
                PGPPublicKey.addCertification(victimPrimary, authorization),
                revocation,
            ),
        )
        val inspected = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = revokedRing,
                candidateRevocationKeys = listOf(revokerPrimary),
                referenceTime = Instant.fromEpochSeconds(authorizationCreationSeconds + 1L),
            ),
        )

        assertFalse(
            inspected.effectiveDirectKeySignature()
                ?.encoded
                ?.contentEquals(authorization.encoded) == true,
        )
        assertTrue(inspected.primary.revoked)
    }

    @Test
    fun `newer expired user id certification shadows an older live certification`() {
        val generated = generate("Expired identity <expired-identity@test.invalid>")
        val ring = publicRing(generated)
        val primary = ring.publicKey
        val rawUserId = primary.rawUserIDs.asSequence().single()
        val originalCertification = assertNotNull(
            GpgCertificateInspectorJvm.inspect(ring),
        ).verifiedUserIdCertifications(rawUserId).maxBy { signature ->
            signature.creationTime.time
        }
        val expiredCreationSeconds = originalCertification.creationTime.time / 1_000L + 1L
        val expiredCertification = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.POSITIVE_CERTIFICATION,
            privateKey = secretRing(generated).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setSignatureCreationTime(true, Date(expiredCreationSeconds * 1_000L))
                    setSignatureExpirationTime(true, 1L)
                }.generate(),
            )
        }.generateCertification(rawUserId.decodeToString(), primary)
        val poisonedRing = PGPPublicKeyRing.insertPublicKey(
            ring,
            PGPPublicKey.addCertification(primary, rawUserId, expiredCertification),
        )

        assertTrue(expiredCertification.verifiesUserIdCertification(rawUserId, primary))
        val beforeExpiration = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = poisonedRing,
                referenceTime = Instant.fromEpochSeconds(expiredCreationSeconds),
            ),
        )
        assertTrue(
            expiredCertification.encoded.contentEquals(
                beforeExpiration.effectiveUserIdCertification(rawUserId)?.encoded,
            ),
        )

        val atExpiration = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = poisonedRing,
                referenceTime = Instant.fromEpochSeconds(expiredCreationSeconds + 1L),
            ),
        )
        assertTrue(
            atExpiration.verifiedUserIdCertifications(rawUserId).any { signature ->
                signature.encoded.contentEquals(expiredCertification.encoded)
            },
        )
        assertNull(atExpiration.effectiveUserIdCertification(rawUserId))
        assertFalse(rawUserId.decodeToString() in atExpiration.verifiedUserIds)
    }

    @Test
    fun `expired subkey binding falls back to an older live binding`() {
        val generated = generate("Expired binding <expired-binding@test.invalid>")
        val ring = publicRing(generated)
        val primary = ring.publicKey
        val inspected = assertNotNull(GpgCertificateInspectorJvm.inspect(ring))
        val subkey = inspected.subkeys.first().publicKey
        val originalBinding = assertNotNull(
            inspected.subkeys.first { key ->
                key.publicKey.fingerprint.contentEquals(subkey.fingerprint)
            }.effectiveSignature,
        )
        val expiredCreationSeconds = originalBinding.creationTime.time / 1_000L + 1L
        val expiredBinding = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.SUBKEY_BINDING,
            privateKey = secretRing(generated).secretKey.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            setHashedSubpackets(
                PGPSignatureSubpacketGenerator().apply {
                    setSignatureCreationTime(true, Date(expiredCreationSeconds * 1_000L))
                    setSignatureExpirationTime(true, 1L)
                }.generate(),
            )
        }.generateCertification(primary, subkey)
        val subkeyWithExpiredBinding = PGPPublicKey.addCertification(subkey, expiredBinding)
        val poisonedRing = PGPPublicKeyRing.insertPublicKey(ring, subkeyWithExpiredBinding)
        val referenceTime = Instant.fromEpochSeconds(expiredCreationSeconds + 1L)

        assertTrue(expiredBinding.verifiesSubkeyCertification(primary, subkey))
        val withFallback = assertNotNull(
            GpgCertificateInspectorJvm.inspect(poisonedRing, referenceTime = referenceTime),
        ).subkeys.first { key -> key.publicKey.fingerprint.contentEquals(subkey.fingerprint) }
        assertTrue(withFallback.authenticated)
        assertTrue(originalBinding.encoded.contentEquals(withFallback.effectiveSignature?.encoded))

        var expiredOnlySubkey = subkey
        inspected.verifiedSubkeyBindings(subkey).forEach { binding ->
            expiredOnlySubkey = PGPPublicKey.removeCertification(expiredOnlySubkey, binding)
        }
        expiredOnlySubkey = PGPPublicKey.addCertification(expiredOnlySubkey, expiredBinding)
        val expiredOnlyRing = PGPPublicKeyRing.insertPublicKey(ring, expiredOnlySubkey)
        val expiredOnly = assertNotNull(
            GpgCertificateInspectorJvm.inspect(expiredOnlyRing, referenceTime = referenceTime),
        ).subkeys.first { key -> key.publicKey.fingerprint.contentEquals(subkey.fingerprint) }
        assertFalse(expiredOnly.authenticated)
        assertNull(expiredOnly.effectiveSignature)
    }

    @Test
    fun `explicit authenticated key flags override algorithm capabilities`() {
        val generated = generator.generate(
            GpgKeyConfig.Rsa(
                userId = "RSA flags <rsa-flags@test.invalid>",
                length = GpgKeyConfig.RsaLength.B3072,
            ),
        )
        val inspected = assertNotNull(
            GpgCertificateInspectorJvm.inspect(publicRing(generated)),
        )
        val primaryFlags = assertNotNull(inspected.primary.keyFlags)
        assertEquals(0, primaryFlags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE))

        val metadata = assertNotNull(
            GpgKeyMetadataResolverJvm().resolve(
                privateKeyArmored = null,
                publicKeyArmored = generated.publicKeyArmored,
                fingerprint = generated.fingerprint,
            ),
        )
        val primaryMetadata = metadata.keys.single { it.fingerprint == generated.fingerprint }
        assertFalse("decrypt" in primaryMetadata.capabilities)
    }

    @Test
    fun `bare primary is not treated as an authenticated agent key`() {
        val generated = generate("Bare <bare@test.invalid>")
        val certificate = publicRing(generated)
        var barePrimary = certificate.publicKey
        barePrimary.rawUserIDs.asSequence().toList().forEach { rawUserId ->
            barePrimary = PGPPublicKey.removeCertification(barePrimary, rawUserId)
        }
        barePrimary.keySignatures.asSequence().toList().forEach { signature ->
            barePrimary = PGPPublicKey.removeCertification(barePrimary, signature)
        }
        val bareCertificate = PGPPublicKeyRing(listOf(barePrimary))

        val inspected = assertNotNull(GpgCertificateInspectorJvm.inspect(bareCertificate))
        assertFalse(inspected.primary.authenticated)
        assertTrue(inspected.authenticatedKeys.isEmpty())
        assertNull(
            GpgKeyMetadataResolverJvm().resolve(
                privateKeyArmored = null,
                publicKeyArmored = bareCertificate.armored(),
                fingerprint = barePrimary.fingerprintHex(),
            ),
        )
    }

    private fun generate(
        userId: String,
    ): GeneratedGpgKey = generator.generate(
        GpgKeyConfig.Modern(userId = userId),
    )

    private fun publicRing(
        generated: GeneratedGpgKey,
    ): PGPPublicKeyRing = parseGpgPublicKeyRingCollection(generated.publicKeyArmored)
        .keyRings
        .next()

    private fun secretRing(
        generated: GeneratedGpgKey,
    ): PGPSecretKeyRing = parseGpgSecretKeyRingCollection(generated.privateKeyArmored)
        .keyRings
        .next()

    private fun armoredCollection(
        vararg rings: PGPPublicKeyRing,
    ): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            rings.forEach { ring -> ring.encode(armoredOut) }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun signatureGenerator(
        signingKey: PGPPublicKey,
        signatureType: Int,
        privateKey: PGPPrivateKey,
    ): PGPSignatureGenerator = PGPSignatureGenerator(
        JcaPGPContentSignerBuilder(signingKey.algorithm, HashAlgorithmTags.SHA256)
            .setProvider(gpgBouncyCastleProvider),
        signingKey,
    ).apply {
        init(signatureType, privateKey)
    }
}
