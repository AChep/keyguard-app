package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.model.toGpgKeyMaterial
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMetadataResolution
import com.artemchep.keyguard.common.service.gpgagent.GpgRenewalAuthorization
import com.artemchep.keyguard.common.service.gpgagent.authorizedAgentKeys
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.crypto.GpgCertificateInspectorJvm
import com.artemchep.keyguard.crypto.GpgKeyExpirationServiceJvm
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgKeyExpirationService
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.crypto.GpgRevocationStatusJvm
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.extractPrivateKeyEmptyPassphrase
import com.artemchep.keyguard.crypto.fingerprintHex
import com.artemchep.keyguard.crypto.toDomain
import com.artemchep.keyguard.nativecrypto.NativeCrypto
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpPublicKeyParseResult
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SignatureSubpacketTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import java.io.ByteArrayInputStream
import java.security.Security
import java.util.Date
import kotlin.io.path.writeText
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Mirrors `MAX_CREATION_TIME_BUMP_SECONDS` in the native mutation core. */
private const val MAX_CREATION_TIME_BUMP_SECONDS = 300L

/** A read reference that covers any replacement-signature bump the core may apply. */
private fun Instant.coveringBump(): Instant = this + MAX_CREATION_TIME_BUMP_SECONDS.seconds

class GpgKeyExpirationServiceJvmTest {
    private val generator = NativeGpgKeyGenerator
    private val parser = NativeGpgPublicKeyParser

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `updates primary and subkey expiries without changing key identity`() {
        val now = Clock.System.now()
        val service = service(now)
        val generated = generateModernKey()
        val readAt = now.coveringBump()
        val originalInfo = generated.parseInfoAt(readAt)
        val target = now + 730.days
        val fingerprints = buildSet {
            add(originalInfo.fingerprint)
            originalInfo.subKeys.forEach { add(it.fingerprint) }
        }

        val result = service.update(
            GpgKeyExpirationRequest(
                key = generated,
                expiresAt = target,
                componentFingerprints = fingerprints,
            ),
        )

        val updated = assertIs<GpgKeyExpirationResult.Success>(result).key
        val updatedInfo = updated.parseInfoAt(readAt)
        assertEquals(generated.fingerprint, updated.fingerprint)
        assertEquals(generated.metadata, updated.metadata)
        assertInstantWithinOneSecond(target, updatedInfo.expiresAt)
        updatedInfo.subKeys.forEach { subkey ->
            assertInstantWithinOneSecond(target, subkey.expiresAt)
            val original = originalInfo.subKeys.single { it.fingerprint == subkey.fingerprint }
            assertEquals(original.createdAt, subkey.createdAt)
            assertEquals(original.keygrip, subkey.keygrip)
        }
        assertGpgImportsWithExpiry(updated.privateKeyArmored, target)
    }

    @Test
    fun `weak RSA self signature digests are upgraded to SHA256`() {
        val generated = generator.generate(
            GpgKeyConfig.Rsa(
                userId = "Legacy Digest <legacy-digest@test.invalid>",
                length = GpgKeyConfig.RsaLength.B3072,
            ),
        )

        listOf(HashAlgorithmTags.SHA1, HashAlgorithmTags.RIPEMD160).forEach { weakHash ->
            val legacy = generated.withPrimarySelfSignatureHash(weakHash)
            val legacyPrimary = publicRing(legacy.publicKeyArmored).publicKey
            val legacySelfSignature = latestPrimarySelfCertification(legacyPrimary)
            assertEquals(weakHash, legacySelfSignature.hashAlgorithm)
            val now = Instant.fromEpochMilliseconds(legacySelfSignature.creationTime.time) + 1.seconds
            val target = now + 365.days

            val updated = assertIs<GpgKeyExpirationResult.Success>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = legacy,
                        expiresAt = target,
                        componentFingerprints = setOf(legacy.fingerprint),
                    ),
                ),
            ).key

            val updatedPrimary = publicRing(updated.publicKeyArmored).publicKey
            assertEquals(
                HashAlgorithmTags.SHA256,
                latestPrimarySelfCertification(updatedPrimary).hashAlgorithm,
            )
        }
    }

    @Test
    fun `a SHA1 only key reads back as unauthenticated and template-only renewable`() {
        val generated = generator.generate(
            GpgKeyConfig.Rsa(
                userId = "Template Only <template-only@test.invalid>",
                length = GpgKeyConfig.RsaLength.B3072,
            ),
        )
        val legacy = generated.withPrimarySelfSignatureHash(HashAlgorithmTags.SHA1)
        val readAt = Clock.System.now() + 5.seconds

        // The parse path is what the vault view reads: the key is still listed,
        // but nothing about it is authenticated.
        val info = legacy.parseInfoAt(readAt)
        assertFalse(info.authenticated)
        assertFalse(info.canSign)
        assertFalse(info.canEncrypt)
        assertFalse(info.revoked)
        // The parse result carries the renewal tier too, so the vault view can
        // tell "renew me" apart from "no renewal can fix this".
        assertEquals(GpgRenewalAuthorization.TEMPLATE_ONLY, info.renewal)
        val healthy = generated.parseInfoAt(readAt)
        assertTrue(healthy.authenticated)
        assertEquals(GpgRenewalAuthorization.AUTHENTICATED, healthy.renewal)

        val renewals = legacy.renewalAuthorizations()
        assertEquals(
            GpgRenewalAuthorization.TEMPLATE_ONLY,
            renewals[legacy.fingerprint.normalizeGpgFingerprint()],
        )
        assertEquals(
            GpgRenewalAuthorization.AUTHENTICATED,
            generated.renewalAuthorizations()[generated.fingerprint.normalizeGpgFingerprint()],
        )
    }

    @Test
    fun `a key with no self signature reads back as unauthenticated and not renewable`() {
        val generated = generator.generate(
            GpgKeyConfig.Rsa(
                userId = "No Self Signature <no-self-sig@test.invalid>",
                length = GpgKeyConfig.RsaLength.B3072,
            ),
        )
        val orphaned = generated.withoutPrimarySelfSignatures()
        val readAt = Clock.System.now() + 5.seconds

        // Unauthenticated exactly like the SHA-1 key above, but nothing here is
        // renewable: there is no self-signature for a renewal to reissue.
        val info = orphaned.parseInfoAt(readAt)
        assertFalse(info.authenticated)
        assertEquals(GpgRenewalAuthorization.NONE, info.renewal)

        assertEquals(
            GpgRenewalAuthorization.NONE,
            orphaned.renewalAuthorizations()[orphaned.fingerprint.normalizeGpgFingerprint()],
        )
    }

    @Test
    fun `replacement preserves the absolute signature expiration time`() {
        val signatureDuration = 3_600L
        val generated = generateModernKey().withPrimarySelfSignatureHash(
            hashAlgorithm = HashAlgorithmTags.SHA256,
            signatureExpirationSeconds = signatureDuration,
        )
        val originalSignature = latestPrimarySelfCertification(
            publicRing(generated.publicKeyArmored).publicKey,
        )
        val originalExpiration = originalSignature.creationTime.time / 1_000L + signatureDuration
        val now = Instant.fromEpochMilliseconds(originalSignature.creationTime.time) + 60.seconds

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        ).key

        val replacement = latestPrimarySelfCertification(
            publicRing(updated.publicKeyArmored).publicKey,
        )
        assertEquals(
            originalExpiration,
            replacement.creationTime.time / 1_000L +
                replacement.hashedSubPackets.signatureExpirationTime,
        )
    }

    @Test
    fun `expired subkey binding cannot be renewed and does not block primary renewal`() {
        val (generated, expiredSubkeyFingerprint, now) =
            generateModernKey().withExpiredOnlySubkeyBinding()
        val target = now + 365.days

        val subkeyResult = service(now).update(
            GpgKeyExpirationRequest(
                key = generated,
                expiresAt = target,
                componentFingerprints = setOf(expiredSubkeyFingerprint),
            ),
        )
        // The only binding is expired, so the component neither authenticates nor
        // offers a usable renewal template. Renewal authorization reports that as an
        // unauthenticated component, which the wire enum surfaces as
        // SignatureVerificationFailed.
        assertEquals(
            GpgKeyExpirationError.SignatureVerificationFailed,
            assertIs<GpgKeyExpirationResult.Error>(subkeyResult).reason,
        )

        assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = target,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        )
    }

    @Test
    fun `replacement signatures supersede the original certificate after gpg merge`() {
        val generated = generateModernKey()
        val originalRing = publicRing(generated.publicKeyArmored)
        val originalSignatures = renewableSignatures(originalRing)
        val latestOriginalSignatureTime = originalSignatures.maxOf { it.creationTime.time }
        // The reference time is exactly the newest existing self-signature, so every
        // replacement collides on the same second. The native core resolves that by
        // bumping the new signature to newest_existing + 1 instead of failing.
        val currentTime = Instant.fromEpochMilliseconds(latestOriginalSignatureTime)
        val service = nativeService(now = { currentTime })
        val target = currentTime + 365.days
        val info = generated.parseInfo()
        val fingerprints = setOf(info.fingerprint) + info.subKeys.map { it.fingerprint }

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service.update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = target,
                    componentFingerprints = fingerprints,
                ),
            ),
        ).key

        assertTrue(
            renewableSignatures(publicRing(updated.publicKeyArmored))
                .all { it.creationTime.time > latestOriginalSignatureTime },
        )
        assertGpgMergeUsesExpiry(
            originalPrivateKeyArmored = generated.privateKeyArmored,
            updatedPublicKeyArmored = updated.publicKeyArmored,
            target = target,
        )
    }

    @Test
    fun `same-second renewal is bumped one second past the statement it supersedes`() {
        val generated = generateModernKey()
        // A frozen clock one second past the certificate's newest statement: the first
        // renewal lands on that second, and the second one collides with it. gpg parity
        // is to date the replacement one second later rather than refuse.
        val now = renewalNow(generated)
        val service = nativeService(now = { now })

        val first = assertIs<GpgKeyExpirationResult.Success>(
            service.update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        ).key
        val second = assertIs<GpgKeyExpirationResult.Success>(
            service.update(
                GpgKeyExpirationRequest(
                    key = first,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        ).key

        assertEquals(now.epochSeconds, primarySelfCertificationSeconds(first))
        assertEquals(now.epochSeconds + 1L, primarySelfCertificationSeconds(second))
    }

    @Test
    fun `a renewal that would repeat the bump against a frozen clock is refused`() {
        val generated = generateModernKey()
        val now = renewalNow(generated)
        val service = nativeService(now = { now })
        val request = { key: GpgKeyMaterial ->
            GpgKeyExpirationRequest(
                key = key,
                expiresAt = now + 365.days,
                componentFingerprints = setOf(generated.fingerprint),
            )
        }

        val first = assertIs<GpgKeyExpirationResult.Success>(
            service.update(request(generated.toGpgKeyMaterial())),
        ).key
        val second = assertIs<GpgKeyExpirationResult.Success>(service.update(request(first))).key

        // The bump has put the certificate's newest self-signature one second ahead of
        // this frozen clock. A future-dated signature is in no policy tier, so a third
        // attempt in the same second is refused. This is the documented edge of the
        // bump, not something a retry loop should paper over: a real caller's clock
        // advances past it.
        assertIs<GpgKeyExpirationResult.Error>(service.update(request(second)))
    }

    @Test
    fun `clock failures are reported as internal failures`() {
        val generated = generateModernKey()
        val service = nativeService(now = { error("clock failure") })

        val result = service.update(
            GpgKeyExpirationRequest(
                key = generated,
                expiresAt = null,
                componentFingerprints = setOf(generated.fingerprint),
            ),
        )

        assertEquals(
            GpgKeyExpirationError.InternalFailure,
            assertIs<GpgKeyExpirationResult.Error>(result).reason,
        )
    }

    @Test
    fun `cheap validation completes before reading the clock`() {
        val generated = generateModernKey()
        var clockReads = 0
        val service = nativeService(
            now = {
                clockReads += 1
                error("clock must not be read")
            },
        )

        val result = service.update(
            GpgKeyExpirationRequest(
                key = generated.copy(privateKeyArmored = ""),
                expiresAt = Clock.System.now() + 365.days,
                componentFingerprints = setOf(generated.fingerprint),
            ),
        )

        assertEquals(
            GpgKeyExpirationError.EmptyPrivateKey,
            assertIs<GpgKeyExpirationResult.Error>(result).reason,
        )
        assertEquals(0, clockReads)
    }

    @Test
    fun `malformed private keys are rejected`() {
        val generated = generateModernKey()
        val currentTime = Clock.System.now()
        val service = nativeService(now = { currentTime })

        val result = service.update(
            GpgKeyExpirationRequest(
                key = generated.copy(privateKeyArmored = "not an OpenPGP key"),
                expiresAt = currentTime + 365.days,
                componentFingerprints = setOf(generated.fingerprint),
            ),
        )

        assertEquals(
            GpgKeyExpirationError.MalformedKey,
            assertIs<GpgKeyExpirationResult.Error>(result).reason,
        )
    }

    @Test
    fun `finite expiry can be removed from every component`() {
        val generated = generateModernKey()
        val now = renewalNow(generated)
        val service = service(now)
        val readAt = now.coveringBump()
        val info = generated.parseInfoAt(readAt)
        val fingerprints = setOf(info.fingerprint) + info.subKeys.map { it.fingerprint }
        val finite = assertIs<GpgKeyExpirationResult.Success>(
            service.update(
                GpgKeyExpirationRequest(generated, now + 365.days, fingerprints),
            ),
        ).key

        val unlimited = assertIs<GpgKeyExpirationResult.Success>(
            service.update(
                GpgKeyExpirationRequest(finite, null, fingerprints),
            ),
        ).key.parseInfoAt(readAt)

        assertEquals(null, unlimited.expiresAt)
        assertTrue(unlimited.subKeys.all { it.expiresAt == null })
    }

    @Test
    fun `updating only primary leaves subkey certificates unchanged`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val readAt = now.coveringBump()
        val before = generated.parseInfoAt(readAt)

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = now + 400.days,
                    componentFingerprints = setOf(before.fingerprint),
                ),
            ),
        ).key.parseInfoAt(readAt)

        assertInstantWithinOneSecond(now + 400.days, updated.expiresAt)
        assertEquals(before.subKeys, updated.subKeys)
    }

    @Test
    fun `updating only one subkey leaves primary and other subkeys unchanged`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val before = generated.parseInfo()
        val selected = before.subKeys.first()
        val target = now + 250.days

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = target,
                    componentFingerprints = setOf(selected.fingerprint),
                ),
            ),
        ).key.parseInfo()

        assertEquals(before.expiresAt, updated.expiresAt)
        updated.subKeys.forEach { subkey ->
            if (subkey.fingerprint == selected.fingerprint) {
                assertInstantWithinOneSecond(target, subkey.expiresAt)
            } else {
                assertEquals(
                    before.subKeys.single { it.fingerprint == subkey.fingerprint },
                    subkey,
                )
            }
        }
    }

    @Test
    fun `public-only certification is preserved when expiry changes`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val certifier = generateModernKey()
        val publicRing = publicRing(generated.publicKeyArmored)
        val primary = publicRing.publicKey
        val userId = primary.userIDs.asSequence().first()
        val certifierSecret = secretRing(certifier.privateKeyArmored).secretKey
        val certification = signatureGenerator(
            signingKey = certifierSecret.publicKey,
            signatureType = PGPSignature.POSITIVE_CERTIFICATION,
            privateKey = certifierSecret.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(userId, primary)
        val certifiedPrimary = PGPPublicKey.addCertification(
            primary,
            userId,
            certification,
        )
        val refreshedPublicRing = PGPPublicKeyRing.insertPublicKey(
            publicRing,
            certifiedPrimary,
        )
        val refreshed = generated.copy(
            publicKeyArmored = refreshedPublicRing.armored(),
        )

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = refreshed,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        ).key

        val updatedPrimary = publicRing(updated.publicKeyArmored).publicKey
        assertTrue(
            updatedPrimary.getSignaturesForID(userId).asSequence().any { signature ->
                signature.encoded.contentEquals(certification.encoded)
            },
            "Expected the public-only third-party certification to be preserved.",
        )
    }

    @Test
    fun `secret-only primary revocation prevents expiry changes`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val originalSecretRing = secretRing(generated.privateKeyArmored)
        val primary = originalSecretRing.publicKey
        val revocation = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = originalSecretRing.secretKey.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(primary)
        val revokedPrimary = PGPPublicKey.addCertification(primary, revocation)
        val revokedCertificate = PGPPublicKeyRing.insertPublicKey(
            originalSecretRing.toCertificate(),
            revokedPrimary,
        )
        val revokedSecretRing = PGPSecretKeyRing.replacePublicKeys(
            originalSecretRing,
            revokedCertificate,
        )
        val secretRevoked = generated.copy(
            privateKeyArmored = revokedSecretRing.armored(),
        )
        val subkeyFingerprint = generated.parseInfo().subKeys.first().fingerprint

        val result = service(now).update(
            GpgKeyExpirationRequest(
                key = secretRevoked,
                expiresAt = now + 365.days,
                componentFingerprints = setOf(subkeyFingerprint),
            ),
        )

        assertEquals(
            GpgKeyExpirationError.RevokedComponent,
            assertIs<GpgKeyExpirationResult.Error>(result).reason,
        )
    }

    @Test
    fun `a revoked primary prevents subkey-only expiry changes`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val publicRing = publicRing(generated.publicKeyArmored)
        val primary = publicRing.publicKey
        val primarySecret = secretRing(generated.privateKeyArmored).secretKey
        val revocation = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = primarySecret.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(primary)
        val revokedPrimary = PGPPublicKey.addCertification(primary, revocation)
        val refreshedPublicRing = PGPPublicKeyRing.insertPublicKey(
            publicRing,
            revokedPrimary,
        )
        val refreshed = generated.copy(
            publicKeyArmored = refreshedPublicRing.armored(),
        )
        val subkeyFingerprint = generated.parseInfo().subKeys.first().fingerprint

        val result = service(now).update(
            GpgKeyExpirationRequest(
                key = refreshed,
                expiresAt = now + 365.days,
                componentFingerprints = setOf(subkeyFingerprint),
            ),
        )

        assertEquals(
            GpgKeyExpirationError.RevokedComponent,
            assertIs<GpgKeyExpirationResult.Error>(result).reason,
        )
    }

    @Test
    fun `designated revocation is unresolved without its key and verified when supplied`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val revoker = generateModernKey()
        val authorizationOnly = generated.withDesignatedRevoker(
            revoker = revoker,
            revokePrimary = false,
        )

        assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = authorizationOnly,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        )

        val revoked = generated.withDesignatedRevoker(
            revoker = revoker,
            revokePrimary = true,
        )
        assertEquals(
            GpgKeyExpirationError.UnresolvedRevocationAuthority,
            assertIs<GpgKeyExpirationResult.Error>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = revoked,
                        expiresAt = now + 365.days,
                        componentFingerprints = setOf(generated.fingerprint),
                    ),
                ),
            ).reason,
        )
        assertEquals(
            GpgKeyExpirationError.RevokedComponent,
            assertIs<GpgKeyExpirationResult.Error>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = revoked,
                        expiresAt = now + 365.days,
                        componentFingerprints = setOf(generated.fingerprint),
                        candidateRevocationKeys = listOf(
                            GpgOpenPgpPublicKey("not an OpenPGP key"),
                            GpgOpenPgpPublicKey(revoker.publicKeyArmored),
                        ),
                    ),
                ),
            ).reason,
        )
    }

    @Test
    fun `unresolved designated subkey revocation blocks only that component`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val revoker = generateModernKey()
        val subkeyFingerprint = generated.parseInfo().subKeys.first().fingerprint
        val revoked = generated.withDesignatedRevoker(
            revoker = revoker,
            revokeSubkeyFingerprint = subkeyFingerprint,
        )
        assertEquals(
            GpgKeyExpirationError.UnresolvedRevocationAuthority,
            assertIs<GpgKeyExpirationResult.Error>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = revoked,
                        expiresAt = now + 365.days,
                        componentFingerprints = setOf(subkeyFingerprint),
                    ),
                ),
            ).reason,
        )

        val updatedPrimary = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = revoked,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                    candidateRevocationKeys = listOf(
                        GpgOpenPgpPublicKey(revoker.publicKeyArmored),
                    ),
                ),
            ),
        ).key
        val updatedInspector = assertNotNull(
            GpgCertificateInspectorJvm.inspect(
                ring = publicRing(updatedPrimary.publicKeyArmored),
                candidateRevocationKeys = listOf(publicRing(revoker.publicKeyArmored).publicKey),
            ),
        )
        assertEquals(
            GpgRevocationStatusJvm.Revoked,
            updatedInspector.subkeys.single {
                it.publicKey.fingerprintHex() == subkeyFingerprint
            }.revocationStatus,
        )
        val updatedAuthorization = assertNotNull(
            NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = updatedPrimary.privateKeyArmored,
                publicKeyArmored = updatedPrimary.publicKeyArmored,
                fingerprint = updatedPrimary.fingerprint,
                candidateRevocationKeys = listOf(
                    GpgOpenPgpPublicKey(revoker.publicKeyArmored),
                ),
            ),
        )
        assertFalse(
            updatedAuthorization.authorizedAgentKeys.any { key -> key.fingerprint == subkeyFingerprint },
            "A verified externally revoked subkey must not be advertised to gpg-agent.",
        )

        assertEquals(
            GpgKeyExpirationError.RevokedComponent,
            assertIs<GpgKeyExpirationResult.Error>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = revoked,
                        expiresAt = now + 365.days,
                        componentFingerprints = setOf(subkeyFingerprint),
                        candidateRevocationKeys = listOf(
                            GpgOpenPgpPublicKey(revoker.publicKeyArmored),
                        ),
                    ),
                ),
            ).reason,
        )
    }

    @Test
    fun `a forged primary revocation does not prevent expiry changes`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val foreign = generateModernKey()
        val publicRing = publicRing(generated.publicKeyArmored)
        val primary = publicRing.publicKey
        val primarySecret = secretRing(generated.privateKeyArmored).secretKey
        val forgedRevocation = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.KEY_REVOCATION,
            privateKey = primarySecret.extractPrivateKeyEmptyPassphrase(),
        ).generateCertification(publicRing(foreign.publicKeyArmored).publicKey)
        val primaryWithForgedRevocation = PGPPublicKey.addCertification(
            primary,
            forgedRevocation,
        )
        val refreshed = generated.copy(
            publicKeyArmored = PGPPublicKeyRing.insertPublicKey(
                publicRing,
                primaryWithForgedRevocation,
            ).armored(),
        )
        val subkeyFingerprint = refreshed.parseInfo().subKeys.first().fingerprint

        val result = service(now).update(
            GpgKeyExpirationRequest(
                key = refreshed,
                expiresAt = now + 365.days,
                componentFingerprints = setOf(subkeyFingerprint),
            ),
        )

        val updated = assertIs<GpgKeyExpirationResult.Success>(result).key
        assertEquals(false, updated.parseInfo().revoked)
        assertTrue(
            publicRing(updated.publicKeyArmored).publicKey.keySignatures.asSequence().any {
                it.encoded.contentEquals(forgedRevocation.encoded)
            },
            "Expected the ignored transferable packet to remain in the certificate.",
        )
    }

    @Test
    fun `reordered public subkeys are reconciled by fingerprint`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val originalRing = publicRing(generated.publicKeyArmored)
        val originalKeys = originalRing.publicKeys.asSequence().toList()
        val reorderedKeys = listOf(originalKeys.first()) + originalKeys.drop(1).reversed()
        val refreshed = generated.copy(
            publicKeyArmored = PGPPublicKeyRing(reorderedKeys).armored(),
        )

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = refreshed,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        ).key

        val updatedKeys = publicRing(updated.publicKeyArmored).publicKeys.asSequence().toList()
        assertEquals(
            reorderedKeys.drop(1).map { it.fingerprint.toHex() }.toSet(),
            updatedKeys.drop(1).map { it.fingerprint.toHex() }.toSet(),
        )
        reorderedKeys.drop(1).forEach { before ->
            val after = updatedKeys.single { it.fingerprint.contentEquals(before.fingerprint) }
            assertTrue(before.encoded.contentEquals(after.encoded))
        }
    }

    @Test
    fun `authenticated public-only subkey is retained without becoming agent secret metadata`() {
        val generated = generateModernKey()
        val (refreshed, addedSubkey) = withAuthenticatedPublicOnlyEncryptionSubkey(generated)
        val now = Clock.System.now()

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = refreshed,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(generated.fingerprint),
                ),
            ),
        ).key

        val updatedPublicSubkey = publicRing(updated.publicKeyArmored)
            .publicKeys
            .asSequence()
            .single { it.fingerprint.contentEquals(addedSubkey.fingerprint) }
        val updatedSecretSubkey = secretRing(updated.privateKeyArmored)
            .toCertificate()
            .publicKeys
            .asSequence()
            .single { it.fingerprint.contentEquals(addedSubkey.fingerprint) }
        assertTrue(addedSubkey.encoded.contentEquals(updatedPublicSubkey.encoded))
        assertTrue(addedSubkey.encoded.contentEquals(updatedSecretSubkey.encoded))
        assertEquals(
            assertNotNull(generated.metadata).routableAgentKeys,
            assertNotNull(updated.metadata).routableAgentKeys,
        )
        val addedFingerprint = addedSubkey.fingerprint.toHex().uppercase()
        val addedComponent = assertNotNull(updated.metadata).certificates
            .flatMap { it.components }
            .single { it.fingerprint == addedFingerprint }
        assertFalse(addedComponent.storedSecretMaterial)
        assertTrue(updated.metadata?.routableAgentKeys.orEmpty().none { it.fingerprint == addedFingerprint })
    }

    @Test
    fun `authenticated public-only encryption subkey expiry can be updated`() {
        val generated = generateModernKey()
        val (refreshed, addedSubkey) = withAuthenticatedPublicOnlyEncryptionSubkey(generated)
        val now = Clock.System.now()
        val addedFingerprint = addedSubkey.fingerprint.toHex().uppercase()
        val target = now + 180.days

        val result = service(now).update(
            GpgKeyExpirationRequest(
                key = refreshed,
                expiresAt = target,
                componentFingerprints = setOf(addedFingerprint),
            ),
        )
        val updated = assertIs<GpgKeyExpirationResult.Success>(
            result,
            "Expected public-only encryption subkey renewal to succeed, got $result.",
        ).key

        val updatedInfo = updated.parseInfo()
        val inspectedSubkey = GpgCertificateInspectorJvm
            .inspect(publicRing(updated.publicKeyArmored))
            ?.subkeys
            ?.singleOrNull { it.publicKey.fingerprintHex() == addedFingerprint }
        val updatedSubkey = assertNotNull(
            updatedInfo.subKeys.singleOrNull { it.fingerprint == addedFingerprint },
            "Renewed public-only subkey $addedFingerprint was not authenticated by a fresh policy evaluation. " +
                "BC authenticated=${inspectedSubkey?.authenticated}, " +
                "BC binding=${inspectedSubkey?.effectiveSignature?.creationTime}; " +
                "Available subkeys: ${updatedInfo.subKeys.map { it.fingerprint }}",
        )
        assertInstantWithinOneSecond(target, updatedSubkey.expiresAt)
        assertEquals(
            assertNotNull(generated.metadata).routableAgentKeys,
            assertNotNull(updated.metadata).routableAgentKeys,
        )
        val addedComponent = assertNotNull(updated.metadata).certificates
            .flatMap { it.components }
            .single { it.fingerprint == addedFingerprint }
        assertFalse(addedComponent.storedSecretMaterial)
    }

    @Test
    fun `authenticated public-only signing subkey reuses its cross-signature`() {
        val generated = generateModernKey()
        val (refreshed, addedSubkey) = withAuthenticatedPublicOnlySigningSubkey(generated)
        val now = Clock.System.now()
        val addedFingerprint = addedSubkey.fingerprint.toHex().uppercase()
        val primaryKeyId = publicRing(refreshed.publicKeyArmored).publicKey.keyID
        val originalCrossSignature = addedSubkey.signatures.asSequence()
            .filter {
                it.signatureType == PGPSignature.SUBKEY_BINDING &&
                    it.keyID == primaryKeyId
            }
            .mapNotNull { it.hashedSubPackets }
            .flatMap { it.embeddedSignatures.asSequence() }
            .single { it.signatureType == PGPSignature.PRIMARYKEY_BINDING }
        val target = now + 180.days

        val result = service(now).update(
            GpgKeyExpirationRequest(
                key = refreshed,
                expiresAt = target,
                componentFingerprints = setOf(addedFingerprint),
            ),
        )
        val updated = assertIs<GpgKeyExpirationResult.Success>(
            result,
            "Expected public-only signing subkey renewal to succeed, got $result.",
        ).key

        val updatedSubkey = publicRing(updated.publicKeyArmored).publicKeys.asSequence()
            .single { it.fingerprint.contentEquals(addedSubkey.fingerprint) }
        val updatedCrossSignature = updatedSubkey.signatures.asSequence()
            .filter {
                it.signatureType == PGPSignature.SUBKEY_BINDING &&
                    it.keyID == primaryKeyId
            }
            .mapNotNull { it.hashedSubPackets }
            .flatMap { it.embeddedSignatures.asSequence() }
            .single { it.signatureType == PGPSignature.PRIMARYKEY_BINDING }
        assertTrue(originalCrossSignature.encoded.contentEquals(updatedCrossSignature.encoded))
        val updatedInfo = updated.parseInfo()
        val inspectedSubkey = GpgCertificateInspectorJvm
            .inspect(publicRing(updated.publicKeyArmored))
            ?.subkeys
            ?.singleOrNull { it.publicKey.fingerprintHex() == addedFingerprint }
        assertInstantWithinOneSecond(
            target,
            assertNotNull(
                updatedInfo.subKeys.singleOrNull { it.fingerprint == addedFingerprint },
                "Renewed public-only signing subkey $addedFingerprint was not authenticated by a fresh policy evaluation. " +
                    "BC authenticated=${inspectedSubkey?.authenticated}, " +
                    "BC binding=${inspectedSubkey?.effectiveSignature?.creationTime}, " +
                    "BC cross-certified=${inspectedSubkey?.signingCrossCertified}; " +
                    "Available subkeys: ${updatedInfo.subKeys.map { it.fingerprint }}",
            ).expiresAt,
        )
        assertEquals(
            assertNotNull(generated.metadata).routableAgentKeys,
            assertNotNull(updated.metadata).routableAgentKeys,
        )
        val addedComponent = assertNotNull(updated.metadata).certificates
            .flatMap { it.components }
            .single { it.fingerprint == addedFingerprint }
        assertFalse(addedComponent.storedSecretMaterial)
    }

    @Test
    fun `unbound public-only subkey is rejected`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val originalRing = publicRing(generated.publicKeyArmored)
        val foreignSubkey = publicRing(generateModernKey().publicKeyArmored)
            .publicKeys
            .asSequence()
            .first { !it.isMasterKey && it.isEncryptionKey }
        val suppliedKeys = originalRing.publicKeys.asSequence().toMutableList().apply {
            add(1, foreignSubkey)
        }
        val refreshed = generated.copy(
            publicKeyArmored = PGPPublicKeyRing(suppliedKeys).armored(),
        )

        val result = service(now).update(
            GpgKeyExpirationRequest(
                key = refreshed,
                expiresAt = now + 365.days,
                componentFingerprints = setOf(generated.fingerprint),
            ),
        )

        assertEquals(
            GpgKeyExpirationError.FingerprintMismatch,
            assertIs<GpgKeyExpirationResult.Error>(result).reason,
        )
    }

    @Test
    fun `empty non-canonical metadata is repopulated`() {
        val now = Clock.System.now()
        val generated = generateModernKey().copy(metadata = GpgAgentKeyMetadata())
        val info = generated.parseInfo()

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(info.fingerprint),
                ),
            ),
        ).key

        assertTrue(assertNotNull(updated.metadata).certificates.isNotEmpty())
        assertTrue(updated.metadata?.routableAgentKeys.orEmpty().isNotEmpty())
    }

    @Test
    fun `non-canonical and stale certificate indices are refreshed`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val generatedMetadata = assertNotNull(generated.metadata)
        val nonCanonicalMetadata = GpgAgentKeyMetadata()
        val staleMetadata = generatedMetadata.copy(
            certificates = generatedMetadata.certificates.mapIndexed { index, certificate ->
                if (index != 0) return@mapIndexed certificate
                certificate.copy(
                    components = certificate.components.mapIndexed { componentIndex, component ->
                        if (componentIndex == 0) component.copy(keygrips = listOf("stale-keygrip")) else component
                    },
                )
            },
        )

        listOf(nonCanonicalMetadata, staleMetadata).forEach { storedMetadata ->
            val stale = generated.copy(metadata = storedMetadata)
            val updated = assertIs<GpgKeyExpirationResult.Success>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = stale,
                        expiresAt = now + 365.days,
                        componentFingerprints = setOf(generated.fingerprint),
                    ),
                ),
            ).key

            assertEquals(generatedMetadata, updated.metadata)
        }
    }

    @Test
    fun `an empty fingerprint is canonicalized for a single certificate`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val info = generated.parseInfo()
        val legacy = generated.toGpgKeyMaterial().copy(fingerprint = "")

        val updated = assertIs<GpgKeyExpirationResult.Success>(
            service(now).update(
                GpgKeyExpirationRequest(
                    key = legacy,
                    expiresAt = now + 365.days,
                    componentFingerprints = setOf(info.fingerprint),
                ),
            ),
        ).key

        assertEquals(info.fingerprint, updated.fingerprint)
        assertTrue(assertNotNull(updated.metadata).certificates.isNotEmpty())
    }

    @Test
    fun `invalid requests fail without returning changed key material`() {
        val now = Clock.System.now()
        val service = service(now)
        val generated = generateModernKey()
        val fingerprint = generated.parseInfo().fingerprint

        assertEquals(
            GpgKeyExpirationError.NoComponentsSelected,
            assertIs<GpgKeyExpirationResult.Error>(
                service.update(GpgKeyExpirationRequest(generated, now + 1.days, emptySet())),
            ).reason,
        )
        assertEquals(
            GpgKeyExpirationError.InvalidExpiration,
            assertIs<GpgKeyExpirationResult.Error>(
                service.update(GpgKeyExpirationRequest(generated, now, setOf(fingerprint))),
            ).reason,
        )
        assertEquals(
            GpgKeyExpirationError.ComponentNotFound,
            assertIs<GpgKeyExpirationResult.Error>(
                service.update(GpgKeyExpirationRequest(generated, now + 1.days, setOf("00"))),
            ).reason,
        )
        assertEquals(
            GpgKeyExpirationError.EmptyPrivateKey,
            assertIs<GpgKeyExpirationResult.Error>(
                service.update(
                    GpgKeyExpirationRequest(
                        generated.copy(privateKeyArmored = ""),
                        now + 1.days,
                        setOf(fingerprint),
                    ),
                ),
            ).reason,
        )
        assertEquals(
            GpgKeyExpirationError.MalformedKey,
            assertIs<GpgKeyExpirationResult.Error>(
                service.update(
                    GpgKeyExpirationRequest(
                        generated.copy(privateKeyArmored = "not an OpenPGP key"),
                        now + 1.days,
                        setOf(fingerprint),
                    ),
                ),
            ).reason,
        )
    }

    @Test
    fun `unexpected implementation failures are not reported as malformed keys`() {
        val generated = generateModernKey()
        val now = renewableSignatures(publicRing(generated.publicKeyArmored))
            .maxOf { signature -> signature.creationTime.time }
            .let(Instant::fromEpochMilliseconds) + 1.seconds
        val service = GpgKeyExpirationServiceJvm(
            metadataResolver = object : GpgKeyMetadataResolver {
                override fun resolve(
                    privateKeyArmored: String?,
                    publicKeyArmored: String?,
                    fingerprint: String?,
                    candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
                ): GpgAgentMetadataResolution = error("resolver failure")
            },
            now = { now },
            waitForClock = {},
        )

        assertEquals(
            GpgKeyExpirationError.InternalFailure,
            assertIs<GpgKeyExpirationResult.Error>(
                service.update(
                    GpgKeyExpirationRequest(
                        generated,
                        now + 1.days,
                        setOf(generated.fingerprint),
                    ),
                ),
            ).reason,
        )
    }

    @Test
    fun `expiry after the OpenPGP timestamp ceiling fails`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val fingerprint = generated.parseInfo().fingerprint

        val result =
            service(now).update(
                GpgKeyExpirationRequest(
                    key = generated,
                    expiresAt = GPG_KEY_EXPIRATION_MAX_INSTANT + 1.nanoseconds,
                    componentFingerprints = setOf(fingerprint),
                ),
            )

        assertEquals(
            GpgKeyExpirationResult.Error(GpgKeyExpirationError.InvalidExpiration),
            result,
        )
    }

    @Test
    fun `expiry at the OpenPGP timestamp ceiling succeeds`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val fingerprint = generated.parseInfo().fingerprint
        val target = GPG_KEY_EXPIRATION_MAX_INSTANT

        val updated =
            assertIs<GpgKeyExpirationResult.Success>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = generated,
                        expiresAt = target,
                        componentFingerprints = setOf(fingerprint),
                    ),
                ),
            ).key.parseInfoAt(now.coveringBump())

        assertInstantWithinOneSecond(target, updated.expiresAt)
    }

    @Test
    fun `safe calendar expiry boundary interoperates with gpg`() {
        val now = Clock.System.now()
        val generated = generateModernKey()
        val info = generated.parseInfo()
        val componentFingerprints =
            buildSet {
                add(info.fingerprint)
                info.subKeys.forEach { add(it.fingerprint) }
            }
        val target =
            GPG_KEY_EXPIRATION_MAX_DATE
                .plus(1, DateTimeUnit.DAY)
                .atStartOfDayIn(TimeZone.of("-12:00")) - 60.seconds

        val updated =
            assertIs<GpgKeyExpirationResult.Success>(
                service(now).update(
                    GpgKeyExpirationRequest(
                        key = generated,
                        expiresAt = target,
                        componentFingerprints = componentFingerprints,
                    ),
                ),
            ).key

        assertGpgImportsWithExpiry(updated.privateKeyArmored, target)
    }

    private fun service(
        now: Instant,
    ): GpgKeyExpirationService = nativeService(now = { now })

    private fun nativeService(
        now: () -> Instant,
    ): GpgKeyExpirationService = object : GpgKeyExpirationService {
        override fun update(
            request: GpgKeyExpirationRequest,
        ): GpgKeyExpirationResult = NativeGpgKeyExpirationService.update(
            request = request,
            now = now,
        )
    }

    @Suppress("FunctionName")
    private fun GpgKeyExpirationRequest(
        key: GeneratedGpgKey,
        expiresAt: Instant?,
        componentFingerprints: Set<String>,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
    ): GpgKeyExpirationRequest = GpgKeyExpirationRequest(
        key = key.toGpgKeyMaterial(),
        change = GpgKeyExpirationChange(
            expiresAt = expiresAt,
            componentFingerprints = componentFingerprints,
        ),
        candidateRevocationKeys = candidateRevocationKeys,
    )

    @Suppress("FunctionName")
    private fun GpgKeyExpirationRequest(
        key: GpgKeyMaterial,
        expiresAt: Instant?,
        componentFingerprints: Set<String>,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
    ): GpgKeyExpirationRequest = GpgKeyExpirationRequest(
        key = key,
        change = GpgKeyExpirationChange(
            expiresAt = expiresAt,
            componentFingerprints = componentFingerprints,
        ),
        candidateRevocationKeys = candidateRevocationKeys,
    )

    private fun generateModernKey() = generator.generate(
        GpgKeyConfig.Modern(
            userId = "Expiry Test <expiry@test.invalid>",
        ),
    )

    @Suppress("DEPRECATION")
    private fun GeneratedGpgKey.withDesignatedRevoker(
        revoker: GeneratedGpgKey,
        revokePrimary: Boolean = false,
        revokeSubkeyFingerprint: String? = null,
    ): GeneratedGpgKey {
        val certificate = publicRing(publicKeyArmored)
        val secretKeyRing = secretRing(privateKeyArmored)
        val primary = certificate.publicKey
        val revokerSecretKey = secretRing(revoker.privateKeyArmored).secretKey
        val revokerPrimary = revokerSecretKey.publicKey
        val authorization = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.DIRECT_KEY,
            privateKey = secretKeyRing.secretKey.extractPrivateKeyEmptyPassphrase(),
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
        }.generateCertification(primary)
        var updatedPrimary = primary
        if (revokePrimary) {
            val revocation = signatureGenerator(
                signingKey = revokerPrimary,
                signatureType = PGPSignature.KEY_REVOCATION,
                privateKey = revokerSecretKey.extractPrivateKeyEmptyPassphrase(),
            ).generateCertification(primary)
            updatedPrimary = PGPPublicKey.addCertification(updatedPrimary, revocation)
        }
        // RFC 9580 orders primary-key revocations before direct-key signatures. Bouncy Castle's
        // addCertification() appends instead of canonicalizing, so the old fixture encoded the
        // authorization first and the revocation second. The native lossless mutation guard
        // correctly rejects that nonconforming order, requiring this fixture to be regenerated
        // by adding the revocation before the designated-revoker authorization.
        updatedPrimary = PGPPublicKey.addCertification(updatedPrimary, authorization)
        var updatedCertificate = PGPPublicKeyRing.insertPublicKey(certificate, updatedPrimary)
        if (revokeSubkeyFingerprint != null) {
            val subkey = certificate.publicKeys
                .asSequence()
                .first { key -> key.fingerprintHex() == revokeSubkeyFingerprint }
            val revocation = signatureGenerator(
                signingKey = revokerPrimary,
                signatureType = PGPSignature.SUBKEY_REVOCATION,
                privateKey = revokerSecretKey.extractPrivateKeyEmptyPassphrase(),
            ).generateCertification(primary, subkey)
            updatedCertificate = PGPPublicKeyRing.insertPublicKey(
                updatedCertificate,
                PGPPublicKey.addCertification(subkey, revocation),
            )
        }
        val updatedSecretKeyRing = PGPSecretKeyRing.replacePublicKeys(
            secretKeyRing,
            updatedCertificate,
        )
        return copy(
            privateKeyArmored = updatedSecretKeyRing.armored(),
            publicKeyArmored = updatedCertificate.armored(),
        )
    }

    private fun GeneratedGpgKey.withPrimarySelfSignatureHash(
        hashAlgorithm: Int,
        signatureExpirationSeconds: Long? = null,
    ): GeneratedGpgKey {
        val certificate = publicRing(publicKeyArmored)
        val secretKeyRing = secretRing(privateKeyArmored)
        val primary = certificate.publicKey
        val rawUserId = primary.rawUserIDs.asSequence().first()
        val selfCertifications = primary.getSignaturesForID(rawUserId)
            .asSequence()
            .filter { signature ->
                signature.keyID == primary.keyID &&
                    signature.signatureType in IDENTITY_CERTIFICATIONS
            }
            .toList()
        val template = selfCertifications.maxBy { it.creationTime.time }
        var updatedPrimary = primary
        selfCertifications.forEach { signature ->
            updatedPrimary = PGPPublicKey.removeCertification(
                updatedPrimary,
                rawUserId,
                signature,
            )
        }
        val generator = signatureGenerator(
            signingKey = primary,
            signatureType = template.signatureType,
            privateKey = secretKeyRing.secretKey.extractPrivateKeyEmptyPassphrase(),
            hashAlgorithm = hashAlgorithm,
        ).apply {
            val hashed = PGPSignatureSubpacketGenerator(template.hashedSubPackets)
            signatureExpirationSeconds?.let { seconds ->
                hashed.setSignatureExpirationTime(true, seconds)
            }
            setHashedSubpackets(hashed.generate())
            setUnhashedSubpackets(template.unhashedSubPackets)
        }
        val userId = rawUserId.decodeToString()
        val replacement = generator.generateCertification(userId, primary)
        updatedPrimary = PGPPublicKey.addCertification(
            updatedPrimary,
            rawUserId,
            replacement,
        )
        val updatedKeys = certificate.publicKeys.asSequence().map { key ->
            if (key.isMasterKey) updatedPrimary else key
        }.toList()
        val updatedCertificate = PGPPublicKeyRing(updatedKeys)
        val updatedSecretKeyRing = PGPSecretKeyRing.replacePublicKeys(
            secretKeyRing,
            updatedCertificate,
        )
        return copy(
            privateKeyArmored = updatedSecretKeyRing.armored(),
            publicKeyArmored = updatedCertificate.armored(),
        )
    }

    /**
     * Strips every self-signature that could authenticate the primary key: its
     * identity certifications and any Direct Key signatures. What is left is a
     * certificate a renewal cannot repair, because it has nothing to reissue.
     */
    private fun GeneratedGpgKey.withoutPrimarySelfSignatures(): GeneratedGpgKey {
        val certificate = publicRing(publicKeyArmored)
        val secretKeyRing = secretRing(privateKeyArmored)
        val primary = certificate.publicKey
        var updatedPrimary = primary
        primary.rawUserIDs.asSequence().toList().forEach { rawUserId ->
            primary.getSignaturesForID(rawUserId).asSequence().toList().forEach { signature ->
                updatedPrimary = PGPPublicKey.removeCertification(
                    updatedPrimary,
                    rawUserId,
                    signature,
                )
            }
        }
        primary.keySignatures.asSequence().toList().forEach { signature ->
            updatedPrimary = PGPPublicKey.removeCertification(updatedPrimary, signature)
        }
        val updatedKeys = certificate.publicKeys.asSequence().map { key ->
            if (key.isMasterKey) updatedPrimary else key
        }.toList()
        val updatedCertificate = PGPPublicKeyRing(updatedKeys)
        val updatedSecretKeyRing = PGPSecretKeyRing.replacePublicKeys(
            secretKeyRing,
            updatedCertificate,
        )
        return copy(
            privateKeyArmored = updatedSecretKeyRing.armored(),
            publicKeyArmored = updatedCertificate.armored(),
        )
    }

    private fun GeneratedGpgKey.withExpiredOnlySubkeyBinding(): Triple<GeneratedGpgKey, String, Instant> {
        val certificate = publicRing(publicKeyArmored)
        val secretKeyRing = secretRing(privateKeyArmored)
        val primary = certificate.publicKey
        val subkey = certificate.publicKeys.asSequence().first { key ->
            !key.isMasterKey && key.isEncryptionKey
        }
        val bindings = subkey.signatures.asSequence()
            .filter { signature -> signature.signatureType == PGPSignature.SUBKEY_BINDING }
            .toList()
        val template = bindings.maxBy { signature -> signature.creationTime.time }
        val creationSeconds = template.creationTime.time / 1_000L + 1L
        val hashed = PGPSignatureSubpacketGenerator(template.hashedSubPackets).apply {
            removePacketsOfType(SignatureSubpacketTags.CREATION_TIME)
            removePacketsOfType(SignatureSubpacketTags.EXPIRE_TIME)
            setSignatureCreationTime(true, Date(creationSeconds * 1_000L))
            setSignatureExpirationTime(true, 1L)
        }
        val expiredBinding = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.SUBKEY_BINDING,
            privateKey = secretKeyRing.secretKey.extractPrivateKeyEmptyPassphrase(),
            hashAlgorithm = template.hashAlgorithm,
        ).apply {
            setHashedSubpackets(hashed.generate())
            setUnhashedSubpackets(template.unhashedSubPackets)
        }.generateCertification(primary, subkey)
        var updatedSubkey = subkey
        bindings.forEach { binding ->
            updatedSubkey = PGPPublicKey.removeCertification(updatedSubkey, binding)
        }
        updatedSubkey = PGPPublicKey.addCertification(updatedSubkey, expiredBinding)
        val updatedCertificate = PGPPublicKeyRing.insertPublicKey(certificate, updatedSubkey)
        val updatedSecretKeyRing = PGPSecretKeyRing.replacePublicKeys(
            secretKeyRing,
            updatedCertificate,
        )
        return Triple(
            copy(
                privateKeyArmored = updatedSecretKeyRing.armored(),
                publicKeyArmored = updatedCertificate.armored(),
            ),
            subkey.fingerprintHex(),
            Instant.fromEpochSeconds(creationSeconds + 1L),
        )
    }

    private fun latestPrimarySelfCertification(
        primary: PGPPublicKey,
    ): PGPSignature = primary.rawUserIDs.asSequence()
        .flatMap { rawUserId ->
            primary.getSignaturesForID(rawUserId).asSequence()
        }
        .filter { signature ->
            signature.keyID == primary.keyID &&
                signature.signatureType in IDENTITY_CERTIFICATIONS
        }
        .maxBy { it.creationTime.time }

    private fun renewableSignatures(
        certificate: PGPPublicKeyRing,
    ): List<PGPSignature> {
        val primary = certificate.publicKey
        return buildList {
            addAll(
                primary.keySignatures.asSequence()
                    .filter {
                        it.keyID == primary.keyID &&
                            it.signatureType == PGPSignature.DIRECT_KEY
                    },
            )
            primary.rawUserIDs.asSequence().forEach { rawUserId ->
                addAll(
                    primary.getSignaturesForID(rawUserId).asSequence()
                        .filter {
                            it.keyID == primary.keyID &&
                                it.signatureType in IDENTITY_CERTIFICATIONS
                        },
                )
            }
            certificate.publicKeys.asSequence()
                .filterNot { it.isMasterKey }
                .forEach { subkey ->
                    addAll(
                        subkey.signatures.asSequence()
                            .filter {
                                it.keyID == primary.keyID &&
                                    it.signatureType == PGPSignature.SUBKEY_BINDING
                            },
                    )
                }
        }
    }

    private fun withAuthenticatedPublicOnlyEncryptionSubkey(
        generated: com.artemchep.keyguard.common.model.GeneratedGpgKey,
    ): Pair<com.artemchep.keyguard.common.model.GeneratedGpgKey, PGPPublicKey> {
        val originalRing = publicRing(generated.publicKeyArmored)
        val primary = originalRing.publicKey
        val primarySecret = secretRing(generated.privateKeyArmored).secretKey
        val foreignSubkey = publicRing(generateModernKey().publicKeyArmored)
            .publicKeys
            .asSequence()
            .first { !it.isMasterKey && it.isEncryptionKey }
        val binding = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.SUBKEY_BINDING,
            privateKey = primarySecret.extractPrivateKeyEmptyPassphrase(),
        ).apply {
            val hashed = PGPSignatureSubpacketGenerator()
            hashed.setSignatureCreationTime(true, backdatedSignatureCreationTime())
            setHashedSubpackets(hashed.generate())
        }.generateCertification(primary, foreignSubkey)
        val authenticatedSubkey = PGPPublicKey.addCertification(foreignSubkey, binding)
        val suppliedKeys = originalRing.publicKeys.asSequence().toMutableList().apply {
            add(1, authenticatedSubkey)
        }
        return generated.copy(
            publicKeyArmored = PGPPublicKeyRing(suppliedKeys).armored(),
        ) to authenticatedSubkey
    }

    private fun withAuthenticatedPublicOnlySigningSubkey(
        generated: com.artemchep.keyguard.common.model.GeneratedGpgKey,
    ): Pair<com.artemchep.keyguard.common.model.GeneratedGpgKey, PGPPublicKey> {
        val originalRing = publicRing(generated.publicKeyArmored)
        val primary = originalRing.publicKey
        val primaryPrivate = secretRing(generated.privateKeyArmored)
            .secretKey
            .extractPrivateKeyEmptyPassphrase()
        val foreign = generateModernKey()
        val foreignRing = publicRing(foreign.publicKeyArmored)
        val foreignSubkey = foreignRing.publicKeys.asSequence()
            .first { !it.isMasterKey && !it.isEncryptionKey }
        val foreignSubkeyPrivate = secretRing(foreign.privateKeyArmored)
            .getSecretKey(foreignSubkey.keyID)
            .extractPrivateKeyEmptyPassphrase()
        val crossSignatureGenerator = signatureGenerator(
            signingKey = foreignSubkey,
            signatureType = PGPSignature.PRIMARYKEY_BINDING,
            privateKey = foreignSubkeyPrivate,
        ).apply {
            val hashed = PGPSignatureSubpacketGenerator()
            hashed.setSignatureCreationTime(true, backdatedSignatureCreationTime())
            setHashedSubpackets(hashed.generate())
        }
        val crossSignature = crossSignatureGenerator.generateCertification(primary, foreignSubkey)
        val bindingGenerator = signatureGenerator(
            signingKey = primary,
            signatureType = PGPSignature.SUBKEY_BINDING,
            privateKey = primaryPrivate,
        ).apply {
            val hashed = PGPSignatureSubpacketGenerator()
            hashed.setSignatureCreationTime(true, backdatedSignatureCreationTime())
            hashed.setKeyFlags(true, KeyFlags.SIGN_DATA)
            hashed.addEmbeddedSignature(false, crossSignature)
            setHashedSubpackets(hashed.generate())
        }
        val binding = bindingGenerator.generateCertification(primary, foreignSubkey)
        val authenticatedSubkey = PGPPublicKey.addCertification(foreignSubkey, binding)
        val suppliedKeys = originalRing.publicKeys.asSequence().toMutableList().apply {
            add(1, authenticatedSubkey)
        }
        return generated.copy(
            publicKeyArmored = PGPPublicKeyRing(suppliedKeys).armored(),
        ) to authenticatedSubkey
    }

    private fun backdatedSignatureCreationTime(): Date = Date(
        (Clock.System.now() - 60.seconds).toEpochMilliseconds(),
    )

    private fun publicRing(
        armored: String,
    ): PGPPublicKeyRing = PGPPublicKeyRingCollection(
        PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
        JcaKeyFingerprintCalculator(),
    ).keyRings.next()

    private fun secretRing(
        armored: String,
    ): PGPSecretKeyRing = PGPSecretKeyRingCollection(
        PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
        JcaKeyFingerprintCalculator(),
    ).keyRings.next()

    private fun signatureGenerator(
        signingKey: PGPPublicKey,
        signatureType: Int,
        privateKey: PGPPrivateKey,
        hashAlgorithm: Int = HashAlgorithmTags.SHA256,
    ): PGPSignatureGenerator = PGPSignatureGenerator(
        JcaPGPContentSignerBuilder(signingKey.algorithm, hashAlgorithm)
            .setProvider(BouncyCastleProvider.PROVIDER_NAME),
        signingKey,
    ).apply {
        init(signatureType, privateKey)
    }

    private fun com.artemchep.keyguard.common.model.GeneratedGpgKey.parseInfo(): GpgPublicKeyInfo {
        val result = assertIs<GpgPublicKeyParseResult.Success>(parser.parse(publicKeyArmored))
        return result.keys.single()
    }

    /**
     * A renewal reference strictly after every statement the certificate already
     * carries. Real callers renew later than the certificate was created; freezing the
     * clock at or before the newest existing signature exercises the same-second bump
     * instead, which `same-second renewal is bumped ...` covers on purpose.
     */
    private fun renewalNow(key: GeneratedGpgKey): Instant =
        Instant.fromEpochSeconds(newestRenewableSecond(key.publicKeyArmored)) + 1.seconds

    private fun newestRenewableSecond(publicKeyArmored: String): Long =
        renewableSignatures(publicRing(publicKeyArmored))
            .maxOf { signature -> signature.creationTime.time / 1_000L }

    private fun primarySelfCertificationSeconds(key: GpgKeyMaterial): Long =
        latestPrimarySelfCertification(
            publicRing(key.publicKeyArmored).publicKey,
        ).creationTime.time / 1_000L

    private fun GpgKeyMaterial.parseInfo(): GpgPublicKeyInfo {
        val result = assertIs<GpgPublicKeyParseResult.Success>(parser.parse(publicKeyArmored))
        return result.keys.single()
    }

    /**
     * Parses the certificate as of [referenceTime].
     *
     * A renewal whose reference time collides with the certificate's newest statement is
     * resolved by bumping the replacement signature forward (bounded by
     * [MAX_CREATION_TIME_BUMP_SECONDS]) rather than by refusing. A replacement can
     * therefore be dated slightly after the instant the caller asked for, and reading it
     * back against the wall clock would see a not-yet-valid signature. Tests that renew
     * at "now" must read the result at an instant that covers the bump.
     */
    private fun GpgKeyMaterial.parseInfoAt(referenceTime: Instant): GpgPublicKeyInfo {
        val keyData = publicKeyArmored.encodeToByteArray()
        val result = assertIs<NativeOpenPgpPublicKeyParseResult.Success>(
            NativeCrypto.openPgp.parsePublicKeys(
                keyData = keyData,
                referenceTimeEpochSeconds = referenceTime.epochSeconds,
            ),
        )
        return result.keys.single().toDomain()
    }

    private fun GeneratedGpgKey.parseInfoAt(referenceTime: Instant): GpgPublicKeyInfo =
        toGpgKeyMaterial().parseInfoAt(referenceTime)

    /**
     * Renewal state from one live policy evaluation. It lives on the transient
     * authorization snapshot, never on the persisted certificate index.
     */
    private fun GeneratedGpgKey.renewalAuthorizations(): Map<String, GpgRenewalAuthorization> {
        val metadata = assertNotNull(
            NativeGpgKeyMetadataResolver.resolve(
                privateKeyArmored = privateKeyArmored,
                publicKeyArmored = publicKeyArmored,
                fingerprint = fingerprint,
            ),
        )
        return assertNotNull(metadata.authorization).renewals
    }

    private fun assertInstantWithinOneSecond(
        expected: Instant,
        actual: Instant?,
    ) {
        assertNotNull(actual)
        assertTrue(
            (actual - expected).absoluteValue.inWholeSeconds <= 1L,
            "Expected $expected, got $actual",
        )
    }

    private fun assertGpgImportsWithExpiry(
        privateKeyArmored: String,
        target: Instant,
    ) {
        if (!GpgCliTestSupport.isGpgAvailable()) return
        val home = GpgCliTestSupport.createHome("kg-expiry-")
        try {
            home.resolve("gpg-agent.conf").writeText("allow-loopback-pinentry\n")
            home.resolve("gpg.conf").writeText("pinentry-mode loopback\n")
            val keyFile = home.resolve("key.asc")
            keyFile.writeText(privateKeyArmored)
            val imported = GpgCliTestSupport.runGpg(home, "--batch", "--import", keyFile.toString())
            assertEquals(0, imported.exitCode, imported.stderr)
            val listing = GpgCliTestSupport.runGpg(home, "--with-colons", "--list-secret-keys")
            assertEquals(0, listing.exitCode, listing.stderr)
            val expected = target.epochSeconds
            val expiries = listing.stdout.lineSequence()
                .map { it.split(':') }
                .filter { it.firstOrNull() in setOf("sec", "ssb") }
                .mapNotNull { it.getOrNull(6)?.toLongOrNull() }
                .toList()
            assertTrue(expiries.isNotEmpty(), listing.stdout)
            assertTrue(expiries.all { kotlin.math.abs(it - expected) <= 1L }, listing.stdout)
        } finally {
            runCatching { GpgCliTestSupport.killAgent(home) }
            runCatching { home.toFile().deleteRecursively() }
        }
    }

    private fun assertGpgMergeUsesExpiry(
        originalPrivateKeyArmored: String,
        updatedPublicKeyArmored: String,
        target: Instant,
    ) {
        if (!GpgCliTestSupport.isGpgAvailable()) return
        val home = GpgCliTestSupport.createHome("kg-expiry-merge-")
        try {
            home.resolve("gpg-agent.conf").writeText("allow-loopback-pinentry\n")
            home.resolve("gpg.conf").writeText("pinentry-mode loopback\n")
            val originalFile = home.resolve("original.asc")
            originalFile.writeText(originalPrivateKeyArmored)
            val updatedFile = home.resolve("updated.asc")
            updatedFile.writeText(updatedPublicKeyArmored)
            val originalImport = GpgCliTestSupport.runGpg(
                home,
                "--batch",
                "--import",
                originalFile.toString(),
            )
            assertEquals(0, originalImport.exitCode, originalImport.stderr)
            val updatedImport = GpgCliTestSupport.runGpg(
                home,
                "--batch",
                "--import",
                updatedFile.toString(),
            )
            assertEquals(0, updatedImport.exitCode, updatedImport.stderr)
            val listing = GpgCliTestSupport.runGpg(home, "--with-colons", "--list-secret-keys")
            assertEquals(0, listing.exitCode, listing.stderr)
            val expected = target.epochSeconds
            val expiries = listing.stdout.lineSequence()
                .map { it.split(':') }
                .filter { it.firstOrNull() in setOf("sec", "ssb") }
                .mapNotNull { it.getOrNull(6)?.toLongOrNull() }
                .toList()
            assertTrue(expiries.isNotEmpty(), listing.stdout)
            assertTrue(expiries.all { kotlin.math.abs(it - expected) <= 1L }, listing.stdout)
        } finally {
            runCatching { GpgCliTestSupport.killAgent(home) }
            runCatching { home.toFile().deleteRecursively() }
        }
    }

    private companion object {
        val IDENTITY_CERTIFICATIONS = setOf(
            PGPSignature.DEFAULT_CERTIFICATION,
            PGPSignature.NO_CERTIFICATION,
            PGPSignature.CASUAL_CERTIFICATION,
            PGPSignature.POSITIVE_CERTIFICATION,
        )
    }
}
