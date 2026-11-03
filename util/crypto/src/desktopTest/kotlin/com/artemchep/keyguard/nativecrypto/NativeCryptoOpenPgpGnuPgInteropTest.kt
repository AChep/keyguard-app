package com.artemchep.keyguard.nativecrypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** External-client coverage for the intentionally modernized OpenPGP output selection. */
class NativeCryptoOpenPgpGnuPgInteropTest {
    @Test
    fun currentGnuPgFixtureUsesTag20OcbAndPreservesPrivateLiteralDefaults() {
        if (!isGpgAvailable()) return

        val publicKey = fixture("cv25519-public.asc")
        val privateKey = fixture("cv25519-secret.asc")
        val plaintext = "native GnuPG fixture tag-20 OCB".encodeToByteArray()
        val encrypted = NativeCrypto.openPgp.encrypt(
            content = plaintext,
            publicKeys = listOf(publicKey),
            candidateRevocationKeys = emptyList(),
            fileName = "",
            armored = false,
        )
        try {
            assertEquals(NativeOpenPgpProtectionMode.GNUPG_OCB, encrypted.protectionMode)
            val home = isolatedGpgHome("keyguard-openpgp-fixture-ocb")
            try {
                val message = home.resolve("message.pgp").also { it.writeBytes(encrypted.data) }
                importSecretKey(home, privateKey)
                val packets = runGpg(home, "--batch", "--list-packets", message.toString())
                assertEquals(0, packets.exitCode, packets.stderr)
                assertTrue(":compressed packet: algo=1" in packets.stdout, packets.stdout)
                assertTrue(":literal data packet:" in packets.stdout, packets.stdout)
                assertTrue("name=\"\"" in packets.stdout, packets.stdout)
                assertTrue("created 0" in packets.stdout, packets.stdout)
                val decrypted = decryptWithGpg(home, message)
                val protection = decrypted.statusFields("DECRYPTION_INFO")
                assertEquals("0", protection.first(), decrypted.stderr)
                assertEquals("2", protection.getOrNull(2), decrypted.stderr) // OCB AEAD algorithm.
                assertEquals(plaintext.decodeToString(), decrypted.stdout)
            } finally {
                disposeGpgHome(home)
            }
        } finally {
            plaintext.fill(0)
            encrypted.data.fill(0)
            privateKey.fill(0)
            publicKey.fill(0)
        }
    }

    @Test
    fun oneNonOcbRecipientForcesMdcForEveryRecipientAndGnuPgDecryptsIt() {
        if (!isGpgAvailable()) return

        val material = generateModern("OpenPGP mixed interop <openpgp-mixed@test.invalid>")
        val mdcRecipient = fixture("mdc-public.asc")
        val plaintext = "native all-recipient MDC fallback".encodeToByteArray()
        val encrypted = NativeCrypto.openPgp.encrypt(
            content = plaintext,
            publicKeys = listOf(material.publicKeyArmored, mdcRecipient),
            candidateRevocationKeys = emptyList(),
            fileName = "mdc.txt",
            armored = false,
        )
        try {
            assertEquals(NativeOpenPgpProtectionMode.SEIPD_V1_MDC, encrypted.protectionMode)
            val home = isolatedGpgHome("keyguard-openpgp-mdc")
            try {
                val message = home.resolve("message.pgp").also { it.writeBytes(encrypted.data) }
                importSecretKey(home, material.privateKeyArmored)
                val decrypted = decryptWithGpg(home, message)
                val protection = decrypted.statusFields("DECRYPTION_INFO")
                assertEquals("2", protection.first(), decrypted.stderr) // SHA-1 MDC method.
                assertEquals("0", protection.getOrElse(2) { "0" }, decrypted.stderr)
                assertEquals(plaintext.decodeToString(), decrypted.stdout)
            } finally {
                disposeGpgHome(home)
            }
        } finally {
            plaintext.fill(0)
            mdcRecipient.fill(0)
            encrypted.data.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
        }
    }

    @Test
    fun addedIdentityAndPrimarySelectionRoundTripThroughGnuPg() {
        if (!isGpgAvailable()) return
        val now = Clock.System.now().epochSeconds
        val first = "Original <original@example.test>"
        val second = "Additional <additional@example.test>"
        val material = NativeCrypto.openPgp.generateKey(
            kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
            userId = first,
            creationTimeEpochSeconds = now - 100,
        )
        val owned = mutableListOf(material)
        val home = isolatedGpgHome("keyguard-userids")
        try {
            val added = assertIs<NativeOpenPgpUserIdUpdateResult.Success>(NativeCrypto.openPgp.updateUserId(
                material.privateKeyArmored, material.publicKeyArmored, material.fingerprint,
                NativeOpenPgpUserIdChange.Add(second), emptyList(), now - 20,
            ))
            owned += added.keyMaterial
            val parsed = assertIs<NativeOpenPgpPublicKeyParseResult.Success>(
                NativeCrypto.openPgp.parsePublicKeys(added.keyMaterial.publicKeyArmored),
            ).keys.single()
            assertEquals(first, parsed.userIdDetails.single { it.primary }.userId)
            assertEquals(listOf(first, second), parsed.userIds)
            val identity = parsed.userIdDetails.single { it.userId == second }
            val selected = assertIs<NativeOpenPgpUserIdUpdateResult.Success>(NativeCrypto.openPgp.updateUserId(
                added.keyMaterial.privateKeyArmored, added.keyMaterial.publicKeyArmored, material.fingerprint,
                NativeOpenPgpUserIdChange.MakePrimary(identity.identityId), emptyList(), now - 10,
            ))
            owned += selected.keyMaterial
            val selectedInfo = assertIs<NativeOpenPgpPublicKeyParseResult.Success>(
                NativeCrypto.openPgp.parsePublicKeys(selected.keyMaterial.publicKeyArmored),
            ).keys.single()
            assertEquals(listOf(second, first), selectedInfo.userIds)
            val file = home.resolve("public.asc").also { it.writeBytes(selected.keyMaterial.publicKeyArmored) }
            val imported = runGpg(home, "--batch", "--import", file.toString())
            assertEquals(0, imported.exitCode, imported.stderr)
            val listed = runGpg(home, "--batch", "--with-colons", "--list-keys", material.fingerprint)
            assertEquals(0, listed.exitCode, listed.stderr)
            val ids = listed.stdout.lineSequence().filter { it.startsWith("uid:") }
                .map { it.split(':')[9] }.toList()
            assertEquals(listOf(second, first), ids)
            val exported = runGpg(home, "--batch", "--armor", "--export", material.fingerprint)
            assertEquals(0, exported.exitCode, exported.stderr)
            val roundTrip = assertIs<NativeOpenPgpPublicKeyParseResult.Success>(
                NativeCrypto.openPgp.parsePublicKeys(exported.stdout.encodeToByteArray()),
            ).keys.single()
            assertEquals(second, roundTrip.userIdDetails.single { it.primary }.userId)
            assertEquals(listOf(second, first), roundTrip.userIds)
            assertEquals(material.fingerprint, roundTrip.fingerprint)
        } finally {
            owned.forEach { it.privateKeyArmored.fill(0); it.publicKeyArmored.fill(0) }
            disposeGpgHome(home)
        }
    }

    private fun generateModern(userId: String): NativeOpenPgpKeyMaterial =
        NativeCrypto.openPgp.generateKey(
            kind = NativeOpenPgpKeyKind.LEGACY_ED25519_X25519,
            userId = userId,
            creationTimeEpochSeconds = Clock.System.now().epochSeconds,
        )

    private fun importSecretKey(home: Path, secret: ByteArray) {
        val file = home.resolve("secret.asc").also { it.writeBytes(secret) }
        val result = runGpg(
            home,
            "--batch",
            "--yes",
            "--pinentry-mode",
            "loopback",
            "--passphrase",
            "",
            "--import",
            file.toString(),
        )
        assertEquals(0, result.exitCode, result.stderr)
    }

    private fun decryptWithGpg(home: Path, message: Path): GpgResult {
        val result = runGpg(
            home,
            "--batch",
            "--yes",
            "--pinentry-mode",
            "loopback",
            "--passphrase",
            "",
            "--status-fd",
            "2",
            "--decrypt",
            message.toString(),
        )
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(emptyList(), result.statusFields("DECRYPTION_OKAY"), result.stderr)
        return result
    }

    private fun fixture(name: String): ByteArray {
        val suffix = Path.of(
            "util/crypto/rust/crates/keyguard-crypto-core/tests/fixtures/openpgp",
            name,
        )
        val start = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        val path = generateSequence(start) { it.parent }
            .map { directory -> directory.resolve(suffix) }
            .firstOrNull { candidate -> candidate.isRegularFile() }
            ?: error("Could not locate checked-in OpenPGP fixture: $name")
        return path.readBytes()
    }

    private fun isolatedGpgHome(prefix: String): Path {
        val shortTempRoot = Path.of("/tmp").takeIf(Files::isDirectory)
        val home = if (shortTempRoot != null) {
            createTempDirectory(shortTempRoot, prefix)
        } else {
            createTempDirectory(prefix)
        }
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
        return home
    }

    private fun disposeGpgHome(home: Path) {
        runCatching { runGpgConf(home, "--kill", "gpg-agent") }
        home.toFile().deleteRecursively()
    }

    private fun isGpgAvailable(): Boolean {
        val gpgCommand = gpgExecutable("gpg")
        gpgExecutable("gpgconf")
        return runCatching {
            runCommand(listOf(gpgCommand, "--version"), home = null).exitCode == 0
        }.getOrDefault(false)
    }

    private fun runGpg(home: Path?, vararg arguments: String): GpgResult {
        val command = listOf(gpgExecutable("gpg"), *arguments)
        return runCommand(command, home)
    }

    private fun runGpgConf(home: Path, vararg arguments: String): GpgResult = runCommand(
        command = listOf(gpgExecutable("gpgconf"), "--homedir", home.toString(), *arguments),
        home = null,
    )

    private fun gpgExecutable(command: String): String {
        val configuredBinDir = System.getenv("KEYGUARD_GPG_BIN_DIR")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return command
        val executableName = if (
            System.getProperty("os.name", "")
                .startsWith("Windows", ignoreCase = true)
        ) {
            "$command.exe"
        } else {
            command
        }
        val executable = Path.of(configuredBinDir).resolve(executableName)
        check(executable.isRegularFile()) {
            "Configured GnuPG executable does not exist: $executable"
        }
        return executable.toString()
    }

    private fun runCommand(command: List<String>, home: Path?): GpgResult {
        val process = ProcessBuilder(command)
            .apply {
                if (home != null) environment()["GNUPGHOME"] = home.toString()
            }
            .start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("gpg timed out")
        }
        return GpgResult(
            exitCode = process.exitValue(),
            stdout = process.inputStream.readBytes().decodeToString(),
            stderr = process.errorStream.readBytes().decodeToString(),
        )
    }

    private data class GpgResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        // Unlike --list-packets labels, this is GnuPG's documented machine interface.
        // https://github.com/gpg/gnupg/blob/master/doc/DETAILS
        fun statusFields(keyword: String): List<String> {
            val prefix = "[GNUPG:] $keyword"
            val status = stderr.lineSequence()
                .filter { it == prefix || it.startsWith("$prefix ") }
                .singleOrNull()
                ?: error("Expected one $keyword status:\n$stderr")
            return status.removePrefix(prefix).trim().split(' ').filter(String::isNotEmpty)
        }
    }
}
