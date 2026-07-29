package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.util.io.toInputStream
import com.artemchep.keyguard.util.io.toOutputStream
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptFileResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextResult
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpDecryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpEncryptTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPrivateKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpService
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpSignTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerification
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationStatus
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerificationWarning
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyFileRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyDetachedTextRequest
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpVerifyTextRequest
import com.artemchep.keyguard.common.service.crypto.splitClearTextLines
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.BCPGOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.bcpg.sig.KeyFlags
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPCompressedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPOnePassSignatureList
import org.bouncycastle.openpgp.PGPPrivateKey
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import org.bouncycastle.util.io.Streams
import org.kodein.di.DirectDI
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Date
import kotlin.time.Clock
import kotlin.time.Instant

class BcGpgOpenPgpServiceTestOracle() : GpgOpenPgpService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun clearSignText(
        request: GpgOpenPgpSignTextRequest,
    ): String {
        val signingKey = findSigningSecretKey(request.privateKey)
            ?: throw IllegalStateException("No signing-capable GPG private key was found.")
        val privateKey = signingKey.extractPrivateKeyEmptyPassphrase()
        val signatureGenerator = signatureGenerator(
            secretKey = signingKey,
            privateKey = privateKey,
            signatureType = PGPSignature.CANONICAL_TEXT_DOCUMENT,
        )

        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            armoredOut.beginClearText(HashAlgorithmTags.SHA256)
            val lines = splitClearTextLines(request.text.encodeToByteArray())
            lines.forEachIndexed { index, line ->
                // The canonical text is the lines joined by CRLF, so every line
                // except the first is preceded by a CRLF separator in the signature.
                if (index > 0) {
                    signatureGenerator.update('\r'.code.toByte())
                    signatureGenerator.update('\n'.code.toByte())
                }
                if (line.canonicalLength > 0) {
                    signatureGenerator.update(line.raw, 0, line.canonicalLength)
                }
                armoredOut.write(line.raw)
            }
            // RFC 4880 §7 requires the armor header line to begin a line, so when the
            // input ends without a terminator, emit one — otherwise the BEGIN PGP
            // SIGNATURE marker gets glued onto the final body line (malformed armor that
            // strict implementations reject). Per the same section the line ending
            // immediately preceding the armor header is NOT part of the signed text, so
            // this byte goes into the armor only, never into the signature generator.
            val lastByte = lines.last().raw.lastOrNull()
            if (request.text.isNotEmpty() &&
                lastByte != '\n'.code.toByte() &&
                lastByte != '\r'.code.toByte()
            ) {
                armoredOut.write('\n'.code)
            }
            armoredOut.endClearText()

            val bcpgOut = BCPGOutputStream(armoredOut)
            signatureGenerator.generate().encode(bcpgOut)
            bcpgOut.flush()
        }
        return out.toString(Charsets.UTF_8)
    }

    override fun signTextDetached(
        request: GpgOpenPgpSignTextRequest,
    ): String {
        val signingKey = findSigningSecretKey(request.privateKey)
            ?: throw IllegalStateException("No signing-capable GPG private key was found.")
        val privateKey = signingKey.extractPrivateKeyEmptyPassphrase()
        val signatureGenerator = signatureGenerator(
            secretKey = signingKey,
            privateKey = privateKey,
            signatureType = PGPSignature.BINARY_DOCUMENT,
        )

        val data = request.text.encodeToByteArray()
        signatureGenerator.update(data, 0, data.size)

        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            BCPGOutputStream(armoredOut).use { bcpgOut ->
                signatureGenerator.generate().encode(bcpgOut)
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    override fun verifyClearSignedText(
        request: GpgOpenPgpVerifyTextRequest,
    ): GpgOpenPgpVerification = NativeGpgOpenPgpVerifier.verifyClearSignedText(request)

    override fun verifyDetachedText(
        request: GpgOpenPgpVerifyDetachedTextRequest,
    ): GpgOpenPgpVerification = NativeGpgOpenPgpVerifier.verifyDetachedText(request)

    override fun encryptText(
        request: GpgOpenPgpEncryptTextRequest,
    ): String {
        val out = ByteArrayOutputStream()
        encrypt(
            input = ByteArrayInputStream(request.text.encodeToByteArray()),
            output = out,
            publicKeys = request.publicKeys,
            fileName = PGPLiteralData.CONSOLE,
            armored = true,
            signingPrivateKey = request.signingPrivateKey,
        )
        return out.toString(Charsets.UTF_8)
    }

    override fun decryptText(
        request: GpgOpenPgpDecryptTextRequest,
    ): GpgOpenPgpDecryptTextResult {
        val out = ByteArrayOutputStream()
        val verification = decrypt(
            input = ByteArrayInputStream(request.encryptedText.encodeToByteArray()),
            output = out,
            privateKeys = request.privateKeys,
            publicKeys = request.publicKeys,
        )
        return GpgOpenPgpDecryptTextResult(
            text = out.toString(Charsets.UTF_8),
            verification = verification,
        )
    }

    override fun signFile(
        request: GpgOpenPgpSignFileRequest,
    ) {
        val signingKey = findSigningSecretKey(request.privateKey)
            ?: throw IllegalStateException("No signing-capable GPG private key was found.")
        val privateKey = signingKey.extractPrivateKeyEmptyPassphrase()
        val signatureGenerator = signatureGenerator(
            secretKey = signingKey,
            privateKey = privateKey,
            signatureType = PGPSignature.BINARY_DOCUMENT,
        )

        request.input.toInputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    signatureGenerator.update(buffer, 0, read)
                }
            }
        }

        val output = request.signatureOutput.toOutputStream()
        val finalOutput = output.maybeArmored(request.armored)
        finalOutput.use {
            BCPGOutputStream(it).use { bcpgOut ->
                signatureGenerator.generate().encode(bcpgOut)
            }
        }
    }

    override fun verifyFile(
        request: GpgOpenPgpVerifyFileRequest,
    ): GpgOpenPgpVerification = NativeGpgOpenPgpVerifier.verifyFile(request)

    override fun encryptFile(
        request: GpgOpenPgpEncryptFileRequest,
    ) {
        encrypt(
            input = request.input.toInputStream(),
            output = request.output.toOutputStream(),
            publicKeys = request.publicKeys,
            fileName = request.fileName.ifBlank { PGPLiteralData.CONSOLE },
            armored = request.armored,
            signingPrivateKey = request.signingPrivateKey,
        )
    }

    override fun decryptFile(
        request: GpgOpenPgpDecryptFileRequest,
    ): GpgOpenPgpDecryptFileResult {
        val verification = request.output.toOutputStream().use { output ->
            decrypt(
                input = request.input.toInputStream(),
                output = output,
                privateKeys = request.privateKeys,
                publicKeys = request.publicKeys,
            )
        }
        return GpgOpenPgpDecryptFileResult(
            verification = verification,
        )
    }

    private fun encrypt(
        input: InputStream,
        output: OutputStream,
        publicKeys: List<GpgOpenPgpPublicKey>,
        fileName: String,
        armored: Boolean,
        signingPrivateKey: GpgOpenPgpPrivateKey?,
    ) {
        val publicKeyRings = parsePublicKeyRings(publicKeys)
        val candidateRevocationKeys = publicKeyRings.allPublicKeys()
        val encryptionKeys = publicKeyRings
            .mapNotNull { publicKeyRing ->
                findEncryptionPublicKey(
                    publicKeyRing = publicKeyRing,
                    candidateRevocationKeys = candidateRevocationKeys,
                )
            }
            .distinctBy { it.keyID }
        if (encryptionKeys.isEmpty()) {
            throw IllegalStateException("No encryption-capable GPG public key was found.")
        }
        val signing = signingPrivateKey?.let { privateKeyRequest ->
            val signingKey = findSigningSecretKey(privateKeyRequest)
                ?: throw IllegalStateException("No signing-capable GPG private key was found.")
            val privateKey = signingKey.extractPrivateKeyEmptyPassphrase()
            SigningContext(
                signatureGenerator = signatureGenerator(
                    secretKey = signingKey,
                    privateKey = privateKey,
                    signatureType = PGPSignature.BINARY_DOCUMENT,
                ),
            )
        }

        val finalOutput = output.maybeArmored(armored)
        try {
            val encryptorBuilder = JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                .setProvider(gpgBouncyCastleProvider)
                .setSecureRandom(SecureRandom())
                .setWithIntegrityPacket(true)
            val encryptedDataGenerator = PGPEncryptedDataGenerator(encryptorBuilder)
            encryptionKeys.forEach { key ->
                encryptedDataGenerator.addMethod(
                    JcePublicKeyKeyEncryptionMethodGenerator(key)
                        .setProvider(gpgBouncyCastleProvider),
                )
            }

            val encryptedOut = encryptedDataGenerator.open(finalOutput, ByteArray(BUFFER_SIZE))
            try {
                val compressedDataGenerator = PGPCompressedDataGenerator(PGPCompressedData.ZIP)
                try {
                    val compressedOut = compressedDataGenerator.open(encryptedOut)
                    signing?.signatureGenerator
                        ?.generateOnePassVersion(false)
                        ?.encode(compressedOut)
                    val literalDataGenerator = PGPLiteralDataGenerator()
                    try {
                        val literalOut = literalDataGenerator.open(
                            compressedOut,
                            PGPLiteralData.BINARY,
                            fileName,
                            Date(),
                            ByteArray(BUFFER_SIZE),
                        )
                        try {
                            input.use {
                                copyToLiteralData(
                                    input = it,
                                    output = literalOut,
                                    signatureGenerator = signing?.signatureGenerator,
                                )
                            }
                        } finally {
                            literalOut.close()
                        }
                    } finally {
                        literalDataGenerator.close()
                    }
                    signing?.signatureGenerator
                        ?.generate()
                        ?.encode(compressedOut)
                } finally {
                    compressedDataGenerator.close()
                }
            } finally {
                encryptedOut.close()
            }
        } finally {
            finalOutput.close()
        }
    }

    private fun decrypt(
        input: InputStream,
        output: OutputStream,
        privateKeys: List<GpgOpenPgpPrivateKey>,
        publicKeys: List<GpgOpenPgpPublicKey>,
    ): GpgOpenPgpVerification? {
        val encryptedDataList = readEncryptedDataList(PGPUtil.getDecoderStream(input))
        var encryptedData: PGPPublicKeyEncryptedData? = null
        var privateKey: PGPPrivateKey? = null
        val encryptedObjects = encryptedDataList.encryptedDataObjects
        while (encryptedObjects.hasNext() && privateKey == null) {
            val candidate = encryptedObjects.next() as? PGPPublicKeyEncryptedData
                ?: continue
            encryptedData = candidate
            privateKey = findPrivateKey(
                privateKeys = privateKeys,
                keyId = candidate.keyID,
            )
        }

        val selectedEncryptedData = encryptedData
            ?: throw IllegalStateException("The encrypted message does not contain public-key encrypted data.")
        val selectedPrivateKey = privateKey
            ?: throw IllegalStateException("No matching GPG private key was found for this message.")

        // Modern gpg refuses a bare SED packet (no MDC / integrity packet) because it is
        // malleable — the classic EFAIL integrity-oracle weakness. So do we, before we
        // ever produce plaintext from it.
        if (!selectedEncryptedData.isIntegrityProtected) {
            throw IllegalStateException("The encrypted GPG message is not integrity protected.")
        }

        val clear = selectedEncryptedData.getDataStream(
            JcePublicKeyDataDecryptorFactoryBuilder()
                .setProvider(gpgBouncyCastleProvider)
                .build(selectedPrivateKey),
        )
        val verification = pipePgpMessage(
            input = clear,
            output = output,
            publicKeys = publicKeys,
        )
        if (selectedEncryptedData.isIntegrityProtected && !selectedEncryptedData.verify()) {
            throw IllegalStateException("The encrypted GPG message failed its integrity check.")
        }
        output.flush()
        return verification
    }

    private fun pipePgpMessage(
        input: InputStream,
        output: OutputStream,
        publicKeys: List<GpgOpenPgpPublicKey>,
    ): GpgOpenPgpVerification? {
        var objectFactory = JcaPGPObjectFactory(input)
        var message = objectFactory.nextObject()
        if (message is PGPCompressedData) {
            objectFactory = JcaPGPObjectFactory(message.dataStream)
            message = objectFactory.nextObject()
        }

        when (message) {
            is PGPLiteralData -> {
                message.inputStream.use { literalInput ->
                    Streams.pipeAll(literalInput, output, BUFFER_SIZE)
                }
                return null
            }

            is PGPOnePassSignatureList -> {
                return pipeSignedPgpMessage(
                    objectFactory = objectFactory,
                    onePassSignatureList = message,
                    output = output,
                    publicKeys = publicKeys,
                )
            }

            else -> throw IllegalStateException("The encrypted GPG message does not contain literal data.")
        }
    }

    private fun pipeSignedPgpMessage(
        objectFactory: JcaPGPObjectFactory,
        onePassSignatureList: PGPOnePassSignatureList,
        output: OutputStream,
        publicKeys: List<GpgOpenPgpPublicKey>,
    ): GpgOpenPgpVerification {
        val publicKeyRings = parsePublicKeyCandidates(publicKeys)
        // Prefer the first one-pass signature we hold a public key for; otherwise fall back
        // to the first (so a missing key is still reported for single-signature messages).
        var onePassSignature = onePassSignatureList.get(0)
        var publicKey: PGPPublicKey? = null
        for (index in 0 until onePassSignatureList.size()) {
            val candidate = onePassSignatureList[index]
            val candidateKey = findPublicKey(publicKeyRings, candidate.keyID)
            if (candidateKey != null) {
                onePassSignature = candidate
                publicKey = candidateKey
                break
            }
        }
        if (publicKey != null) {
            onePassSignature.init(
                JcaPGPContentVerifierBuilderProvider()
                    .setProvider(gpgBouncyCastleProvider),
                publicKey,
            )
        }

        val literalData = objectFactory.nextObject() as? PGPLiteralData
            ?: throw IllegalStateException("The signed encrypted GPG message does not contain literal data.")
        literalData.inputStream.use { literalInput ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = literalInput.read(buffer)
                if (read < 0) {
                    break
                }
                if (read > 0) {
                    output.write(buffer, 0, read)
                    if (publicKey != null) {
                        onePassSignature.update(buffer, 0, read)
                    }
                }
            }
        }

        val signatureList = objectFactory.nextObject() as? PGPSignatureList
            ?: throw IllegalStateException("The signed encrypted GPG message does not contain a signature.")
        // Match the trailing signature to the chosen one-pass packet by keyID (they need
        // not share an index once several signers are present).
        val signature = (0 until signatureList.size())
            .map { signatureList[it] }
            .firstOrNull { it.keyID == onePassSignature.keyID }
            ?: signatureList.get(0)
        return if (publicKey != null) {
            verificationResult(
                signature = signature,
                publicKey = publicKey,
                publicKeyRings = publicKeyRings,
                valid = onePassSignature.verify(signature),
            )
        } else {
            missingPublicKey(signature)
        }
    }

    private fun copyToLiteralData(
        input: InputStream,
        output: OutputStream,
        signatureGenerator: PGPSignatureGenerator?,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            if (read > 0) {
                output.write(buffer, 0, read)
                signatureGenerator?.update(buffer, 0, read)
            }
        }
    }

    private fun OutputStream.maybeArmored(
        armored: Boolean,
    ): OutputStream = if (armored) {
        ArmoredOutputStream(this)
    } else {
        this
    }

    private fun readEncryptedDataList(
        input: InputStream,
    ): PGPEncryptedDataList {
        val objectFactory = JcaPGPObjectFactory(input)
        val first = objectFactory.nextObject()
        return when (first) {
            is PGPEncryptedDataList -> first
            else -> objectFactory.nextObject() as? PGPEncryptedDataList
                ?: throw IllegalStateException("The input is not an encrypted GPG message.")
        }
    }

    private fun signatureGenerator(
        secretKey: PGPSecretKey,
        privateKey: PGPPrivateKey,
        signatureType: Int,
    ): PGPSignatureGenerator {
        val generator = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(gpgBouncyCastleProvider),
        )
        generator.init(signatureType, privateKey)
        val userId = secretKey.publicKey.userIDs
            .asSequence()
            .firstOrNull()
        if (userId != null) {
            val subpacketGenerator = PGPSignatureSubpacketGenerator()
            subpacketGenerator.addSignerUserID(false, userId)
            generator.setHashedSubpackets(subpacketGenerator.generate())
        }
        return generator
    }

    private fun findSigningSecretKey(
        privateKey: GpgOpenPgpPrivateKey,
    ): PGPSecretKey? {
        val expectedFingerprint = privateKey.preferredFingerprint
            ?.normalizeGpgFingerprint()
            ?.takeIf { it.isNotBlank() }
        val collection = parseSecretKeyCollection(privateKey.armored)
        val secretRings = collection.keyRings.asSequence().toList()
        val candidateRevocationKeys = secretRings
            .asSequence()
            .flatMap { secretRing -> secretRing.publicKeys.asSequence() }
            .toList()
        val now = Clock.System.now()
        return secretRings
            .asSequence()
            .mapNotNull { secretRing ->
                val certificate = GpgCertificateInspectorJvm.inspect(
                    ring = secretRing.toCertificate(),
                    candidateRevocationKeys = candidateRevocationKeys,
                    referenceTime = now,
                )
                    ?: return@mapNotNull null
                if (
                    !certificate.primary.authenticated ||
                    certificate.primary.revoked ||
                    certificate.primary.isExpired(now)
                ) {
                    return@mapNotNull null
                }
                val expectedComponent = expectedFingerprint?.let { expected ->
                    certificate.keys.firstOrNull { key ->
                        key.publicKey.fingerprintHex().normalizeGpgFingerprint() == expected
                    } ?: return@mapNotNull null
                }
                val candidates = certificate.authenticatedKeys
                    .asSequence()
                    .filter { key -> !key.revoked && !key.isExpired(now) }
                    .filter { key ->
                        key.keyFlags?.let { flags -> flags and KeyFlags.SIGN_DATA != 0 }
                            ?: key.publicKey.isSigningKey()
                    }
                    .filter { key ->
                        key.publicKey.isMasterKey || key.signingCrossCertified
                    }
                    .mapNotNull { key ->
                        secretRing.getSecretKey(key.publicKey.keyID)
                            ?.takeIf { secretKey ->
                                !secretKey.isPrivateKeyEmpty && secretKey.isSigningKey
                            }
                            ?.let { secretKey -> key to secretKey }
                    }
                    .toList()
                if (expectedComponent != null && !expectedComponent.publicKey.isMasterKey) {
                    candidates.firstOrNull { (key) -> key === expectedComponent }?.second
                } else {
                    candidates
                        .sortedWith(
                            compareByDescending<Pair<GpgVerifiedCertificateKeyJvm, PGPSecretKey>> {
                                !it.first.publicKey.isMasterKey
                            }.thenByDescending { it.first.publicKey.creationTime?.time ?: 0L },
                        )
                        .firstOrNull()
                        ?.second
                }
            }
            .firstOrNull()
    }

    private fun findPrivateKey(
        privateKeys: List<GpgOpenPgpPrivateKey>,
        keyId: Long,
    ): PGPPrivateKey? {
        var unsupportedVersion: GpgUnsupportedKeyVersionException? = null
        var hasSupportedCandidate = false
        privateKeys.forEach { privateKey ->
            val collection = try {
                parseSecretKeyCollection(privateKey.armored)
            } catch (error: GpgUnsupportedKeyVersionException) {
                unsupportedVersion = unsupportedVersion ?: error
                return@forEach
            }
            hasSupportedCandidate = true
            collection.getSecretKey(keyId)
                ?.extractPrivateKeyEmptyPassphrase()
                ?.let { return it }
        }
        if (!hasSupportedCandidate) {
            unsupportedVersion?.let { throw it }
        }
        return null
    }

    private fun parseSecretKeyCollection(
        armored: String,
    ): PGPSecretKeyRingCollection = parseGpgSecretKeyRingCollection(armored)

    private fun parsePublicKeyRings(
        publicKeys: List<GpgOpenPgpPublicKey>,
    ): List<PGPPublicKeyRing> = publicKeys.flatMap { publicKey ->
        parseGpgPublicKeyRingCollection(publicKey.armored)
            .keyRings
            .asSequence()
            .toList()
    }

    private fun parsePublicKeyCandidates(
        publicKeys: List<GpgOpenPgpPublicKey>,
    ): List<PGPPublicKeyRing> {
        var unsupportedVersion: GpgUnsupportedKeyVersionException? = null
        val rings = publicKeys.flatMap { publicKey ->
            try {
                val collection = parseGpgPublicKeyRingCollection(publicKey.armored)
                collection.keyRings.asSequence().toList()
            } catch (error: GpgUnsupportedKeyVersionException) {
                unsupportedVersion = unsupportedVersion ?: error
                emptyList()
            }
        }
        if (rings.isEmpty()) {
            unsupportedVersion?.let { throw it }
        }
        return rings
    }

    private fun findPublicKey(
        publicKeyRings: List<PGPPublicKeyRing>,
        keyId: Long,
    ): PGPPublicKey? = publicKeyRings
        .asSequence()
        .flatMap { it.publicKeys.asSequence() }
        .firstOrNull { it.keyID == keyId }

    private fun findEncryptionPublicKey(
        publicKeyRing: PGPPublicKeyRing,
        candidateRevocationKeys: List<PGPPublicKey>,
    ): PGPPublicKey? {
        val now = Clock.System.now()
        val certificate = GpgCertificateInspectorJvm.inspect(
            ring = publicKeyRing,
            candidateRevocationKeys = candidateRevocationKeys,
            referenceTime = now,
        )
            ?: return null
        if (
            !certificate.primary.authenticated ||
            certificate.primary.revoked ||
            certificate.primary.isExpired(now)
        ) {
            return null
        }
        // Match gpg's recipient selection by key flags (RFC 4880 §5.2.3.21) rather than by
        // algorithm: RSA primaries are encryption-capable by algorithm but must not be
        // used unless their self-signature actually requests an encryption usage.
        return certificate.authenticatedKeys
            .asSequence()
            .filter { it.isEncryptionCandidate() }
            // Never encrypt to a revoked or expired key; there is deliberately no fallback
            // to one — the caller reports "no encryption-capable key" instead.
            .filter { !it.revoked && !it.isExpired(now) }
            // Prefer a dedicated encryption subkey over the primary, then the newest key,
            // exactly the recipient a real gpg client would route the message to.
            .sortedWith(
                compareByDescending<GpgVerifiedCertificateKeyJvm> { !it.publicKey.isMasterKey }
                    .thenByDescending { it.publicKey.creationTime?.time ?: 0L },
            )
            .firstOrNull()
            ?.publicKey
    }

    private fun GpgVerifiedCertificateKeyJvm.isEncryptionCandidate(): Boolean {
        // A key with no key-flags subpacket at all (older keys) falls back to the
        // algorithm-based capability; a key that does carry flags must request encryption.
        return keyFlags?.let { flags ->
            flags and (KeyFlags.ENCRYPT_COMMS or KeyFlags.ENCRYPT_STORAGE) != 0
        } ?: publicKey.isEncryptionKey
    }

    private fun verificationResult(
        signature: PGPSignature,
        publicKey: PGPPublicKey,
        publicKeyRings: List<PGPPublicKeyRing>,
        valid: Boolean,
    ): GpgOpenPgpVerification {
        val now = Clock.System.now()
        val (certificate, inspectedKey) = findInspectedPublicKey(
            publicKeyRings = publicKeyRings,
            publicKey = publicKey,
            referenceTime = now,
        )
        val signerKey = inspectedKey?.takeIf { key ->
            key.authenticated &&
                (key.publicKey.isMasterKey || key.signingCrossCertified)
        }
        return GpgOpenPgpVerification(
            status = if (valid) {
                GpgOpenPgpVerificationStatus.VALID
            } else {
                GpgOpenPgpVerificationStatus.INVALID
            },
            keyId = signature.keyID.gpgKeyIdHex(),
            fingerprint = publicKey.fingerprintHex(),
            userIds = certificate
                ?.takeIf { signerKey != null }
                ?.verifiedUserIds
                .orEmpty(),
            createdAt = signature.creationTime?.let { Instant.fromEpochMilliseconds(it.time) },
            warnings = buildList {
                if (
                    signerKey != null &&
                    (certificate?.primary?.revoked == true || signerKey.revoked)
                ) {
                    add(GpgOpenPgpVerificationWarning.KEY_REVOKED)
                }
                if (
                    signerKey != null &&
                    (
                        certificate?.primary?.isExpired(now) == true ||
                            signerKey.isExpired(now)
                        )
                ) {
                    add(GpgOpenPgpVerificationWarning.KEY_EXPIRED)
                }
                if (signature.isExpiredAt(now)) {
                    add(GpgOpenPgpVerificationWarning.SIGNATURE_EXPIRED)
                }
            },
        )
    }

    private fun findInspectedPublicKey(
        publicKeyRings: List<PGPPublicKeyRing>,
        publicKey: PGPPublicKey,
        referenceTime: Instant,
    ): Pair<GpgCertificateInspectorJvm?, GpgVerifiedCertificateKeyJvm?> {
        val candidateRevocationKeys = publicKeyRings.allPublicKeys()
        return publicKeyRings
            .asSequence()
            .mapNotNull { ring ->
                GpgCertificateInspectorJvm.inspect(
                    ring = ring,
                    candidateRevocationKeys = candidateRevocationKeys,
                    referenceTime = referenceTime,
                )
            }
            .mapNotNull { certificate ->
                certificate.keys
                    .firstOrNull { key ->
                        key.publicKey.fingerprint.contentEquals(publicKey.fingerprint)
                    }
                    ?.let { key -> certificate to key }
            }
            .firstOrNull()
            ?: (null to null)
    }

    private fun List<PGPPublicKeyRing>.allPublicKeys(): List<PGPPublicKey> =
        asSequence()
            .flatMap { ring -> ring.publicKeys.asSequence() }
            .toList()

    private fun missingPublicKey(
        signature: PGPSignature,
    ): GpgOpenPgpVerification = GpgOpenPgpVerification(
        status = GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY,
        keyId = signature.keyID.gpgKeyIdHex(),
        fingerprint = null,
        userIds = emptyList(),
        createdAt = signature.creationTime?.let { Instant.fromEpochMilliseconds(it.time) },
    )

    private fun GpgVerifiedCertificateKeyJvm.isExpired(
        now: Instant,
    ): Boolean {
        if (validSeconds <= 0L) {
            return false
        }
        val created = publicKey.creationTime ?: return false
        val expiresAt = Instant.fromEpochMilliseconds(created.time + validSeconds * 1000L)
        return expiresAt <= now
    }

    private companion object {
        const val BUFFER_SIZE = 1 shl 16
    }
}

private data class SigningContext(
    val signatureGenerator: PGPSignatureGenerator,
)
