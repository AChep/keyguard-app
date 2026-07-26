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
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/** External-client coverage for the intentionally modernized OpenPGP output selection. */
class NativeCryptoOpenPgpGnuPgInteropTest {
    @Test
    fun generatedModernRecipientSelectsTag20OcbAndGnuPgDecryptsIt() {
        if (!gpgSupportsOcb()) return

        val material = generateModern("OpenPGP OCB interop <openpgp-ocb@test.invalid>")
        val plaintext = "native GnuPG OCB interoperability".encodeToByteArray()
        val encrypted = NativeCrypto.openPgp.encrypt(
            content = plaintext,
            publicKeys = listOf(material.publicKeyArmored),
            fileName = "ocb.txt",
            armored = false,
        )
        try {
            assertEquals(NativeOpenPgpProtectionMode.GNUPG_OCB, encrypted.protectionMode)
            val home = isolatedGpgHome("keyguard-openpgp-ocb")
            try {
                val message = home.resolve("message.pgp").also { it.writeBytes(encrypted.data) }
                importSecretKey(home, material.privateKeyArmored)
                val packets = runGpg(home, "--batch", "--list-packets", message.toString())
                assertEquals(0, packets.exitCode, packets.stderr)
                assertTrue(
                    ":aead encrypted packet:" in packets.stdout || "tag=20" in packets.stdout,
                    packets.stdout,
                )
                val decrypted = decryptWithGpg(home, message)
                assertEquals(plaintext.decodeToString(), decrypted.stdout)
            } finally {
                disposeGpgHome(home)
            }
        } finally {
            plaintext.fill(0)
            encrypted.data.fill(0)
            material.privateKeyArmored.fill(0)
            material.publicKeyArmored.fill(0)
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
            fileName = "mdc.txt",
            armored = false,
        )
        try {
            assertEquals(NativeOpenPgpProtectionMode.SEIPD_V1_MDC, encrypted.protectionMode)
            val home = isolatedGpgHome("keyguard-openpgp-mdc")
            try {
                val message = home.resolve("message.pgp").also { it.writeBytes(encrypted.data) }
                importSecretKey(home, material.privateKeyArmored)
                val packets = runGpg(home, "--batch", "--list-packets", message.toString())
                assertEquals(0, packets.exitCode, packets.stderr)
                assertTrue("mdc_method: 2" in packets.stdout, packets.stdout)
                val decrypted = decryptWithGpg(home, message)
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
            "--decrypt",
            message.toString(),
        )
        assertEquals(0, result.exitCode, result.stderr)
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

    private fun gpgSupportsOcb(): Boolean {
        if (!isGpgAvailable()) return false
        val firstLine = runGpg(null, "--version").stdout.lineSequence().firstOrNull().orEmpty()
        val match = Regex("(\\d+)\\.(\\d+)").find(firstLine) ?: return false
        val (major, minor) = match.destructured
        return major.toInt() > 2 || major.toInt() == 2 && minor.toInt() >= 3
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
    )
}
