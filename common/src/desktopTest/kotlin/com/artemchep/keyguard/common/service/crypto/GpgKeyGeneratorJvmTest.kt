package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.common.model.GpgKeyExpiry
import com.artemchep.keyguard.crypto.GpgKeyGeneratorJvm
import com.artemchep.keyguard.crypto.GpgOpenPgpServiceJvm
import com.artemchep.keyguard.crypto.GpgPublicKeyParserJvm
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.security.Security
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class GpgKeyGeneratorJvmTest {
    private val generator = GpgKeyGeneratorJvm()
    private val parser = GpgPublicKeyParserJvm()
    private val openPgpService = GpgOpenPgpServiceJvm()

    @BeforeTest
    fun setup() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Test
    fun `modern key generates valid armored rings metadata and usable key material`() {
        assertGeneratedKeyWorks(
            config = GpgKeyConfig.Modern(
                userId = "Keyguard Test <modern-gpg@test.invalid>",
            ),
        )
    }

    @Test
    fun `rsa key generates valid armored rings metadata and usable key material`() {
        assertGeneratedKeyWorks(
            config = GpgKeyConfig.Rsa(
                userId = "Keyguard Test <rsa-gpg@test.invalid>",
                length = GpgKeyConfig.RsaLength.B3072,
            ),
        )
    }

    @Test
    fun `default expiry is applied to every generated component`() {
        val generated = generator.generate(
            GpgKeyConfig.Modern(
                userId = "Keyguard Test <default-expiry@test.invalid>",
            ),
        )

        val parsed = parser.parse(generated.publicKeyArmored)
        assertTrue(parsed is GpgPublicKeyParseResult.Success, "expected Success, got $parsed")
        val key = parsed.keys.single()
        val primaryCreatedAt = assertNotNull(key.createdAt)
        val expectedExpiry = assertNotNull(
            GpgKeyExpiry.default.resolve(
                creationTime = primaryCreatedAt,
                timeZone = TimeZone.currentSystemDefault(),
            ),
        )
        assertEquals(
            expectedExpiry,
            key.expiresAt,
        )
        key.subKeys.forEach { subkey ->
            assertEquals(
                expectedExpiry,
                subkey.expiresAt,
            )
        }
    }

    @Test
    fun `never expiry is applied to every generated component`() {
        val generated = generator.generate(
            GpgKeyConfig.Modern(
                userId = "Keyguard Test <never-expiry@test.invalid>",
                expiry = GpgKeyExpiry.Never,
            ),
        )

        val parsed = parser.parse(generated.publicKeyArmored)
        assertTrue(parsed is GpgPublicKeyParseResult.Success, "expected Success, got $parsed")
        val key = parsed.keys.single()
        assertNull(key.expiresAt)
        key.subKeys.forEach { subkey ->
            assertNull(subkey.expiresAt)
        }
    }

    @Test
    fun `absolute expiry is applied to every generated component`() {
        val target = Instant.fromEpochSeconds((Clock.System.now() + 30.days).epochSeconds)
        val generated = generator.generate(
            GpgKeyConfig.Modern(
                userId = "Keyguard Test <absolute-expiry@test.invalid>",
                expiry = GpgKeyExpiry.At(target),
            ),
        )

        val parsed = parser.parse(generated.publicKeyArmored)
        assertTrue(parsed is GpgPublicKeyParseResult.Success, "expected Success, got $parsed")
        val key = parsed.keys.single()
        assertEquals(target, key.expiresAt)
        key.subKeys.forEach { subkey ->
            assertEquals(target, subkey.expiresAt)
        }
    }

    @Test
    fun `metadata resolution failure aborts generation`() {
        val generator = GpgKeyGeneratorJvm(
            metadataResolver = GpgKeyMetadataResolverUnsupported,
        )

        assertFailsWith<IllegalStateException> {
            generator.generate(
                GpgKeyConfig.Modern(
                    userId = "Keyguard Test <metadata-failure@test.invalid>",
                ),
            )
        }
    }

    @Test
    fun `modern generated key keygrips match gpg`() {
        assertGeneratedKeyInteropsWithGpg(
            config = GpgKeyConfig.Modern(
                userId = "Keyguard Test <modern-gpg-interop@test.invalid>",
            ),
        )
    }

    @Test
    fun `rsa generated key keygrips match gpg`() {
        assertGeneratedKeyInteropsWithGpg(
            config = GpgKeyConfig.Rsa(
                userId = "Keyguard Test <rsa-gpg-interop@test.invalid>",
                length = GpgKeyConfig.RsaLength.B3072,
            ),
        )
    }

    /**
     * Proves that a generated key is usable by a real gpg client: import it and compare
     * gpg's own keygrip for every (sub)key against the Kotlin-computed keygrip stored in
     * the metadata. gpg addresses agent operations purely by keygrip, so any mismatch
     * means the agent would be asked for a key it does not recognize.
     */
    private fun assertGeneratedKeyInteropsWithGpg(
        config: GpgKeyConfig,
    ) {
        if (!GpgCliTestSupport.isGpgAvailable()) {
            return
        }
        val generated = generator.generate(config)

        // A short base path keeps the gpg-agent's unix socket well under the ~104-char
        // limit (a long system temp path would make the agent fail to bind, which is
        // needed to store the passphrase-less secret key on import).
        val home = Path.of("/tmp", "kg-keygrip-${randomToken()}")
        Files.createDirectories(home)
        runCatching {
            Files.setPosixFilePermissions(
                home,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        try {
            // Permit loopback pinentry so gpg can store the passphrase-less secret key
            // via its agent without an interactive prompt.
            home.resolve("gpg-agent.conf").writeText("allow-loopback-pinentry\n")
            home.resolve("gpg.conf").writeText("pinentry-mode loopback\n")
            val privateKeyFile = home.resolve("private.asc")
            privateKeyFile.writeText(generated.privateKeyArmored)
            val importResult = GpgCliTestSupport.runGpg(home, "--batch", "--import", privateKeyFile.toString())
            assertEquals(0, importResult.exitCode, importResult.stderr)

            val listing = GpgCliTestSupport.runGpg(
                home,
                "--list-secret-keys",
                "--with-keygrip",
                "--with-colons",
            )
            assertEquals(0, listing.exitCode, listing.stderr)
            val gpgKeygrips = parseColonKeygrips(listing.stdout)

            for (key in generated.metadata.keys) {
                val gpgKeygrip = gpgKeygrips[key.fingerprint]
                assertNotNull(
                    gpgKeygrip,
                    "gpg listing has no keygrip for fingerprint ${key.fingerprint}",
                )
                assertEquals(
                    key.keygrip,
                    gpgKeygrip,
                    "keygrip mismatch for fingerprint ${key.fingerprint}",
                )
            }
        } finally {
            runCatching {
                ProcessBuilder("gpgconf", "--kill", "gpg-agent")
                    .also { it.environment()["GNUPGHOME"] = home.toString() }
                    .start()
                    .waitFor()
            }
            runCatching { home.toFile().deleteRecursively() }
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Parses `gpg --list-secret-keys --with-keygrip --with-colons` into a
     * fingerprint -> keygrip map. Each sec/ssb record is followed by its fpr and grp
     * records, both of which carry the value in field index 9.
     */
    private fun parseColonKeygrips(
        listing: String,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var fingerprint: String? = null
        for (line in listing.lineSequence()) {
            val fields = line.split(":")
            when (fields.getOrNull(0)) {
                "sec", "ssb" -> fingerprint = null
                "fpr" -> {
                    val fpr = fields.getOrNull(9)?.takeIf { it.isNotBlank() }
                    if (fpr != null && fingerprint == null) {
                        fingerprint = fpr
                    }
                }

                "grp" -> {
                    val grp = fields.getOrNull(9)?.takeIf { it.isNotBlank() }
                    val fpr = fingerprint
                    if (grp != null && fpr != null) {
                        result.putIfAbsent(fpr, grp)
                    }
                }
            }
        }
        return result
    }

    private fun assertGeneratedKeyWorks(
        config: GpgKeyConfig,
    ) {
        val generated = generator.generate(config)

        assertTrue(
            "BEGIN PGP PRIVATE KEY BLOCK" in generated.privateKeyArmored,
            "private key should be ASCII-armored",
        )
        assertTrue(
            "BEGIN PGP PUBLIC KEY BLOCK" in generated.publicKeyArmored,
            "public key should be ASCII-armored",
        )
        assertTrue(
            Regex("[0-9A-F]{40}").matches(generated.fingerprint),
            "fingerprint should be uppercase SHA-1 hex",
        )
        assertTrue(
            generated.metadata.keys.any { it.fingerprint == generated.fingerprint },
            "metadata should contain the primary fingerprint",
        )
        assertTrue(
            generated.metadata.keys.all { it.keygrip.matches(Regex("[0-9A-F]{40}")) },
            "all generated keygrips should be nonblank uppercase SHA-1 hex",
        )
        assertTrue(
            generated.metadata.keys.any { it.canSign },
            "metadata should include a signing-capable key",
        )
        assertTrue(
            generated.metadata.keys.any { it.canDecrypt },
            "metadata should include a decrypt-capable key",
        )

        val parsed = parser.parse(generated.publicKeyArmored)
        assertTrue(parsed is GpgPublicKeyParseResult.Success, "expected Success, got $parsed")
        val parsedKey = parsed.keys.single()
        assertEquals(generated.fingerprint, parsedKey.fingerprint)
        assertTrue(parsedKey.canSign, "parsed key should be sign-capable")
        assertTrue(parsedKey.canEncrypt, "parsed key should be encrypt-capable")

        val privateKey = GpgOpenPgpPrivateKey(
            armored = generated.privateKeyArmored,
            preferredFingerprint = generated.fingerprint,
        )
        val publicKey = GpgOpenPgpPublicKey(
            armored = generated.publicKeyArmored,
        )
        val message = "hello from generated ${generated.typeLabel}"
        val signed = openPgpService.clearSignText(
            GpgOpenPgpSignTextRequest(
                text = message,
                privateKey = privateKey,
            ),
        )
        val verification = openPgpService.verifyClearSignedText(
            GpgOpenPgpVerifyTextRequest(
                signedText = signed,
                publicKeys = listOf(publicKey),
            ),
        )
        assertEquals(GpgOpenPgpVerificationStatus.VALID, verification.status)
        assertTrue(
            generated.metadata.keys.any { key ->
                key.canSign && key.fingerprint == verification.fingerprint
            },
            "verification should identify an authenticated signing component",
        )

        val encrypted = openPgpService.encryptText(
            GpgOpenPgpEncryptTextRequest(
                text = message,
                publicKeys = listOf(publicKey),
            ),
        )
        val decrypted = openPgpService.decryptText(
            GpgOpenPgpDecryptTextRequest(
                encryptedText = encrypted,
                privateKeys = listOf(privateKey),
            ),
        )
        assertEquals(message, decrypted.text)
    }
}
