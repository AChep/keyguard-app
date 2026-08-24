package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GPG_KEY_EXPIRATION_MAX_INSTANT
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationError
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationResult
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationService
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SignatureSubpacketTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant

class GpgKeyExpirationServiceJvm(
    private val metadataResolver: GpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
    private val now: () -> Instant = { Clock.System.now() },
    private val waitForClock: (milliseconds: Long) -> Unit = { milliseconds ->
        Thread.sleep(milliseconds)
    },
) : GpgKeyExpirationService {
    private val renewalPolicy = GpgRenewalPolicyJvm(
        now = now,
        waitForClock = waitForClock,
    )

    constructor(
        directDI: DirectDI,
    ) : this(
        metadataResolver = directDI.instance(),
    )

    override fun update(
        request: GpgKeyExpirationRequest,
    ): GpgKeyExpirationResult {
        if (request.key.privateKeyArmored.isBlank()) {
            return error(GpgKeyExpirationError.EmptyPrivateKey)
        }
        if (request.change.componentFingerprints.isEmpty()) {
            return error(GpgKeyExpirationError.NoComponentsSelected)
        }
        val expiration = request.change.expiresAt
        if (expiration != null && expiration <= now()) {
            return error(GpgKeyExpirationError.InvalidExpiration)
        }
        if (expiration != null && expiration > GPG_KEY_EXPIRATION_MAX_INSTANT) {
            return error(GpgKeyExpirationError.InvalidExpiration)
        }

        return try {
            updateOrThrow(request)
        } catch (e: ExpirationUpdateException) {
            error(e.reason)
        } catch (_: Exception) {
            error(GpgKeyExpirationError.InternalFailure)
        }
    }

    private fun updateOrThrow(
        request: GpgKeyExpirationRequest,
    ): GpgKeyExpirationResult.Success {
        val candidateRevocationKeys = request.candidateRevocationKeys
            .parseGpgPublicKeyCandidates()
        val secretCollection = parseOrFail(GpgKeyExpirationError.MalformedKey) {
            parseGpgSecretKeyRingCollection(request.key.privateKeyArmored)
        }
        if (secretCollection.size() != 1) {
            fail(GpgKeyExpirationError.MalformedKey)
        }
        val secretRing = secretCollection.keyRings.asSequence().single()
        val secretCertificate = secretRing.toCertificate()
        val certificate = reconcileAndVerifySuppliedCertificate(
            armored = request.key.publicKeyArmored,
            secretCertificate = secretCertificate,
            candidateRevocationKeys = candidateRevocationKeys,
        )
        // The stored public certificate may have received certifications or
        // revocations after the private key was imported (for example, through a
        // keyserver refresh). Use that certificate as the source of truth and
        // synchronize it into the working secret ring before replacing expiry
        // signatures, so unrelated public packets are preserved in both outputs.
        val workingSecretRing = synchronizePublicKeys(
            secretRing = secretRing,
            certificate = certificate,
        )
        val certificateInspector = GpgCertificateInspectorJvm.inspect(
            ring = certificate,
            candidateRevocationKeys = candidateRevocationKeys,
            referenceTime = now(),
        )
            ?: fail(GpgKeyExpirationError.MalformedKey)
        val primary = certificateInspector.primary.publicKey
        val canonicalPrimaryFingerprint = primary.fingerprintHex()
        val expectedPrimaryFingerprint = request.key.fingerprint.normalizeGpgFingerprint()
        if (
            expectedPrimaryFingerprint.isNotEmpty() &&
            canonicalPrimaryFingerprint.normalizeGpgFingerprint() != expectedPrimaryFingerprint
        ) {
            fail(GpgKeyExpirationError.FingerprintMismatch)
        }
        when (certificateInspector.primary.revocationStatus) {
            GpgRevocationStatusJvm.Revoked -> fail(GpgKeyExpirationError.RevokedComponent)
            is GpgRevocationStatusJvm.Unresolved ->
                fail(GpgKeyExpirationError.UnresolvedRevocationAuthority)

            GpgRevocationStatusJvm.NotRevoked -> Unit
        }

        val originalKeys = certificate.publicKeys.asSequence().toList()
        if (originalKeys.any { it.version != 4 }) {
            fail(GpgKeyExpirationError.UnsupportedKeyVersion)
        }
        val selected = request.change.componentFingerprints
            .map { it.normalizeGpgFingerprint() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (selected.size != request.change.componentFingerprints.size) {
            fail(GpgKeyExpirationError.ComponentNotFound)
        }
        val keysByFingerprint = originalKeys.associateBy {
            it.fingerprintHex().normalizeGpgFingerprint()
        }
        val inspectedKeysByFingerprint = certificateInspector.keys.associateBy {
            it.publicKey.fingerprintHex().normalizeGpgFingerprint()
        }
        if (!keysByFingerprint.keys.containsAll(selected)) {
            fail(GpgKeyExpirationError.ComponentNotFound)
        }
        selected.forEach { fingerprint ->
            when (inspectedKeysByFingerprint[fingerprint]?.revocationStatus) {
                GpgRevocationStatusJvm.Revoked ->
                    fail(GpgKeyExpirationError.RevokedComponent)

                is GpgRevocationStatusJvm.Unresolved ->
                    fail(GpgKeyExpirationError.UnresolvedRevocationAuthority)

                GpgRevocationStatusJvm.NotRevoked -> Unit
                null -> fail(GpgKeyExpirationError.ComponentNotFound)
            }
        }

        val primarySelected = primary.fingerprintHex().normalizeGpgFingerprint() in selected
        if (primarySelected && certificateInspector.hasUnresolvedIdentityRevocations()) {
            fail(GpgKeyExpirationError.UnresolvedRevocationAuthority)
        }
        val primarySecret = requireUnprotectedSecret(workingSecretRing.getSecretKey(primary.keyID))
        val primaryPrivate = primarySecret.extractPrivateKeyEmptyPassphrase()
        var updatedPrimary = primary
        if (primarySelected) {
            updatedPrimary = updatePrimary(
                primary = primary,
                primaryPrivate = primaryPrivate,
                expiresAt = request.change.expiresAt,
                certificateInspector = certificateInspector,
            )
        }

        val updatedKeys = originalKeys.map { originalKey ->
            if (originalKey.isMasterKey || originalKey.keyID == primary.keyID) {
                updatedPrimary
            } else if (originalKey.fingerprintHex().normalizeGpgFingerprint() in selected) {
                updateSubkey(
                    primary = updatedPrimary,
                    primaryPrivate = primaryPrivate,
                    subkey = originalKey,
                    secretRing = workingSecretRing,
                    expiresAt = request.change.expiresAt,
                    certificateInspector = certificateInspector,
                )
            } else {
                originalKey
            }
        }
        val updatedCertificate = PGPPublicKeyRing(updatedKeys)
        val updatedSecretRing = synchronizePublicKeys(
            secretRing = workingSecretRing,
            certificate = updatedCertificate,
        )
        validateUpdatedCertificate(
            before = certificate,
            after = updatedCertificate,
            selected = selected,
            expiresAt = request.change.expiresAt,
            candidateRevocationKeys = candidateRevocationKeys,
        )

        val privateKeyArmored = updatedSecretRing.armored()
        val publicKeyArmored = updatedSecretRing.toCertificate().armored()
        val reparsedSecret = parseOrFail(GpgKeyExpirationError.SignatureVerificationFailed) {
            parseGpgSecretKeyRingCollection(privateKeyArmored)
        }
        val reparsedPublic = parseOrFail(GpgKeyExpirationError.SignatureVerificationFailed) {
            parseGpgPublicKeyRingCollection(publicKeyArmored)
        }
        if (reparsedSecret.size() != 1 || reparsedPublic.size() != 1) {
            fail(GpgKeyExpirationError.SignatureVerificationFailed)
        }
        val reparsedCertificate = reparsedSecret.keyRings.asSequence().single().toCertificate()
        val reparsedPublicRing = reparsedPublic.keyRings.asSequence().single()
        if (!reparsedCertificate.encoded.contentEquals(reparsedPublicRing.encoded)) {
            fail(GpgKeyExpirationError.SignatureVerificationFailed)
        }

        val resolvedMetadata = metadataResolver.resolve(
            privateKeyArmored = privateKeyArmored,
            publicKeyArmored = publicKeyArmored,
            fingerprint = canonicalPrimaryFingerprint,
            candidateRevocationKeys = request.candidateRevocationKeys,
        ) ?: fail(GpgKeyExpirationError.MetadataResolutionFailed)
        return GpgKeyExpirationResult.Success(
            key = request.key.copy(
                privateKeyArmored = privateKeyArmored,
                publicKeyArmored = publicKeyArmored,
                fingerprint = canonicalPrimaryFingerprint,
                metadata = resolvedMetadata.metadata,
            ),
        )
    }

    private fun updatePrimary(
        primary: PGPPublicKey,
        primaryPrivate: PGPPrivateKey,
        expiresAt: Instant?,
        certificateInspector: GpgCertificateInspectorJvm,
    ): PGPPublicKey {
        var updated = primary
        var signaturesUpdated = 0

        val directSignatures = certificateInspector.effectiveDirectKeySignatures()
        if (directSignatures.isNotEmpty()) {
            directSignatures.forEach { signature ->
                updated = PGPPublicKey.removeCertification(updated, signature)
            }
            directSignatures.forEach { template ->
                // Keyguard-generated certificates can carry key expiry on direct signatures,
                // unlike the UID-centric layout normally rewritten by gpg --quick-set-expire.
                // Renew every effective direct signature so that layout remains interoperable,
                // while preserving independent policy such as designated-revoker declarations.
                val replacement = createSignature(
                    template = template,
                    signingKey = primary,
                    privateKey = primaryPrivate,
                    expiresAt = expiresAt,
                ) { generator -> generator.generateCertification(primary) }
                updated = PGPPublicKey.addCertification(updated, replacement)
                signaturesUpdated++
            }
        }

        primary.rawUserIDs.asSequence().toList().forEach { rawUserId ->
            val certifications = certificateInspector.verifiedUserIdCertifications(rawUserId)
            if (certifications.isEmpty()) return@forEach
            val revoked = certificateInspector.verifiedUserIdRevocations(rawUserId).isNotEmpty()
            if (revoked) return@forEach
            val template = certificateInspector.effectiveUserIdCertification(rawUserId)
                ?: return@forEach
            certifications.forEach { signature ->
                updated = PGPPublicKey.removeCertification(updated, rawUserId, signature)
            }
            val userId = rawUserId.decodeToString()
            if (!userId.encodeToByteArray().contentEquals(rawUserId)) {
                fail(GpgKeyExpirationError.MalformedKey)
            }
            val replacement = createSignature(
                template = template,
                signingKey = primary,
                privateKey = primaryPrivate,
                expiresAt = expiresAt,
            ) { generator -> generator.generateCertification(userId, primary) }
            updated = PGPPublicKey.addCertification(updated, rawUserId, replacement)
            signaturesUpdated++
        }

        primary.userAttributes.asSequence().toList().forEach { attribute ->
            val certifications = certificateInspector.verifiedUserAttributeCertifications(attribute)
            if (certifications.isEmpty()) return@forEach
            val revoked = certificateInspector
                .verifiedUserAttributeRevocations(attribute)
                .isNotEmpty()
            if (revoked) return@forEach
            val template = certificateInspector.effectiveUserAttributeCertification(attribute)
                ?: return@forEach
            certifications.forEach { signature ->
                updated = PGPPublicKey.removeCertification(updated, attribute, signature)
            }
            val replacement = createSignature(
                template = template,
                signingKey = primary,
                privateKey = primaryPrivate,
                expiresAt = expiresAt,
            ) { generator -> generator.generateCertification(attribute, primary) }
            updated = PGPPublicKey.addCertification(updated, attribute, replacement)
            signaturesUpdated++
        }

        if (signaturesUpdated == 0) {
            fail(GpgKeyExpirationError.MissingSelfSignature)
        }
        return updated
    }

    private fun updateSubkey(
        primary: PGPPublicKey,
        primaryPrivate: PGPPrivateKey,
        subkey: PGPPublicKey,
        secretRing: PGPSecretKeyRing,
        expiresAt: Instant?,
        certificateInspector: GpgCertificateInspectorJvm,
    ): PGPPublicKey {
        val bindings = certificateInspector.effectiveSubkeyBindings(subkey)
        val template = bindings.maxByOrNull { it.creationTime.time }
            ?: fail(GpgKeyExpirationError.MissingSelfSignature)
        var updated = subkey
        bindings.forEach { signature ->
            updated = PGPPublicKey.removeCertification(updated, signature)
        }

        val hashedSubpackets = template.hashedSubPackets
        val signingSubkey = if (
            hashedSubpackets?.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS) == true
        ) {
            hashedSubpackets.keyFlags and KeyFlags.SIGN_DATA != 0
        } else {
            subkey.isSigningKey()
        }
        val embeddedSignature = if (signingSubkey) {
            // The embedded cross-signature authenticates the primary/subkey pair;
            // expiry is carried by the outer subkey-binding signature. Reuse an
            // existing verified proof when present, which also permits a refreshed
            // public-only signing subkey to be renewed with the primary secret.
            template.validEmbeddedPrimaryKeyBinding(primary, subkey)
                ?: run {
                    val subkeySecret = requireUnprotectedSecret(secretRing.getSecretKey(subkey.keyID))
                    val subkeyPrivate = subkeySecret.extractPrivateKeyEmptyPassphrase()
                    createPrimaryKeyBinding(
                        bindingTemplate = template,
                        primary = primary,
                        subkey = subkey,
                        subkeyPrivate = subkeyPrivate,
                    )
                }
        } else {
            null
        }
        val replacement = createSignature(
            template = template,
            signingKey = primary,
            expiringKey = subkey,
            privateKey = primaryPrivate,
            expiresAt = expiresAt,
            embeddedSignature = embeddedSignature,
        ) { generator -> generator.generateCertification(primary, subkey) }
        return PGPPublicKey.addCertification(updated, replacement)
    }

    private fun createPrimaryKeyBinding(
        bindingTemplate: PGPSignature,
        primary: PGPPublicKey,
        subkey: PGPPublicKey,
        subkeyPrivate: PGPPrivateKey,
    ): PGPSignature {
        val oldEmbedded = bindingTemplate.validEmbeddedPrimaryKeyBinding(primary, subkey)
        val templateHashAlgorithm = oldEmbedded?.hashAlgorithm
            ?: bindingTemplate.hashAlgorithm.takeIf { it > 0 }
            ?: HashAlgorithmTags.SHA256
        val hashAlgorithm = renewalPolicy.replacementHashAlgorithm(
            signingAlgorithm = subkey.algorithm,
            templateHashAlgorithm = templateHashAlgorithm,
        )
        val generator = signatureGenerator(
            signingKey = subkey,
            privateKey = subkeyPrivate,
            signatureType = PGPSignature.PRIMARYKEY_BINDING,
            hashAlgorithm = hashAlgorithm,
        )
        val hashed = PGPSignatureSubpacketGenerator(oldEmbedded?.hashedSubPackets)
        hashed.removePacketsOfType(SignatureSubpacketTags.CREATION_TIME)
        val replacementCreationTime = replacementSignatureCreationTime(
            oldEmbedded ?: bindingTemplate,
        )
        hashed.setSignatureCreationTime(
            true,
            replacementCreationTime,
        )
        oldEmbedded?.let { template ->
            preserveSignatureExpiration(
                template = template,
                replacementCreationTime = replacementCreationTime,
                hashed = hashed,
            )
        }
        generator.setHashedSubpackets(hashed.generate())
        generator.setUnhashedSubpackets(oldEmbedded?.unhashedSubPackets)
        return generator.generateCertification(primary, subkey).also { signature ->
            if (!signature.verifiesSubkeyCertification(primary, subkey, signer = subkey)) {
                fail(GpgKeyExpirationError.SignatureVerificationFailed)
            }
        }
    }

    private fun PGPSignature.validEmbeddedPrimaryKeyBinding(
        primary: PGPPublicKey,
        subkey: PGPPublicKey,
    ): PGPSignature? = runCatching {
        hashedSubPackets?.embeddedSignatures
            ?.asSequence()
            ?.filter { it.signatureType == PGPSignature.PRIMARYKEY_BINDING }
            ?.filter { it.verifiesSubkeyCertification(primary, subkey, signer = subkey) }
            ?.maxByOrNull { it.creationTime.time }
    }.getOrNull()

    private fun createSignature(
        template: PGPSignature,
        signingKey: PGPPublicKey,
        expiringKey: PGPPublicKey = signingKey,
        privateKey: PGPPrivateKey,
        expiresAt: Instant?,
        embeddedSignature: PGPSignature? = null,
        generate: (PGPSignatureGenerator) -> PGPSignature,
    ): PGPSignature {
        val generator = signatureGenerator(
            signingKey = signingKey,
            privateKey = privateKey,
            signatureType = template.signatureType,
            hashAlgorithm = renewalPolicy.replacementHashAlgorithm(
                signingAlgorithm = signingKey.algorithm,
                templateHashAlgorithm = template.hashAlgorithm,
            ),
        )
        val hashed = PGPSignatureSubpacketGenerator(template.hashedSubPackets)
        hashed.removePacketsOfType(SignatureSubpacketTags.CREATION_TIME)
        hashed.removePacketsOfType(SignatureSubpacketTags.KEY_EXPIRE_TIME)
        if (embeddedSignature != null) {
            hashed.removePacketsOfType(SignatureSubpacketTags.EMBEDDED_SIGNATURE)
            hashed.addEmbeddedSignature(false, embeddedSignature)
        }
        val replacementCreationTime = replacementSignatureCreationTime(template)
        hashed.setSignatureCreationTime(true, replacementCreationTime)
        preserveSignatureExpiration(
            template = template,
            replacementCreationTime = replacementCreationTime,
            hashed = hashed,
        )
        expiresAt?.let { target ->
            hashed.setKeyExpirationTime(true, expirationSeconds(key = expiringKey, target = target))
        }
        generator.setHashedSubpackets(hashed.generate())
        generator.setUnhashedSubpackets(template.unhashedSubPackets)
        return generate(generator)
    }

    private fun preserveSignatureExpiration(
        template: PGPSignature,
        replacementCreationTime: Date,
        hashed: PGPSignatureSubpacketGenerator,
    ) {
        val templateDuration = template.hashedSubPackets?.signatureExpirationTime
            ?: return
        val replacementDuration = renewalPolicy.replacementSignatureExpirationDuration(
            templateCreationTime = template.creationTime,
            templateDurationSeconds = templateDuration,
            replacementCreationTime = replacementCreationTime,
        ) ?: return
        hashed.removePacketsOfType(SignatureSubpacketTags.EXPIRE_TIME)
        hashed.setSignatureExpirationTime(true, replacementDuration)
    }

    private fun replacementSignatureCreationTime(
        template: PGPSignature,
    ): Date = renewalPolicy
        .replacementSignatureCreationTime(template.creationTime)
        ?: fail(GpgKeyExpirationError.TimeConflict)

    private fun signatureGenerator(
        signingKey: PGPPublicKey,
        privateKey: PGPPrivateKey,
        signatureType: Int,
        hashAlgorithm: Int,
    ): PGPSignatureGenerator = PGPSignatureGenerator(
        JcaPGPContentSignerBuilder(signingKey.algorithm, hashAlgorithm)
            .setProvider(gpgBouncyCastleProvider),
        signingKey,
    ).apply {
        init(signatureType, privateKey)
    }

    private fun expirationSeconds(
        key: PGPPublicKey,
        target: Instant,
    ): Long {
        if (target > GPG_KEY_EXPIRATION_MAX_INSTANT) {
            fail(GpgKeyExpirationError.InvalidExpiration)
        }
        val seconds = target.epochSeconds - key.creationTime.toInstant().epochSecond
        if (seconds <= 0L || seconds > UInt.MAX_VALUE.toLong()) {
            fail(GpgKeyExpirationError.InvalidExpiration)
        }
        return seconds
    }

    private fun validateUpdatedCertificate(
        before: PGPPublicKeyRing,
        after: PGPPublicKeyRing,
        selected: Set<String>,
        expiresAt: Instant?,
        candidateRevocationKeys: List<PGPPublicKey>,
    ) {
        val afterInspector = GpgCertificateInspectorJvm.inspect(
            ring = after,
            candidateRevocationKeys = candidateRevocationKeys,
            referenceTime = now(),
        )
            ?: fail(GpgKeyExpirationError.SignatureVerificationFailed)
        val afterKeysByFingerprint = afterInspector.authenticatedKeys.associateBy {
            it.publicKey.fingerprintHex().normalizeGpgFingerprint()
        }
        val beforeKeys = before.publicKeys.asSequence().toList()
        val afterKeys = after.publicKeys.asSequence().toList()
        if (beforeKeys.map { it.fingerprintHex() } != afterKeys.map { it.fingerprintHex() }) {
            fail(GpgKeyExpirationError.SignatureVerificationFailed)
        }
        beforeKeys.zip(afterKeys).forEach { (old, updated) ->
            if (GpgKeygripCalculatorJvm.calculate(old) != GpgKeygripCalculatorJvm.calculate(updated)) {
                fail(GpgKeyExpirationError.SignatureVerificationFailed)
            }
            if (updated.fingerprintHex().normalizeGpgFingerprint() in selected) {
                val expectedSeconds = expiresAt?.let { expirationSeconds(updated, it) } ?: 0L
                val inspected = afterKeysByFingerprint[
                    updated.fingerprintHex().normalizeGpgFingerprint()
                ] ?: fail(GpgKeyExpirationError.SignatureVerificationFailed)
                if (inspected.validSeconds != expectedSeconds) {
                    fail(GpgKeyExpirationError.SignatureVerificationFailed)
                }
                if (!inspected.authenticated) {
                    fail(GpgKeyExpirationError.SignatureVerificationFailed)
                }
            }
        }
    }

    private fun reconcileAndVerifySuppliedCertificate(
        armored: String,
        secretCertificate: PGPPublicKeyRing,
        candidateRevocationKeys: List<PGPPublicKey>,
    ): PGPPublicKeyRing {
        if (armored.isBlank()) {
            fail(GpgKeyExpirationError.FingerprintMismatch)
        }
        val publicCollection = parseOrFail(GpgKeyExpirationError.FingerprintMismatch) {
            parseGpgPublicKeyRingCollection(armored)
        }
        if (publicCollection.size() != 1) {
            fail(GpgKeyExpirationError.FingerprintMismatch)
        }
        val supplied = publicCollection.keyRings.asSequence().single()
        val suppliedKeys = supplied.publicKeys.asSequence().toList()
        val secretKeys = secretCertificate.publicKeys.asSequence().toList()
        val suppliedPrimary = GpgCertificateInspectorJvm.inspect(
            ring = supplied,
            referenceTime = now(),
        )
            ?.primary
            ?.publicKey
            ?: fail(GpgKeyExpirationError.FingerprintMismatch)
        val secretPrimary = GpgCertificateInspectorJvm.inspect(
            ring = secretCertificate,
            referenceTime = now(),
        )
            ?.primary
            ?.publicKey
            ?: fail(GpgKeyExpirationError.FingerprintMismatch)
        if (!suppliedPrimary.hasSameFingerprint(secretPrimary)) {
            fail(GpgKeyExpirationError.FingerprintMismatch)
        }

        requireUniqueComponentIdentities(suppliedKeys)
        requireUniqueComponentIdentities(secretKeys)

        // A refreshed certificate can legitimately contain subkeys that are not
        // present in an older secret-key export. Only accept such components (and
        // refreshed packets for known components) when the unchanged primary key
        // cryptographically binds them into this certificate.
        val secretByKeyId = secretKeys.associateBy { it.keyID }
        suppliedKeys.forEach { suppliedKey ->
            val secretKey = secretByKeyId[suppliedKey.keyID] ?: return@forEach
            if (!suppliedKey.hasSameFingerprint(secretKey)) {
                // Key IDs are only 64 bits. Never let a collision replace secret
                // material with a different public-key packet.
                fail(GpgKeyExpirationError.FingerprintMismatch)
            }
        }

        val secretByFingerprint = secretKeys.associateBy {
            it.fingerprintHex().normalizeGpgFingerprint()
        }
        val suppliedFingerprints = suppliedKeys
            .map { it.fingerprintHex().normalizeGpgFingerprint() }
            .toSet()
        val reconciledKeys = suppliedKeys.map { suppliedKey ->
            val fingerprint = suppliedKey.fingerprintHex().normalizeGpgFingerprint()
            val secretKey = secretByFingerprint[fingerprint] ?: return@map suppliedKey
            try {
                // Both transferable representations may have learned signatures
                // independently. Keep the supplied packet ordering while taking
                // the union of certifications and revocations from both sides.
                PGPPublicKey.join(
                    suppliedKey,
                    secretKey,
                    false,
                    false,
                )
            } catch (_: Exception) {
                fail(GpgKeyExpirationError.FingerprintMismatch)
            }
        } + secretKeys.filter { secretKey ->
            secretKey.fingerprintHex().normalizeGpgFingerprint() !in suppliedFingerprints
        }
        val reconciled = PGPPublicKeyRing(reconciledKeys)
        val inspector = GpgCertificateInspectorJvm.inspect(
            ring = reconciled,
            candidateRevocationKeys = candidateRevocationKeys,
            referenceTime = now(),
        )
            ?: fail(GpgKeyExpirationError.FingerprintMismatch)
        if (
            inspector.subkeys.any { key ->
                inspector.verifiedSubkeyBindings(key.publicKey).isEmpty()
            }
        ) {
            fail(GpgKeyExpirationError.FingerprintMismatch)
        }
        return reconciled
    }

    private fun requireUniqueComponentIdentities(
        keys: List<PGPPublicKey>,
    ) {
        val fingerprints = keys.map { it.fingerprintHex().normalizeGpgFingerprint() }
        val keyIds = keys.map { it.keyID }
        if (fingerprints.toSet().size != fingerprints.size || keyIds.toSet().size != keyIds.size) {
            fail(GpgKeyExpirationError.FingerprintMismatch)
        }
    }

    private fun PGPPublicKey.hasSameFingerprint(
        other: PGPPublicKey,
    ): Boolean = fingerprint.contentEquals(other.fingerprint)

    private fun synchronizePublicKeys(
        secretRing: PGPSecretKeyRing,
        certificate: PGPPublicKeyRing,
    ): PGPSecretKeyRing {
        val secretFingerprints = secretRing.secretKeys
            .asSequence()
            .map { it.publicKey.fingerprintHex().normalizeGpgFingerprint() }
            .toSet()
        var synchronized = PGPSecretKeyRing.replacePublicKeys(secretRing, certificate)
        certificate.publicKeys.asSequence()
            .filter { publicKey ->
                publicKey.fingerprintHex().normalizeGpgFingerprint() !in secretFingerprints
            }
            .forEach { publicKey ->
                synchronized = PGPSecretKeyRing.insertOrReplacePublicKey(synchronized, publicKey)
            }
        return synchronized
    }

    private fun requireUnprotectedSecret(
        secretKey: PGPSecretKey?,
    ): PGPSecretKey {
        secretKey ?: fail(GpgKeyExpirationError.MissingSecretKey)
        if (secretKey.isPrivateKeyEmpty) {
            fail(GpgKeyExpirationError.MissingSecretKey)
        }
        if (secretKey.keyEncryptionAlgorithm != SymmetricKeyAlgorithmTags.NULL) {
            fail(GpgKeyExpirationError.ProtectedSecretKey)
        }
        return secretKey
    }

    private fun error(
        reason: GpgKeyExpirationError,
    ) = GpgKeyExpirationResult.Error(reason)

    private inline fun <T> parseOrFail(
        reason: GpgKeyExpirationError,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: ExpirationUpdateException) {
        throw e
    } catch (_: GpgUnsupportedKeyVersionException) {
        fail(GpgKeyExpirationError.UnsupportedKeyVersion)
    } catch (_: Exception) {
        fail(reason)
    }

    private fun fail(
        reason: GpgKeyExpirationError,
    ): Nothing = throw ExpirationUpdateException(reason)

    private class ExpirationUpdateException(
        val reason: GpgKeyExpirationError,
    ) : RuntimeException()

}
