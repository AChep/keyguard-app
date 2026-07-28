package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.crypto.NativeGpgKeyGenerator
import com.artemchep.keyguard.crypto.NativeGpgOpenPgpService
import com.artemchep.keyguard.crypto.GpgUnsupportedKeyVersionException
import com.artemchep.keyguard.crypto.armored
import com.artemchep.keyguard.crypto.extractPrivateKeyEmptyPassphrase
import com.artemchep.keyguard.crypto.fingerprintHex
import com.artemchep.keyguard.util.foundation.io.toSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator
import org.bouncycastle.openpgp.PGPEncryptedDataList
import org.bouncycastle.openpgp.PGPLiteralData
import org.bouncycastle.openpgp.PGPLiteralDataGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgOpenPgpServiceJvmTest {
    private val service = NativeGpgOpenPgpService

    private val privateKey = GpgOpenPgpPrivateKey(
        armored = CV25519_SECRET_KEY,
        preferredFingerprint = "D0BBCFBB250D3BB0658E5384F83D947D29EFECF7",
    )
    private val publicKey by lazy {
        GpgOpenPgpPublicKey(publicKeyArmoredOf(CV25519_SECRET_KEY))
    }

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `clear-sign text then verify`() {
        val signed = service.clearSignText(
            GpgOpenPgpSignTextRequest(
                text = "hello from keyguard",
                privateKey = privateKey,
            ),
        )

        assertTrue("BEGIN PGP SIGNED MESSAGE" in signed)
        val verification = service.verifyClearSignedText(
            GpgOpenPgpVerifyTextRequest(
                signedText = signed,
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", verification.fingerprint)
        assertTrue(verification.userIds.any { "cv25519@test.invalid" in it })
    }

    @Test
    fun `legacy public key candidate does not hide a supported verification key`() {
        val signed = service.clearSignText(
            GpgOpenPgpSignTextRequest(
                text = "hello from keyguard",
                privateKey = privateKey,
            ),
        )
        val legacyKey = GpgOpenPgpPublicKey(
            GpgLegacyKeyFixtures.publicRing(version = 3).armored(),
        )

        val verification = service.verifyClearSignedText(
            GpgOpenPgpVerifyTextRequest(
                signedText = signed,
                publicKeys = listOf(legacyKey, publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", verification.fingerprint)
    }

    @Test
    fun `tampered clear-signed text fails verification`() {
        val signed = service.clearSignText(
            GpgOpenPgpSignTextRequest(
                text = "original body",
                privateKey = privateKey,
            ),
        )
        val tampered = signed.replace("original body", "tampered body")

        val verification = service.verifyClearSignedText(
            GpgOpenPgpVerifyTextRequest(
                signedText = tampered,
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.INVALID, verification.status)
    }

    @Test
    fun `detached file sign then verify`() {
        val data = "file payload\nwith multiple lines\n".encodeToByteArray()
        val signature = Buffer()

        service.signFile(
            GpgOpenPgpSignFileRequest(
                input = data.toSource(),
                signatureOutput = signature,
                privateKey = privateKey,
            ),
        )
        val signatureBytes = signature.readByteArray()

        val verification = service.verifyFile(
            GpgOpenPgpVerifyFileRequest(
                input = data.toSource(),
                signatureInput = signatureBytes.toSource(),
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
    }

    @Test
    fun `tampered file fails detached verification`() {
        val data = "file payload".encodeToByteArray()
        val signature = Buffer()
        service.signFile(
            GpgOpenPgpSignFileRequest(
                input = data.toSource(),
                signatureOutput = signature,
                privateKey = privateKey,
            ),
        )

        val verification = service.verifyFile(
            GpgOpenPgpVerifyFileRequest(
                input = "different payload".encodeToByteArray().toSource(),
                signatureInput = signature.readByteArray().toSource(),
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.INVALID, verification.status)
    }

    @Test
    fun `encrypt decrypt text round trip with stored public key`() {
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "secret text",
                publicKeys = listOf(publicKey),
            ),
        )

        assertTrue("BEGIN PGP MESSAGE" in encrypted)
        val decrypted = service.decryptText(
            GpgOpenPgpDecryptTextRequest(
                encryptedText = encrypted,
                privateKeys = listOf(privateKey),
            ),
        )

        assertEquals("secret text", decrypted.text)
        assertNull(decrypted.verification)
    }

    @Test
    fun `legacy recipient cannot be silently dropped from encryption`() {
        GpgLegacyKeyFixtures.versions.forEach { version ->
            val legacyKey = GpgOpenPgpPublicKey(
                GpgLegacyKeyFixtures.publicRing(version).armored(),
            )

            assertFailsWith<GpgUnsupportedKeyVersionException> {
                service.encryptText(
                    GpgOpenPgpEncryptTextRequest(
                        text = "all recipients must be supported",
                        publicKeys = listOf(legacyKey, publicKey),
                    ),
                )
            }
        }
    }

    @Test
    fun `legacy private key candidate does not hide a supported decryption key`() {
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "supported decryption key",
                publicKeys = listOf(publicKey),
            ),
        )

        GpgLegacyKeyFixtures.versions.forEach { version ->
            val legacyKey = GpgOpenPgpPrivateKey(
                armored = GpgLegacyKeyFixtures.secretRing(version).armored(),
            )
            val decrypted = service.decryptText(
                GpgOpenPgpDecryptTextRequest(
                    encryptedText = encrypted,
                    privateKeys = listOf(legacyKey, privateKey),
                ),
            )

            assertEquals("supported decryption key", decrypted.text)
        }
    }

    @Test
    fun `legacy-only private key candidates are rejected`() {
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "unsupported decryption key",
                publicKeys = listOf(publicKey),
            ),
        )

        GpgLegacyKeyFixtures.versions.forEach { version ->
            val legacyKey = GpgOpenPgpPrivateKey(
                armored = GpgLegacyKeyFixtures.secretRing(version).armored(),
            )

            assertFailsWith<GpgUnsupportedKeyVersionException> {
                service.decryptText(
                    GpgOpenPgpDecryptTextRequest(
                        encryptedText = encrypted,
                        privateKeys = listOf(legacyKey),
                    ),
                )
            }
        }
    }

    @Test
    fun `encrypt decrypt text round trip with pasted public key`() {
        val pastedPublicKey = GpgOpenPgpPublicKey(publicKey.armored)
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "pasted recipient",
                publicKeys = listOf(pastedPublicKey),
            ),
        )

        val decrypted = service.decryptText(
            GpgOpenPgpDecryptTextRequest(
                encryptedText = encrypted,
                privateKeys = listOf(privateKey),
            ),
        )

        assertEquals("pasted recipient", decrypted.text)
    }

    @Test
    fun `detached text sign then verify`() {
        val text = "detached text payload"
        val signature = service.signTextDetached(
            GpgOpenPgpSignTextRequest(
                text = text,
                privateKey = privateKey,
            ),
        )

        assertTrue("BEGIN PGP SIGNATURE" in signature)
        val verification = service.verifyDetachedText(
            GpgOpenPgpVerifyDetachedTextRequest(
                text = text,
                signature = signature,
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
    }

    @Test
    fun `tampered detached text fails verification`() {
        val signature = service.signTextDetached(
            GpgOpenPgpSignTextRequest(
                text = "original detached text",
                privateKey = privateKey,
            ),
        )

        val verification = service.verifyDetachedText(
            GpgOpenPgpVerifyDetachedTextRequest(
                text = "tampered detached text",
                signature = signature,
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.INVALID, verification.status)
    }

    @Test
    fun `encrypt decrypt file round trip larger than one stream buffer`() {
        val data = ByteArray(140_000) { index ->
            (index % 251).toByte()
        }
        val encrypted = Buffer()
        val decrypted = Buffer()

        service.encryptFile(
            GpgOpenPgpEncryptFileRequest(
                input = data.toSource(),
                output = encrypted,
                publicKeys = listOf(publicKey),
                fileName = "large.bin",
            ),
        )
        val encryptedBytes = encrypted.readByteArray()
        assertTrue("BEGIN PGP MESSAGE" in encryptedBytes.decodeToString())
        service.decryptFile(
            GpgOpenPgpDecryptFileRequest(
                input = encryptedBytes.toSource(),
                output = decrypted,
                privateKeys = listOf(privateKey),
            ),
        )

        assertContentEquals(data, decrypted.readByteArray())
    }

    @Test
    fun `encrypt decrypt binary file round trip without armor`() {
        val data = ByteArray(140_000) { index ->
            (index % 251).toByte()
        }
        val encrypted = Buffer()
        val decrypted = Buffer()

        service.encryptFile(
            GpgOpenPgpEncryptFileRequest(
                input = data.toSource(),
                output = encrypted,
                publicKeys = listOf(publicKey),
                fileName = "large.bin",
                armored = false,
            ),
        )
        val encryptedBytes = encrypted.readByteArray()
        assertTrue("BEGIN PGP MESSAGE" !in encryptedBytes.decodeToString())
        service.decryptFile(
            GpgOpenPgpDecryptFileRequest(
                input = encryptedBytes.toSource(),
                output = decrypted,
                privateKeys = listOf(privateKey),
            ),
        )

        assertContentEquals(data, decrypted.readByteArray())
    }

    @Test
    fun `detached file sign without armor then verify`() {
        val data = "binary detached signature".encodeToByteArray()
        val signature = Buffer()

        service.signFile(
            GpgOpenPgpSignFileRequest(
                input = data.toSource(),
                signatureOutput = signature,
                privateKey = privateKey,
                armored = false,
            ),
        )
        val signatureBytes = signature.readByteArray()
        assertTrue("BEGIN PGP SIGNATURE" !in signatureBytes.decodeToString())

        val verification = service.verifyFile(
            GpgOpenPgpVerifyFileRequest(
                input = data.toSource(),
                signatureInput = signatureBytes.toSource(),
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
    }

    @Test
    fun `sign encrypt text decrypts and verifies signer`() {
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "signed encrypted text",
                publicKeys = listOf(publicKey),
                signingPrivateKey = privateKey,
            ),
        )

        val decrypted = service.decryptText(
            GpgOpenPgpDecryptTextRequest(
                encryptedText = encrypted,
                privateKeys = listOf(privateKey),
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals("signed encrypted text", decrypted.text)
        assertEquals(GpgOpenPgpVerificationStatus.VALID, decrypted.verification?.status)
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", decrypted.verification?.fingerprint)
    }

    @Test
    fun `sign encrypt text decrypts with missing signer key verification`() {
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "signed encrypted text",
                publicKeys = listOf(publicKey),
                signingPrivateKey = privateKey,
            ),
        )

        val decrypted = service.decryptText(
            GpgOpenPgpDecryptTextRequest(
                encryptedText = encrypted,
                privateKeys = listOf(privateKey),
                publicKeys = emptyList(),
            ),
        )

        assertEquals("signed encrypted text", decrypted.text)
        assertEquals(GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY, decrypted.verification?.status)
    }

    @Test
    fun `missing public key returns missing key verification`() {
        val signed = service.clearSignText(
            GpgOpenPgpSignTextRequest(
                text = "missing public key",
                privateKey = privateKey,
            ),
        )

        val verification = service.verifyClearSignedText(
            GpgOpenPgpVerifyTextRequest(
                signedText = signed,
                publicKeys = emptyList(),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.MISSING_PUBLIC_KEY, verification.status)
    }

    @Test
    fun `missing private key fails decryption`() {
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "needs private key",
                publicKeys = listOf(publicKey),
            ),
        )

        assertFailsWith<IllegalStateException> {
            service.decryptText(
                GpgOpenPgpDecryptTextRequest(
                    encryptedText = encrypted,
                    privateKeys = emptyList(),
                ),
            )
        }
    }

    @Test
    fun `malformed pasted key fails encryption`() {
        assertFailsWith<Exception> {
            service.encryptText(
                GpgOpenPgpEncryptTextRequest(
                    text = "cannot encrypt",
                    publicKeys = listOf(GpgOpenPgpPublicKey("not a public key")),
                ),
            )
        }
    }

    @Test
    fun `malformed detached signature fails verification`() {
        assertFailsWith<Exception> {
            service.verifyFile(
                GpgOpenPgpVerifyFileRequest(
                    input = "payload".encodeToByteArray().toSource(),
                    signatureInput = "not a signature".encodeToByteArray().toSource(),
                    publicKeys = listOf(publicKey),
                ),
            )
        }
    }

    @Test
    fun `gpg accepts service-created clear-signed signature when available`() {
        if (!GpgCliTestSupport.isGpgAvailable()) {
            return
        }
        val home = createTempDirectory("keyguard-gpg-clear")
        if (!tryImportPublicKey(home)) {
            return
        }
        val signedFile = home.resolve("signed.asc")
        signedFile.writeText(
            service.clearSignText(
                GpgOpenPgpSignTextRequest(
                    text = "gpg verify clear text",
                    privateKey = privateKey,
                ),
            ),
        )

        val result = GpgCliTestSupport.runGpg(home, "--batch", "--verify", signedFile.toString())

        assertEquals(0, result.exitCode, result.stderr)
    }

    @Test
    fun `gpg accepts service-created detached signature when available`() {
        if (!GpgCliTestSupport.isGpgAvailable()) {
            return
        }
        val home = createTempDirectory("keyguard-gpg-detached")
        if (!tryImportPublicKey(home)) {
            return
        }
        val data = "gpg detached verification".encodeToByteArray()
        val inputFile = home.resolve("input.txt")
        val signatureFile = home.resolve("input.txt.sig.asc")
        val signature = Buffer()
        service.signFile(
            GpgOpenPgpSignFileRequest(
                input = data.toSource(),
                signatureOutput = signature,
                privateKey = privateKey,
            ),
        )
        inputFile.writeBytes(data)
        signatureFile.writeBytes(signature.readByteArray())

        val result = GpgCliTestSupport.runGpg(home, "--batch", "--verify", signatureFile.toString(), inputFile.toString())

        assertEquals(0, result.exitCode, result.stderr)
    }

    @Test
    fun `gpg decrypts service-created armored message when available`() {
        if (!GpgCliTestSupport.isGpgAvailable()) {
            return
        }
        val home = createTempDirectory("keyguard-gpg-decrypt")
        if (!tryImportPrivateKey(home)) {
            return
        }
        val encryptedFile = home.resolve("message.asc")
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "gpg decrypt interop",
                publicKeys = listOf(publicKey),
            ),
        )
        encryptedFile.writeText(encrypted)

        val result = GpgCliTestSupport.runGpg(
            home,
            "--batch",
            "--yes",
            "--pinentry-mode",
            "loopback",
            "--decrypt",
            encryptedFile.toString(),
        )

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("gpg decrypt interop", result.stdout.trimEnd())
    }

    @Test
    fun `clear-sign round trip with dash and marker lines`() {
        // The body contains lines that collide with clear-signing's dash-escaping and
        // armor markers: a line starting with '-', a line that is literally the
        // signature BEGIN marker, a line that is the signed-message BEGIN marker, and a
        // line carrying trailing whitespace. A correct implementation dash-escapes them
        // on signing and un-escapes them on verify, so the round trip must be VALID.
        val signed = service.clearSignText(
            GpgOpenPgpSignTextRequest(
                text = MARKER_LINES_TEXT,
                privateKey = privateKey,
            ),
        )

        val verification = service.verifyClearSignedText(
            GpgOpenPgpVerifyTextRequest(
                signedText = signed,
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
    }

    @Test
    fun `clear-sign of text without trailing newline emits well-formed armor`() {
        // RFC 4880 §7: the armor header line must begin a line, and the line ending
        // immediately preceding it is not part of the signed text. Signing input that
        // lacks a final newline must therefore still put the BEGIN PGP SIGNATURE marker
        // at a line start (not glued onto the last body line) without changing the
        // signed bytes — so the signature must remain VALID.
        val signed = service.clearSignText(
            GpgOpenPgpSignTextRequest(
                text = "no trailing newline",
                privateKey = privateKey,
            ),
        )

        assertTrue(
            "\n-----BEGIN PGP SIGNATURE-----" in signed,
            "armor signature marker must begin a line",
        )
        val verification = service.verifyClearSignedText(
            GpgOpenPgpVerifyTextRequest(
                signedText = signed,
                publicKeys = listOf(publicKey),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
        assertEquals("D0BBCFBB250D3BB0658E5384F83D947D29EFECF7", verification.fingerprint)
    }

    @Test
    fun `clear-sign with marker lines verifies with gpg when available`() {
        if (!GpgCliTestSupport.isGpgAvailable()) {
            return
        }
        val home = createTempDirectory("keyguard-gpg-marker")
        if (!tryImportPublicKey(home)) {
            return
        }
        val signedFile = home.resolve("signed.asc")
        signedFile.writeText(
            service.clearSignText(
                GpgOpenPgpSignTextRequest(
                    text = MARKER_LINES_TEXT,
                    privateKey = privateKey,
                ),
            ),
        )

        val result = GpgCliTestSupport.runGpg(home, "--batch", "--verify", signedFile.toString())

        assertEquals(0, result.exitCode, result.stderr)
    }

    @Test
    fun `rsa encryption targets the encryption subkey`() {
        // The RSA fixture's primary key is itself encryption-capable (RSA_GENERAL), but
        // gpg always encrypts to the dedicated encryption SUBKEY. Encrypting to the
        // primary produces a message a real gpg client cannot route to its agent.
        val rsaPublicKey = GpgOpenPgpPublicKey(publicKeyArmoredOf(GpgTestKeyFixtures.RSA))
        val encrypted = service.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = "target the subkey",
                publicKeys = listOf(rsaPublicKey),
            ),
        )

        val subkeyId = rsaEncryptionSubkeyId()
        val keyIds = decodeEncryptedDataList(encrypted)
            .encryptedDataObjects
            .asSequence()
            .filterIsInstance<PGPPublicKeyEncryptedData>()
            .map { it.keyID }
            .toList()

        assertTrue(keyIds.isNotEmpty(), "expected at least one public-key encrypted session key")
        keyIds.forEach { keyId ->
            assertEquals(
                subkeyId,
                keyId,
                "encryption must target the RSA encryption subkey, not the primary",
            )
        }
    }

    @Test
    fun `preferred RSA primary fingerprint selects its authenticated signing subkey`() {
        val generated = NativeGpgKeyGenerator.generate(
            GpgKeyConfig.Rsa(
                userId = "Signing selection <signing-selection@test.invalid>",
                length = GpgKeyConfig.RsaLength.B3072,
            ),
        )
        val signingFingerprint = generated.metadata.keys.single { it.canSign }.fingerprint
        val text = "sign with authenticated capability"

        val armoredSignature = service.signTextDetached(
            GpgOpenPgpSignTextRequest(
                text = text,
                privateKey = GpgOpenPgpPrivateKey(
                    armored = generated.privateKeyArmored,
                    preferredFingerprint = generated.fingerprint,
                ),
            ),
        )
        val verification = service.verifyDetachedText(
            GpgOpenPgpVerifyDetachedTextRequest(
                text = text,
                signature = armoredSignature,
                publicKeys = listOf(GpgOpenPgpPublicKey(generated.publicKeyArmored)),
            ),
        )

        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
        assertEquals(signingFingerprint, verification.fingerprint)
    }

    @Test
    fun `revoked primary invalidates an otherwise usable encryption subkey`() {
        val secretCollection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(CV25519_SECRET_KEY.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val secretRing = secretCollection.keyRings.next()
        val certificate = PGPPublicKeyRing(secretRing.publicKeys.asSequence().toList())
        val primary = certificate.publicKey
        val revocation = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(primary.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME),
            primary,
        ).apply {
            init(
                PGPSignature.KEY_REVOCATION,
                secretRing.secretKey.extractPrivateKeyEmptyPassphrase(),
            )
        }.generateCertification(primary)
        val revokedCertificate = PGPPublicKeyRing.insertPublicKey(
            certificate,
            PGPPublicKey.addCertification(primary, revocation),
        )
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            revokedCertificate.encode(armoredOut)
        }

        assertFailsWith<IllegalStateException> {
            service.encryptText(
                GpgOpenPgpEncryptTextRequest(
                    text = "do not encrypt",
                    publicKeys = listOf(
                        GpgOpenPgpPublicKey(out.toString(Charsets.UTF_8)),
                    ),
                ),
            )
        }
    }

    @Test
    fun `message without integrity protection is rejected`() {
        // A message wrapped in a bare SED packet (no MDC / integrity packet) is malleable
        // and must be refused; silently decrypting it re-introduces the classic OpenPGP
        // integrity-oracle weakness.
        val recipient = encryptionPublicKeyOf(GpgTestKeyFixtures.CV25519)
        val message = encryptWithoutIntegrity("no integrity", recipient)

        assertFailsWith<Exception> {
            service.decryptText(
                GpgOpenPgpDecryptTextRequest(
                    encryptedText = message,
                    privateKeys = listOf(privateKey),
                ),
            )
        }
    }

    private fun rsaEncryptionSubkeyId(): Long {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(GpgTestKeyFixtures.RSA.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        return collection.keyRings.next()
            .publicKeys
            .asSequence()
            .first { !it.isMasterKey && it.isEncryptionKey }
            .keyID
    }

    private fun encryptionPublicKeyOf(
        secretArmored: String,
    ): PGPPublicKey {
        val collection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(secretArmored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        return collection.keyRings.asSequence()
            .flatMap { it.publicKeys.asSequence() }
            .first { it.isEncryptionKey }
    }

    private fun decodeEncryptedDataList(
        armored: String,
    ): PGPEncryptedDataList {
        val factory = JcaPGPObjectFactory(
            PGPUtil.getDecoderStream(ByteArrayInputStream(armored.encodeToByteArray())),
        )
        var obj = factory.nextObject()
        while (obj != null && obj !is PGPEncryptedDataList) {
            obj = factory.nextObject()
        }
        return obj as? PGPEncryptedDataList
            ?: throw IllegalStateException("The message does not contain an encrypted data list.")
    }

    private fun encryptWithoutIntegrity(
        text: String,
        recipient: PGPPublicKey,
    ): String {
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            val encryptedDataGenerator = PGPEncryptedDataGenerator(
                JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .setSecureRandom(SecureRandom())
                    .setWithIntegrityPacket(false),
            )
            encryptedDataGenerator.addMethod(
                JcePublicKeyKeyEncryptionMethodGenerator(recipient)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME),
            )
            val encryptedOut = encryptedDataGenerator.open(armoredOut, ByteArray(BUFFER_SIZE))
            val literalDataGenerator = PGPLiteralDataGenerator()
            val literalOut = literalDataGenerator.open(
                encryptedOut,
                PGPLiteralData.BINARY,
                PGPLiteralData.CONSOLE,
                Date(),
                ByteArray(BUFFER_SIZE),
            )
            literalOut.write(text.encodeToByteArray())
            literalDataGenerator.close()
            encryptedDataGenerator.close()
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun tryImportPublicKey(home: Path): Boolean {
        val publicKeyFile = home.resolve("public.asc")
        publicKeyFile.writeText(publicKey.armored)
        val result = GpgCliTestSupport.runGpg(home, "--batch", "--import", publicKeyFile.toString())
        return result.exitCode == 0
    }

    private fun tryImportPrivateKey(home: Path): Boolean {
        val privateKeyFile = home.resolve("private.asc")
        privateKeyFile.writeText(privateKey.armored)
        val result = GpgCliTestSupport.runGpg(home, "--batch", "--import", privateKeyFile.toString())
        return result.exitCode == 0
    }

    private fun publicKeyArmoredOf(
        secretArmored: String,
    ): String {
        val secretCollection = PGPSecretKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(secretArmored.encodeToByteArray())),
            JcaKeyFingerprintCalculator(),
        )
        val secretRing = secretCollection.keyRings.next()
        val publicRing = PGPPublicKeyRing(
            secretRing.publicKeys.asSequence().toList(),
        )
        val out = ByteArrayOutputStream()
        ArmoredOutputStream(out).use { armoredOut ->
            publicRing.encode(armoredOut)
        }
        return out.toString(Charsets.UTF_8)
    }

    companion object {
        private const val BUFFER_SIZE = 4096

        // A clear-sign body deliberately packed with lines that stress the escaping and
        // marker handling: a leading-dash line, the literal signature/signed-message
        // markers, and a line with trailing whitespace.
        private val MARKER_LINES_TEXT = buildString {
            append("- leading dash\n")
            append("-----BEGIN PGP SIGNATURE-----\n")
            append("-----BEGIN PGP SIGNED MESSAGE-----\n")
            append("trailing whitespace here   ")
        }

        // Real, unprotected (empty-passphrase) Ed25519 cert primary with a
        // CV25519 (algorithm 18, ECDH) encryption subkey. User-id:
        // "Keyguard Test CV25519 <cv25519@test.invalid>".
        private val CV25519_SECRET_KEY = """
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
        """.trimIndent()
    }
}
