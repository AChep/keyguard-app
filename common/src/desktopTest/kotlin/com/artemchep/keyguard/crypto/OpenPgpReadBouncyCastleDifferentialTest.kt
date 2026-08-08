package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.model.GpgKeyExpiry
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPrivateKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpReadFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyDetachedTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseError
import com.artemchep.keyguard.common.service.crypto.GpgPublicKeyParseResult
import com.artemchep.keyguard.common.service.crypto.GpgPublicSubKeyInfo
import com.artemchep.keyguard.common.service.crypto.GpgTestKeyFixtures
import com.artemchep.keyguard.common.service.crypto.extractGpgUserIdEmail
import com.artemchep.keyguard.common.service.crypto.gpgAlgorithmName
import com.artemchep.keyguard.common.service.crypto.parseClearSignedMessage
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import com.artemchep.keyguard.util.io.toSource
import kotlinx.datetime.TimeZone
import kotlinx.io.Buffer
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGInputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SignaturePacket
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Date
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Permanent differential coverage for OpenPGP read behavior.
 *
 * The oracle below preserves the BC 1.84 read behavior in test-only form. The native and
 * BC results are compared as complete domain DTOs. ASCII-armor `Version:` headers are removed,
 * and CRLF is canonicalized to LF because BC uses the platform line separator while rPGP emits
 * LF. Packet bytes, per-certificate packet ordering, CRC, and all other armor remain exact. The
 * designated-revoker collection comparison additionally sorts complete certificate DTOs by
 * fingerprint because BC's `getKeyRings()` exposes `HashMap` iteration order.
 * The repository has no checked-in OpenPGP v6 certificate fixture yet; the existing
 * RSA, legacy Curve25519, and NIST ECDSA fixtures are all exercised here.
 */
class OpenPgpReadBouncyCastleDifferentialTest {
    private val nativeParser = NativeGpgPublicKeyParser
    private val nativeMetadataResolver = NativeGpgKeyMetadataResolver
    private val nativeVerifier = NativeGpgOpenPgpVerifier
    private val nativeService = NativeGpgOpenPgpService()
    private val bcSigner = BcGpgOpenPgpServiceTestOracle()

    @BeforeTest
    fun assertPinnedOracleVersion() {
        assertEquals("1.84", gpgBouncyCastleProvider.versionStr)
    }

    @Test
    fun `native parser and metadata match BC for RSA fixtures`() {
        assertAlgorithmFixtureParity("RSA")
    }

    @Test
    fun `native parser and metadata match BC for legacy Curve25519 fixtures`() {
        assertAlgorithmFixtureParity("legacy Ed25519 plus CV25519", "legacy Ed25519")
    }

    @Test
    fun `native parser and metadata match BC for existing NIST ECDSA fixtures`() {
        assertAlgorithmFixtureParity("NIST P-256 ECDSA plus ECDH", "ECDSA")
    }

    private fun assertAlgorithmFixtureParity(vararg names: String) {
        algorithmFixtures.filter { fixture -> fixture.name in names }.forEach { fixture ->
            val publicKeyArmored = publicKeyArmoredOf(fixture.secretKeyArmored)
            val fingerprint = publicRingOf(fixture.secretKeyArmored).publicKey.fingerprintHex()

            assertParserParity(fixture.name, publicKeyArmored)
            assertMetadataParity(
                context = fixture.name,
                privateKeyArmored = fixture.secretKeyArmored,
                publicKeyArmored = publicKeyArmored,
                fingerprint = fingerprint,
            )
        }
    }

    @Test
    fun `native expiry flags and cross-certified capabilities match BC`() {
        val generator =
            generator(
                creationTime = CERTIFICATE_CREATION_TIME,
            )
        val generatedCases =
            listOf(
                generator.generate(
                    GpgKeyConfig.Modern(
                        userId = "OpenPGP Modern <openpgp-modern@test.invalid>",
                        expiry = GpgKeyExpiry.At(CERTIFICATE_EXPIRY_TIME),
                    ),
                ),
                generator.generate(
                    GpgKeyConfig.Rsa(
                        userId = "OpenPGP RSA <openpgp-rsa@test.invalid>",
                        length = GpgKeyConfig.RsaLength.B3072,
                        expiry = GpgKeyExpiry.At(CERTIFICATE_EXPIRY_TIME),
                    ),
                ),
            )

        generatedCases.forEach { generated ->
            assertParserParity(generated.typeLabel, generated.publicKeyArmored)
            assertMetadataParity(
                context = generated.typeLabel,
                privateKeyArmored = generated.privateKeyArmored,
                publicKeyArmored = generated.publicKeyArmored,
                fingerprint = generated.fingerprint,
            )

            val parsed =
                nativeParser
                    .parse(generated.publicKeyArmored)
                    .requireSuccess()
                    .single()
            assertEquals(CERTIFICATE_EXPIRY_TIME, parsed.expiresAt)
            assertTrue(parsed.subKeys.all { subkey -> subkey.expiresAt == CERTIFICATE_EXPIRY_TIME })
        }

        val modern = generatedCases.first()
        val modernMetadata =
            assertNotNull(
                nativeMetadataResolver.resolve(
                    privateKeyArmored = modern.privateKeyArmored,
                    publicKeyArmored = modern.publicKeyArmored,
                    fingerprint = modern.fingerprint,
                ),
            )
        assertTrue(
            modernMetadata.keys.any { key ->
                key.fingerprint != modern.fingerprint && "sign" in key.capabilities
            },
            "the authenticated signing subkey must retain its cross-certified sign capability",
        )

        val rsa = generatedCases.last()
        val rsaPrimary =
            assertNotNull(
                nativeMetadataResolver.resolve(
                    privateKeyArmored = rsa.privateKeyArmored,
                    publicKeyArmored = rsa.publicKeyArmored,
                    fingerprint = rsa.fingerprint,
                ),
            ).keys.single { key -> key.fingerprint == rsa.fingerprint }
        assertFalse(
            "decrypt" in rsaPrimary.capabilities,
            "authenticated RSA key flags, not the raw RSA algorithm, define capabilities",
        )
    }

    @Test
    fun `native user-id authentication and signing cross-certification policy match BC`() {
        val fixture = policyFixture
        val victimRing = publicRing(fixture.victim)
        val victimSecret = secretRing(fixture.victim)
        val victimPrimary = victimRing.publicKey

        val forgedUserId = "Forged Identity <forged@test.invalid>"
        val foreignPrimary = publicRing(fixture.foreign).publicKey
        val forgedCertification =
            signatureGenerator(
                signingKey = foreignPrimary,
                signatureType = PGPSignature.POSITIVE_CERTIFICATION,
                privateKey = secretRing(fixture.foreign).secretKey.extractPrivateKeyEmptyPassphrase(),
            ).generateCertification(forgedUserId, victimPrimary)
        val forgedPrimary =
            PGPPublicKey.addCertification(
                victimPrimary,
                forgedUserId,
                forgedCertification,
            )
        val forgedRing = PGPPublicKeyRing.insertPublicKey(victimRing, forgedPrimary)

        assertParserParity("forged user id", forgedRing.armored())
        val forgedParsed =
            nativeParser
                .parse(forgedRing.armored())
                .requireSuccess()
                .single()
        assertFalse(forgedUserId in forgedParsed.userIds)

        val inspected = assertNotNull(GpgCertificateInspectorJvm.inspect(victimRing))
        val signingSubkey =
            inspected.subkeys
                .single { subkey ->
                    subkey.keyFlags?.let { flags -> flags and KeyFlags.SIGN_DATA != 0 } == true
                }.publicKey
        var replacementSubkey = signingSubkey
        signingSubkey.signatures.asSequence().toList().forEach { signature ->
            replacementSubkey = PGPPublicKey.removeCertification(replacementSubkey, signature)
        }
        val bindingWithoutCrossCertification =
            signatureGenerator(
                signingKey = victimPrimary,
                signatureType = PGPSignature.SUBKEY_BINDING,
                privateKey = victimSecret.secretKey.extractPrivateKeyEmptyPassphrase(),
            ).apply {
                setHashedSubpackets(
                    org.bouncycastle.openpgp
                        .PGPSignatureSubpacketGenerator()
                        .apply {
                            setKeyFlags(false, KeyFlags.SIGN_DATA)
                        }.generate(),
                )
            }.generateCertification(victimPrimary, replacementSubkey)
        replacementSubkey =
            PGPPublicKey.addCertification(
                replacementSubkey,
                bindingWithoutCrossCertification,
            )
        val nonCrossCertifiedRing = PGPPublicKeyRing.insertPublicKey(victimRing, replacementSubkey)

        assertParserParity("signing subkey without cross-certification", nonCrossCertifiedRing.armored())
        assertMetadataParity(
            context = "signing subkey without cross-certification",
            privateKeyArmored = null,
            publicKeyArmored = nonCrossCertifiedRing.armored(),
            fingerprint = victimPrimary.fingerprintHex(),
        )
        val parsedSigningSubkey =
            nativeParser
                .parse(nonCrossCertifiedRing.armored())
                .requireSuccess()
                .single()
                .subKeys
                .single { subkey -> subkey.fingerprint == signingSubkey.fingerprintHex() }
        assertFalse(parsedSigningSubkey.canSign)
        val metadata =
            assertNotNull(
                nativeMetadataResolver.resolve(
                    privateKeyArmored = null,
                    publicKeyArmored = nonCrossCertifiedRing.armored(),
                    fingerprint = victimPrimary.fingerprintHex(),
                ),
            )
        assertFalse(metadata.keys.any { key -> key.fingerprint == signingSubkey.fingerprintHex() })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `native designated revocation policy matches BC for parser and metadata`() {
        val fixture = policyFixture
        val victimRing = publicRing(fixture.victim)
        val victimPrimary = victimRing.publicKey
        val revokerPrimary = publicRing(fixture.revoker).publicKey

        val authorization =
            signatureGenerator(
                signingKey = victimPrimary,
                signatureType = PGPSignature.DIRECT_KEY,
                privateKey = secretRing(fixture.victim).secretKey.extractPrivateKeyEmptyPassphrase(),
            ).apply {
                setHashedSubpackets(
                    org.bouncycastle.openpgp
                        .PGPSignatureSubpacketGenerator()
                        .apply {
                            setRevocationKey(
                                false,
                                revokerPrimary.algorithm,
                                revokerPrimary.fingerprint,
                            )
                        }.generate(),
                )
            }.generateCertification(victimPrimary)
        val revocation =
            signatureGenerator(
                signingKey = revokerPrimary,
                signatureType = PGPSignature.KEY_REVOCATION,
                privateKey = secretRing(fixture.revoker).secretKey.extractPrivateKeyEmptyPassphrase(),
            ).generateCertification(victimPrimary)
        // RFC 9580 places a primary-key revocation before direct-key signatures. Bouncy Castle
        // only appends certifications, so regenerate this deprecated designated-revoker
        // compatibility fixture in wire order instead of retaining the old nonconforming order.
        val revokedPrimary =
            PGPPublicKey.addCertification(
                PGPPublicKey.addCertification(victimPrimary, revocation),
                authorization,
            )
        val revokedRing = PGPPublicKeyRing.insertPublicKey(victimRing, revokedPrimary)
        val armoredCollection = armoredCollection(revokedRing, publicRing(fixture.revoker))

        // BC 1.84 getKeyRings() iterates HashMap values, so generated key IDs make its
        // multi-ring order nondeterministic. Compare every DTO field in a stable order;
        // native keeps the encoded certificate order.
        assertParserParity(
            context = "designated primary revocation",
            armored = armoredCollection,
            sortKeysByFingerprint = true,
        )
        assertMetadataParity(
            context = "designated primary revocation",
            privateKeyArmored = null,
            publicKeyArmored = revokedRing.armored(),
            fingerprint = victimPrimary.fingerprintHex(),
            candidateRevocationKeys = listOf(GpgOpenPgpPublicKey(fixture.revoker.publicKeyArmored)),
        )

        val parsedVictim =
            nativeParser
                .parse(armoredCollection)
                .requireSuccess()
                .single { key -> key.fingerprint == victimPrimary.fingerprintHex() }
        assertTrue(parsedVictim.revoked)
        assertFalse(parsedVictim.canSign)
        assertFalse(parsedVictim.canEncrypt)
    }

    @Test
    fun `native detached verification matches BC for valid invalid and missing-key cases`() {
        signingFixtures.forEach { fixture ->
            val publicKey = GpgOpenPgpPublicKey(publicKeyArmoredOf(fixture.secretKeyArmored))
            val request =
                GpgOpenPgpSignTextRequest(
                    text = DETACHED_TEXT,
                    privateKey = GpgOpenPgpPrivateKey(fixture.secretKeyArmored),
                )
            val signature = bcSigner.signTextDetached(request)

            assertDetachedVerificationParity(
                context = "${fixture.name} valid",
                request =
                    GpgOpenPgpVerifyDetachedTextRequest(
                        text = DETACHED_TEXT,
                        signature = signature,
                        publicKeys = listOf(publicKey),
                    ),
            )
            assertDetachedVerificationParity(
                context = "${fixture.name} invalid",
                request =
                    GpgOpenPgpVerifyDetachedTextRequest(
                        text = "$DETACHED_TEXT tampered",
                        signature = signature,
                        publicKeys = listOf(publicKey),
                    ),
            )
            assertDetachedVerificationParity(
                context = "${fixture.name} missing",
                request =
                    GpgOpenPgpVerifyDetachedTextRequest(
                        text = DETACHED_TEXT,
                        signature = signature,
                        publicKeys = emptyList(),
                    ),
            )
        }
    }

    @Test
    fun `native clear-text verification matches BC`() {
        clearTextSigningFixtures.forEach { fixture ->
            val publicKey = GpgOpenPgpPublicKey(publicKeyArmoredOf(fixture.secretKeyArmored))
            val signed =
                bcSigner.clearSignText(
                    GpgOpenPgpSignTextRequest(
                        text = CLEAR_TEXT,
                        privateKey = GpgOpenPgpPrivateKey(fixture.secretKeyArmored),
                    ),
                )

            assertClearTextVerificationParity(
                context = "${fixture.name} valid clear text",
                request =
                    GpgOpenPgpVerifyTextRequest(
                        signedText = signed,
                        publicKeys = listOf(publicKey),
                    ),
            )
            assertClearTextVerificationParity(
                context = "${fixture.name} invalid clear text",
                request =
                    GpgOpenPgpVerifyTextRequest(
                        signedText = signed.replace("dash escaped", "tampered"),
                        publicKeys = listOf(publicKey),
                    ),
            )
            assertClearTextVerificationParity(
                context = "${fixture.name} missing clear text",
                request =
                    GpgOpenPgpVerifyTextRequest(
                        signedText = signed,
                        publicKeys = emptyList(),
                    ),
            )
        }
    }

    @Test
    fun `native verification warning semantics match BC`() {
        val fixture = signingFixtures.first { it.name == "legacy Ed25519 plus CV25519" }
        val secretRing = secretRingOf(fixture.secretKeyArmored)
        val publicRing = secretRing.toCertificate()
        val primary = publicRing.publicKey
        val privateKey = secretRing.secretKey.extractPrivateKeyEmptyPassphrase()

        val normalSignature =
            detachedSignature(
                secretKey = secretRing.secretKey,
                text = DETACHED_TEXT,
            )
        val selfRevocation =
            signatureGenerator(
                signingKey = primary,
                signatureType = PGPSignature.KEY_REVOCATION,
                privateKey = privateKey,
            ).generateCertification(primary)
        val revokedRing =
            PGPPublicKeyRing.insertPublicKey(
                publicRing,
                PGPPublicKey.addCertification(primary, selfRevocation),
            )
        val revoked =
            assertDetachedVerificationParity(
                context = "revoked key warning",
                request =
                    GpgOpenPgpVerifyDetachedTextRequest(
                        text = DETACHED_TEXT,
                        signature = normalSignature,
                        publicKeys = listOf(GpgOpenPgpPublicKey(revokedRing.armored())),
                    ),
            )
        assertEquals(
            listOf(GpgOpenPgpVerificationWarning.KEY_REVOKED),
            revoked.warnings,
        )

        val expiredGenerated =
            generator(
                creationTime = EXPIRED_KEY_CREATION_TIME,
            ).generate(
                GpgKeyConfig.Modern(
                    userId = "Expired OpenPGP <expired@test.invalid>",
                    expiry = GpgKeyExpiry.At(EXPIRED_KEY_CREATION_TIME + 1.days),
                ),
            )
        val expiredSecretRing = secretRing(expiredGenerated)
        val expiredKeySignature =
            detachedSignature(
                secretKey = expiredSecretRing.secretKey,
                text = DETACHED_TEXT,
            )
        val expiredKey =
            assertDetachedVerificationParity(
                context = "expired key warning",
                request =
                    GpgOpenPgpVerifyDetachedTextRequest(
                        text = DETACHED_TEXT,
                        signature = expiredKeySignature,
                        publicKeys = listOf(GpgOpenPgpPublicKey(expiredGenerated.publicKeyArmored)),
                    ),
            )
        assertEquals(
            listOf(GpgOpenPgpVerificationWarning.KEY_EXPIRED),
            expiredKey.warnings,
        )

        val expiredSignature =
            detachedSignature(
                secretKey = secretRing.secretKey,
                text = DETACHED_TEXT,
                creationTime = EXPIRED_SIGNATURE_CREATION_TIME,
                expirationSeconds = 60L,
            )
        val signatureExpired =
            assertDetachedVerificationParity(
                context = "expired signature warning",
                request =
                    GpgOpenPgpVerifyDetachedTextRequest(
                        text = DETACHED_TEXT,
                        signature = expiredSignature,
                        publicKeys = listOf(GpgOpenPgpPublicKey(publicRing.armored())),
                    ),
            )
        assertEquals(
            listOf(GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED),
            signatureExpired.warnings,
        )
    }

    private fun assertParserParity(
        context: String,
        armored: String,
        sortKeysByFingerprint: Boolean = false,
    ) {
        val expected =
            BouncyCastleParserOracle
                .parse(armored)
                .withCanonicalArmorForComparison()
                .sortKeysByFingerprintIf(sortKeysByFingerprint)
        val actual =
            nativeParser
                .parse(armored)
                .withCanonicalArmorForComparison()
                .sortKeysByFingerprintIf(sortKeysByFingerprint)
        assertEquals(
            expected = expected,
            actual = actual,
            message = "$context; BC=${expected.packetSummary()}; native=${actual.packetSummary()}",
        )
    }

    private fun assertMetadataParity(
        context: String,
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey> = emptyList(),
    ) {
        assertEquals(
            expected =
                BouncyCastleMetadataOracle.resolve(
                    privateKeyArmored = privateKeyArmored,
                    publicKeyArmored = publicKeyArmored,
                    fingerprint = fingerprint,
                    candidateRevocationKeys = candidateRevocationKeys,
                ),
            actual =
                nativeMetadataResolver.resolve(
                    privateKeyArmored = privateKeyArmored,
                    publicKeyArmored = publicKeyArmored,
                    fingerprint = fingerprint,
                    candidateRevocationKeys = candidateRevocationKeys,
                ),
            message = context,
        )
    }

    private fun assertDetachedVerificationParity(
        context: String,
        request: GpgOpenPgpVerifyDetachedTextRequest,
    ): GpgOpenPgpVerification {
        val expected = BouncyCastleVerificationOracle.verifyDetachedText(request)
        val actual = nativeVerifier.verifyDetachedText(request)
        assertEquals(expected, actual, context)
        return actual
    }

    private fun assertClearTextVerificationParity(
        context: String,
        request: GpgOpenPgpVerifyTextRequest,
    ) {
        val expected = BouncyCastleVerificationOracle.verifyClearSignedText(request)
        assertEquals(
            expected = expected,
            actual = nativeVerifier.verifyClearSignedText(request),
            message = context,
        )
        // The streaming session must agree with the independent BC oracle too.
        val body = Buffer()
        val streamed = nativeService.verifyClearSignedFile(
            GpgOpenPgpReadFileRequest(
                input = request.signedText.encodeToByteArray().toSource(),
                output = body,
                publicKeys = request.publicKeys,
            ),
        )
        assertEquals(expected, streamed.verification, "$context (streamed)")
        assertEquals(body.size, streamed.bodySize, "$context (streamed body size)")
    }

    private fun generator(creationTime: Instant): BcGpgKeyGeneratorTestOracle =
        BcGpgKeyGeneratorTestOracle(
            metadataResolver = BouncyCastleMetadataOracle,
            now = { creationTime },
            timeZone = { TimeZone.UTC },
        )

    private fun publicRing(generated: GeneratedGpgKey): PGPPublicKeyRing =
        parseGpgPublicKeyRingCollection(generated.publicKeyArmored)
            .keyRings
            .next()

    private fun secretRing(generated: GeneratedGpgKey): PGPSecretKeyRing =
        parseGpgSecretKeyRingCollection(generated.privateKeyArmored)
            .keyRings
            .next()

    private fun signatureGenerator(
        signingKey: PGPPublicKey,
        signatureType: Int,
        privateKey: PGPPrivateKey,
    ): PGPSignatureGenerator =
        PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(signingKey.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(gpgBouncyCastleProvider),
            signingKey,
        ).apply {
            init(signatureType, privateKey)
        }

    private fun detachedSignature(
        secretKey: PGPSecretKey,
        text: String,
        creationTime: Instant? = null,
        expirationSeconds: Long? = null,
    ): String {
        val generator =
            PGPSignatureGenerator(
                JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
                    .setProvider(gpgBouncyCastleProvider),
            ).apply {
                init(PGPSignature.BINARY_DOCUMENT, secretKey.extractPrivateKeyEmptyPassphrase())
                if (creationTime != null || expirationSeconds != null) {
                    setHashedSubpackets(
                        org.bouncycastle.openpgp
                            .PGPSignatureSubpacketGenerator()
                            .apply {
                                creationTime?.let { instant ->
                                    setSignatureCreationTime(false, Date(instant.toEpochMilliseconds()))
                                }
                                expirationSeconds?.let { seconds ->
                                    setSignatureExpirationTime(false, seconds)
                                }
                            }.generate(),
                    )
                }
            }
        val data = text.encodeToByteArray()
        generator.update(data, 0, data.size)
        val output = ByteArrayOutputStream()
        ArmoredOutputStream(output).use { armored ->
            BCPGOutputStream(armored).use { packet ->
                generator.generate().encode(packet)
            }
        }
        return output.toString(Charsets.UTF_8)
    }

    private data class AlgorithmFixture(
        val name: String,
        val secretKeyArmored: String,
    )

    private data class PolicyFixture(
        val victim: GeneratedGpgKey,
        val foreign: GeneratedGpgKey,
        val revoker: GeneratedGpgKey,
    )

    private val policyFixture: PolicyFixture by lazy {
        val generator = generator(CERTIFICATE_CREATION_TIME)
        PolicyFixture(
            victim =
                generator.generate(
                    GpgKeyConfig.Modern(
                        userId = "OpenPGP Victim <victim@test.invalid>",
                        expiry = GpgKeyExpiry.Never,
                    ),
                ),
            foreign =
                generator.generate(
                    GpgKeyConfig.Modern(
                        userId = "OpenPGP Foreign <foreign@test.invalid>",
                        expiry = GpgKeyExpiry.Never,
                    ),
                ),
            revoker =
                generator.generate(
                    GpgKeyConfig.Modern(
                        userId = "OpenPGP Revoker <revoker@test.invalid>",
                        expiry = GpgKeyExpiry.Never,
                    ),
                ),
        )
    }

    private companion object {
        const val DETACHED_TEXT = "OpenPGP differential detached signature"
        const val CLEAR_TEXT = "dash escaped\n- marker-like line\nOpenPGP clear text"

        val CERTIFICATE_CREATION_TIME = Instant.parse("2025-01-02T03:04:05Z")
        val CERTIFICATE_EXPIRY_TIME = Instant.parse("2030-01-02T03:04:05Z")
        val EXPIRED_KEY_CREATION_TIME = Instant.parse("2020-01-02T03:04:05Z")
        val EXPIRED_SIGNATURE_CREATION_TIME = Instant.parse("2021-02-03T04:05:06Z")

        val algorithmFixtures =
            listOf(
                AlgorithmFixture("RSA", GpgTestKeyFixtures.RSA),
                AlgorithmFixture("legacy Ed25519 plus CV25519", GpgTestKeyFixtures.CV25519),
                AlgorithmFixture("legacy Ed25519", GpgTestKeyFixtures.ED25519),
                AlgorithmFixture("NIST P-256 ECDSA plus ECDH", GpgTestKeyFixtures.NISTP256),
                AlgorithmFixture("ECDSA", GpgTestKeyFixtures.ECDSA),
            )
        val signingFixtures =
            algorithmFixtures.filter { fixture ->
                fixture.name in setOf("RSA", "legacy Ed25519 plus CV25519", "ECDSA")
            }
        val clearTextSigningFixtures = signingFixtures
    }
}

private object BouncyCastleParserOracle {
    fun parse(armored: String): GpgPublicKeyParseResult {
        if (armored.isBlank()) {
            return GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Empty)
        }
        return runCatching {
            val collection = parseGpgPublicKeyRingCollection(armored)
            val rings = collection.keyRings.asSequence().toList()
            val candidateRevocationKeys =
                rings
                    .asSequence()
                    .flatMap { ring -> ring.publicKeys.asSequence() }
                    .toList()
            val keys = rings.mapNotNull { ring -> parseRing(ring, candidateRevocationKeys) }
            if (keys.isEmpty()) {
                GpgPublicKeyParseResult.Error(GpgPublicKeyParseError.Malformed)
            } else {
                GpgPublicKeyParseResult.Success(keys)
            }
        }.getOrElse { error ->
            GpgPublicKeyParseResult.Error(
                if (error is GpgUnsupportedKeyVersionException) {
                    GpgPublicKeyParseError.UnsupportedKeyVersion
                } else {
                    GpgPublicKeyParseError.Malformed
                },
            )
        }
    }

    private fun parseRing(
        ring: PGPPublicKeyRing,
        candidateRevocationKeys: List<PGPPublicKey>,
    ): GpgPublicKeyInfo? {
        val certificate =
            GpgCertificateInspectorJvm.inspect(
                ring = ring,
                candidateRevocationKeys = candidateRevocationKeys,
            ) ?: return null
        val primary = certificate.primary
        val primaryKey = primary.publicKey
        val certificateRevoked = primary.revoked
        val userIds = certificate.verifiedUserIds
        val subkeys =
            certificate.subkeys
                .asSequence()
                .filter { subkey -> subkey.authenticated }
                .map { subkey ->
                    val key = subkey.publicKey
                    GpgPublicSubKeyInfo(
                        fingerprint = key.fingerprintHex(),
                        keygrip = GpgKeygripCalculatorJvm.calculate(key),
                        keyId = key.keyID.gpgKeyIdHex(),
                        algorithm = gpgAlgorithmName(key.algorithm),
                        bitStrength = key.bitStrength.takeIf { bits -> bits > 0 },
                        canSign =
                            !certificateRevoked &&
                                !subkey.revoked &&
                                (subkey.keyFlags?.canSign() ?: key.isSigningKey()) &&
                                subkey.signingCrossCertified,
                        canEncrypt =
                            !certificateRevoked &&
                                !subkey.revoked &&
                                (subkey.keyFlags?.canEncrypt() ?: key.isEncryptionKey),
                        revoked = subkey.revoked,
                        createdAt =
                            key.creationTime?.let { date ->
                                Instant.fromEpochMilliseconds(date.time)
                            },
                        expiresAt = subkey.expiresAt(),
                    )
                }.toList()
        val primaryCanSign =
            primary.authenticated &&
                !primary.revoked &&
                (primary.keyFlags?.canSign() ?: primaryKey.isSigningKey())
        val certificateCanEncrypt =
            !certificateRevoked &&
                certificate.authenticatedKeys.any { key ->
                    !key.revoked &&
                        (key.keyFlags?.canEncrypt() ?: key.publicKey.isEncryptionKey)
                }
        return GpgPublicKeyInfo(
            fingerprint = primaryKey.fingerprintHex(),
            keygrip = GpgKeygripCalculatorJvm.calculate(primaryKey),
            keyId = primaryKey.keyID.gpgKeyIdHex(),
            algorithm = gpgAlgorithmName(primaryKey.algorithm),
            bitStrength = primaryKey.bitStrength.takeIf { bits -> bits > 0 },
            userIds = userIds,
            emails = userIds.mapNotNull(::extractGpgUserIdEmail).distinct(),
            createdAt =
                primaryKey.creationTime?.let { date ->
                    Instant.fromEpochMilliseconds(date.time)
                },
            expiresAt = primary.expiresAt(),
            revoked = primary.revoked,
            canSign = primaryCanSign || subkeys.any { subkey -> subkey.canSign },
            canEncrypt = certificateCanEncrypt,
            publicKeyArmored = ring.armored(),
            subKeys = subkeys,
        )
    }
}

private object BouncyCastleMetadataOracle : GpgKeyMetadataResolver {
    override fun resolve(
        privateKeyArmored: String?,
        publicKeyArmored: String?,
        fingerprint: String?,
        candidateRevocationKeys: List<GpgOpenPgpPublicKey>,
    ): GpgAgentKeyMetadata? {
        val externalRevocationKeys = candidateRevocationKeys.parseGpgPublicKeyCandidates()
        return privateKeyArmored
            ?.takeIf { armored -> armored.isNotBlank() }
            ?.let { armored -> parsePrivate(armored, fingerprint, externalRevocationKeys) }
            ?: publicKeyArmored
                ?.takeIf { armored -> armored.isNotBlank() }
                ?.let { armored -> parsePublic(armored, fingerprint, externalRevocationKeys) }
    }

    private fun parsePrivate(
        armored: String,
        fingerprint: String?,
        externalRevocationKeys: List<PGPPublicKey>,
    ): GpgAgentKeyMetadata? =
        runCatching {
            val rings = parseGpgSecretKeyRingCollection(armored).keyRings.asSequence().toList()
            val candidates =
                buildList {
                    rings.forEach { ring -> ring.publicKeys.asSequence().forEach(::add) }
                    addAll(externalRevocationKeys)
                }
            rings
                .asSequence()
                .filterSecretRingsByFingerprint(fingerprint)
                .mapNotNull { ring ->
                    GpgCertificateInspectorJvm.inspect(
                        ring = ring.toCertificate(),
                        candidateRevocationKeys = candidates,
                    )
                }.toMetadataOrNull()
        }.getOrNull()

    private fun parsePublic(
        armored: String,
        fingerprint: String?,
        externalRevocationKeys: List<PGPPublicKey>,
    ): GpgAgentKeyMetadata? =
        runCatching {
            val rings = parseGpgPublicKeyRingCollection(armored).keyRings.asSequence().toList()
            val candidates =
                buildList {
                    rings.forEach { ring -> ring.publicKeys.asSequence().forEach(::add) }
                    addAll(externalRevocationKeys)
                }
            rings
                .asSequence()
                .filterPublicRingsByFingerprint(fingerprint)
                .mapNotNull { ring ->
                    GpgCertificateInspectorJvm.inspect(
                        ring = ring,
                        candidateRevocationKeys = candidates,
                    )
                }.toMetadataOrNull()
        }.getOrNull()

    private fun Sequence<GpgCertificateInspectorJvm>.toMetadataOrNull(): GpgAgentKeyMetadata? {
        val keys =
            flatMap { certificate ->
                if (!certificate.primary.authenticated || certificate.primary.revoked) {
                    return@flatMap emptySequence()
                }
                certificate.authenticatedKeys.asSequence().map { key ->
                    MetadataKey(
                        key = key,
                        primary = key === certificate.primary,
                        certificateRevoked = certificate.primary.revoked,
                    )
                }
            }.mapNotNull { inspected -> inspected.toMetadataKeyOrNull() }
                .toList()
        return GpgAgentKeyMetadata(version = 1, keys = keys)
            .takeIf { metadata -> metadata.keys.isNotEmpty() }
    }

    private fun MetadataKey.toMetadataKeyOrNull(): GpgAgentKeyMetadataKey? {
        val capabilities =
            if (certificateRevoked || key.revoked) {
                emptySet()
            } else {
                buildSet {
                    val canSign = key.keyFlags?.canSign() ?: key.publicKey.isSigningKey()
                    if (canSign && (primary || key.signingCrossCertified)) add("sign")
                    if (key.keyFlags?.canEncrypt() ?: key.publicKey.isEncryptionKey) add("decrypt")
                }
            }
        if (capabilities.isEmpty() && !primary) return null
        val keygrip =
            runCatching {
                GpgKeygripCalculatorJvm.calculate(key.publicKey)
            }.getOrNull() ?: return null
        return GpgAgentKeyMetadataKey(
            keygrip = keygrip,
            fingerprint = key.publicKey.fingerprintHex(),
            algorithm = gpgAlgorithmName(key.publicKey.algorithm),
            capabilities = capabilities,
        )
    }

    private fun Sequence<PGPSecretKeyRing>.filterSecretRingsByFingerprint(fingerprint: String?): Sequence<PGPSecretKeyRing> {
        val normalized = fingerprint.normalizedOrNull() ?: return this
        return filter { ring ->
            ring.publicKeys.asSequence().any { key ->
                key.fingerprintHex().normalizeGpgFingerprint() == normalized
            }
        }
    }

    private fun Sequence<PGPPublicKeyRing>.filterPublicRingsByFingerprint(fingerprint: String?): Sequence<PGPPublicKeyRing> {
        val normalized = fingerprint.normalizedOrNull() ?: return this
        return filter { ring ->
            ring.publicKeys.asSequence().any { key ->
                key.fingerprintHex().normalizeGpgFingerprint() == normalized
            }
        }
    }

    private fun String?.normalizedOrNull(): String? =
        this
            ?.normalizeGpgFingerprint()
            ?.takeIf { fingerprint -> fingerprint.isNotEmpty() }

    private data class MetadataKey(
        val key: GpgVerifiedCertificateKeyJvm,
        val primary: Boolean,
        val certificateRevoked: Boolean,
    )
}

private object BouncyCastleVerificationOracle {
    fun verifyClearSignedText(request: GpgOpenPgpVerifyTextRequest): GpgOpenPgpVerification {
        val rings = parseCandidates(request.publicKeys)
        val clearSignedMessage = parseClearSignedMessage(request.signedText)
        val signatures =
            readSignatureList(
                PGPUtil.getDecoderStream(
                    ByteArrayInputStream(clearSignedMessage.signatureArmored.encodeToByteArray()),
                ),
            )
        val (signature, publicKey) = selectVerifiableSignature(signatures, rings)
        if (publicKey == null) return missingPublicKey(signature)
        signature.init(verifierProvider(), publicKey)
        clearSignedMessage.lines.forEachIndexed { index, line ->
            if (index > 0) {
                signature.update('\r'.code.toByte())
                signature.update('\n'.code.toByte())
            }
            if (line.isNotEmpty()) signature.update(line)
        }
        return verificationResult(signature, publicKey, rings, signature.verify())
    }

    fun verifyDetachedText(request: GpgOpenPgpVerifyDetachedTextRequest): GpgOpenPgpVerification {
        val rings = parseCandidates(request.publicKeys)
        val signatures =
            readSignatureList(
                PGPUtil.getDecoderStream(ByteArrayInputStream(request.signature.encodeToByteArray())),
            )
        val (signature, publicKey) = selectVerifiableSignature(signatures, rings)
        if (publicKey == null) return missingPublicKey(signature)
        signature.init(verifierProvider(), publicKey)
        val data = request.text.encodeToByteArray()
        signature.update(data, 0, data.size)
        return verificationResult(signature, publicKey, rings, signature.verify())
    }

    private fun parseCandidates(publicKeys: List<GpgOpenPgpPublicKey>): List<PGPPublicKeyRing> =
        publicKeys.flatMap { candidate ->
            parseGpgPublicKeyRingCollection(candidate.armored)
                .keyRings
                .asSequence()
                .toList()
        }

    private fun readSignatureList(input: InputStream): PGPSignatureList {
        var value = JcaPGPObjectFactory(input).nextObject()
        if (value is PGPCompressedData) {
            value = JcaPGPObjectFactory(value.dataStream).nextObject()
        }
        return value as? PGPSignatureList
            ?: error("The input does not contain a GPG signature.")
    }

    private fun selectVerifiableSignature(
        signatures: PGPSignatureList,
        rings: List<PGPPublicKeyRing>,
    ): Pair<PGPSignature, PGPPublicKey?> {
        for (index in 0 until signatures.size()) {
            val signature = signatures[index]
            val key =
                rings
                    .asSequence()
                    .flatMap { ring -> ring.publicKeys.asSequence() }
                    .firstOrNull { candidate -> candidate.keyID == signature.keyID }
            if (key != null) return signature to key
        }
        return signatures[0] to null
    }

    private fun verificationResult(
        signature: PGPSignature,
        publicKey: PGPPublicKey,
        rings: List<PGPPublicKeyRing>,
        valid: Boolean,
    ): GpgOpenPgpVerification {
        val now = Clock.System.now()
        val candidates =
            rings
                .asSequence()
                .flatMap { ring -> ring.publicKeys.asSequence() }
                .toList()
        val inspected =
            rings
                .asSequence()
                .mapNotNull { ring ->
                    GpgCertificateInspectorJvm.inspect(
                        ring = ring,
                        candidateRevocationKeys = candidates,
                        referenceTime = now,
                    )
                }.mapNotNull { certificate ->
                    certificate.keys
                        .firstOrNull { key ->
                            key.publicKey.fingerprint.contentEquals(publicKey.fingerprint)
                        }?.let { key -> certificate to key }
                }.firstOrNull()
        val certificate = inspected?.first
        val signer =
            inspected?.second?.takeIf { key ->
                key.authenticated && (key.publicKey.isMasterKey || key.signingCrossCertified)
            }
        return GpgOpenPgpVerification(
            status =
                if (valid) {
                    GpgOpenPgpVerificationStatus.VALID
                } else {
                    GpgOpenPgpVerificationStatus.INVALID
                },
            keyId = signature.keyID.gpgKeyIdHex(),
            fingerprint = publicKey.fingerprintHex(),
            userIds = certificate?.takeIf { signer != null }?.verifiedUserIds.orEmpty(),
            createdAt =
                signature.creationTime?.let { date ->
                    Instant.fromEpochMilliseconds(date.time)
                },
            warnings =
                buildList {
                    if (signer != null && (certificate?.primary?.revoked == true || signer.revoked)) {
                        add(GpgOpenPgpVerificationWarning.KEY_REVOKED)
                    }
                    if (
                        signer != null &&
                        (certificate?.primary?.isExpired(now) == true || signer.isExpired(now))
                    ) {
                        add(GpgOpenPgpVerificationWarning.KEY_EXPIRED)
                    }
                    if (signature.isExpiredAt(now)) {
                        add(GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED)
                    }
                },
        )
    }

    private fun missingPublicKey(signature: PGPSignature): GpgOpenPgpVerification =
        GpgOpenPgpVerification(
            status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY,
            keyId = signature.keyID.gpgKeyIdHex(),
            fingerprint = null,
            userIds = emptyList(),
            createdAt =
                signature.creationTime?.let { date ->
                    Instant.fromEpochMilliseconds(date.time)
                },
        )

    private fun verifierProvider(): JcaPGPContentVerifierBuilderProvider =
        JcaPGPContentVerifierBuilderProvider().setProvider(gpgBouncyCastleProvider)
}

private fun GpgPublicKeyParseResult.withCanonicalArmorForComparison(): GpgPublicKeyParseResult =
    when (this) {
        is GpgPublicKeyParseResult.Error -> {
            this
        }

        is GpgPublicKeyParseResult.Success -> {
            copy(
                keys =
                    keys.map { key ->
                        key.copy(
                            publicKeyArmored = key.publicKeyArmored.canonicalGpgArmorForComparison(),
                        )
                    },
            )
        }
    }

private fun GpgPublicKeyParseResult.sortKeysByFingerprintIf(enabled: Boolean): GpgPublicKeyParseResult =
    if (enabled && this is GpgPublicKeyParseResult.Success) {
        copy(keys = keys.sortedBy(GpgPublicKeyInfo::fingerprint))
    } else {
        this
    }

private fun GpgPublicKeyParseResult.requireSuccess(): List<GpgPublicKeyInfo> =
    (this as? GpgPublicKeyParseResult.Success)?.keys
        ?: error("Expected a successful OpenPGP parse, got $this")

private fun GpgPublicKeyParseResult.packetSummary(): String =
    when (this) {
        is GpgPublicKeyParseResult.Error -> {
            toString()
        }

        is GpgPublicKeyParseResult.Success -> {
            keys.joinToString(prefix = "[", postfix = "]") { key ->
                "${key.fingerprint}:${key.publicKeyArmored.packetTags()}"
            }
        }
    }

private fun String.packetTags(): List<String> =
    BCPGInputStream(
        PGPUtil.getDecoderStream(ByteArrayInputStream(encodeToByteArray())),
    ).use { input ->
        buildList {
            while (input.nextPacketTag() >= 0) {
                val packet = input.readPacket()
                add(
                    if (packet is SignaturePacket) {
                        "SIGNATURE(${packet.signatureType})"
                    } else {
                        packet::class
                            .simpleName
                            .orEmpty()
                            .removeSuffix("Packet")
                            .uppercase()
                    },
                )
            }
        }
    }

private fun GpgVerifiedCertificateKeyJvm.expiresAt(): Instant? {
    if (validSeconds <= 0L) return null
    val creationTime = publicKey.creationTime ?: return null
    return Instant.fromEpochMilliseconds(creationTime.time + validSeconds * 1_000L)
}

private fun GpgVerifiedCertificateKeyJvm.isExpired(now: Instant): Boolean = expiresAt()?.let { expiry -> expiry <= now } == true

private fun Int.canSign(): Boolean = this and KeyFlags.SIGN_DATA != 0

private fun Int.canEncrypt(): Boolean = this and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0

private fun publicKeyArmoredOf(secretKeyArmored: String): String = publicRingOf(secretKeyArmored).armored()

private fun publicRingOf(secretKeyArmored: String): PGPPublicKeyRing = secretRingOf(secretKeyArmored).toCertificate()

private fun secretRingOf(secretKeyArmored: String): PGPSecretKeyRing =
    parseGpgSecretKeyRingCollection(secretKeyArmored)
        .keyRings
        .next()

private fun armoredCollection(vararg rings: PGPPublicKeyRing): String {
    val output = ByteArrayOutputStream()
    ArmoredOutputStream(output).use { armored ->
        rings.forEach { ring -> ring.encode(armored) }
    }
    return output.toString(Charsets.UTF_8)
}
