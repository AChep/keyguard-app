package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

object GpgKeyFactory {

    data class GeneratedKeys(
        val rsa: TestGpgKey,
        val ed25519: TestGpgKey,
        val nistp256: TestGpgKey,
    ) {
        val all: List<TestGpgKey> get() = listOf(rsa, ed25519, nistp256)
    }

    /**
     * Generate the three key types in [serverHome].
     * Leaves the server gpg-agent running; the caller is responsible for killing it.
     */
    fun generate(serverHome: Path): GeneratedKeys {
        prepareHome(serverHome)
        val gpg = GpgCli(serverHome)

        val rsa = generateKeyPair(
            gpg = gpg,
            name = "Keyguard RSA",
            email = "rsa@keyguard.test.invalid",
            primaryAlgo = "rsa2048",
            encryptAlgo = "rsa2048",
        )
        val ed25519 = generateKeyPair(
            gpg = gpg,
            name = "Keyguard Ed25519",
            email = "ed25519@keyguard.test.invalid",
            primaryAlgo = "ed25519",
            encryptAlgo = "cv25519",
        )
        val nistp256 = generateKeyPair(
            gpg = gpg,
            name = "Keyguard NistP256",
            email = "nistp256@keyguard.test.invalid",
            primaryAlgo = "nistp256",
            encryptAlgo = "nistp256",
        )
        return GeneratedKeys(rsa = rsa, ed25519 = ed25519, nistp256 = nistp256)
    }

    private fun prepareHome(home: Path) {
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
        // Permit loopback pinentry so we can generate/use keys with an empty passphrase
        // without any interactive prompt.
        Files.writeString(home.resolve("gpg-agent.conf"), "allow-loopback-pinentry\n")
        Files.writeString(home.resolve("gpg.conf"), "pinentry-mode loopback\n")
    }

    private fun generateKeyPair(
        gpg: GpgCli,
        name: String,
        email: String,
        primaryAlgo: String,
        encryptAlgo: String,
    ): TestGpgKey {
        val userId = "$name <$email>"

        val genResult = gpg.run(
            "--batch",
            "--pinentry-mode", "loopback",
            "--passphrase", "",
            "--quick-gen-key", userId, primaryAlgo, "sign", "never",
        )
        require(genResult.isSuccess) {
            "Failed to generate primary key for $userId:\n${genResult.stderr}"
        }

        val primaryFingerprint = primaryFingerprintOf(gpg, email)

        val addResult = gpg.run(
            "--batch",
            "--pinentry-mode", "loopback",
            "--passphrase", "",
            "--quick-add-key", primaryFingerprint, encryptAlgo, "encr", "never",
        )
        require(addResult.isSuccess) {
            "Failed to add encryption subkey for $userId:\n${addResult.stderr}"
        }

        val metadataKeys = parseMetadataKeys(gpg, primaryFingerprint)
        require(metadataKeys.any { it.canSign }) {
            "Expected a signing key for $userId, got: $metadataKeys"
        }
        require(metadataKeys.any { it.canDecrypt }) {
            "Expected an encryption key for $userId, got: $metadataKeys"
        }

        val secretArmored = exportSecretKeyArmored(gpg, primaryFingerprint)
        val publicArmored = exportPublicKeyArmored(gpg, primaryFingerprint)

        return TestGpgKey(
            name = userId,
            privateKeyArmored = secretArmored,
            publicKeyArmored = publicArmored,
            primaryFingerprint = primaryFingerprint,
            metadataKeys = metadataKeys,
        )
    }

    private fun primaryFingerprintOf(
        gpg: GpgCli,
        email: String,
    ): String {
        val result = gpg.run("--list-secret-keys", "--with-colons", email)
        require(result.isSuccess) { "Failed to list secret key for $email:\n${result.stderr}" }
        return result.stdout.lineSequence()
            .firstOrNull { it.startsWith("fpr:") }
            ?.split(":")
            ?.getOrNull(9)
            ?.takeIf { it.isNotBlank() }
            ?: error("Could not find fingerprint for $email in:\n${result.stdout}")
    }

    private fun exportSecretKeyArmored(
        gpg: GpgCli,
        fingerprint: String,
    ): String {
        val result = gpg.run(
            "--batch",
            "--pinentry-mode", "loopback",
            "--passphrase", "",
            "--export-secret-keys", "--armor", fingerprint,
        )
        require(result.isSuccess && result.stdout.contains("PRIVATE KEY BLOCK")) {
            "Failed to export secret key $fingerprint:\n${result.stderr}"
        }
        return result.stdout
    }

    private fun exportPublicKeyArmored(
        gpg: GpgCli,
        fingerprint: String,
    ): String {
        val result = gpg.run("--export", "--armor", fingerprint)
        require(result.isSuccess && result.stdout.contains("PUBLIC KEY BLOCK")) {
            "Failed to export public key $fingerprint:\n${result.stderr}"
        }
        return result.stdout
    }

    /**
     * Parse `gpg --list-secret-keys --with-keygrip --with-colons` into per-(sub)key
     * metadata. Each `sec`/`ssb` record is followed by its `fpr` and `grp` records.
     * The colon record's field 12 (0-based index 11) carries the lowercase capability
     * letters (s/e/a) usable for THIS specific (sub)key.
     */
    fun parseMetadataKeys(
        gpg: GpgCli,
        fingerprint: String,
    ): List<GpgAgentKeyMetadataKey> {
        val result = gpg.run("--list-secret-keys", "--with-keygrip", "--with-colons", fingerprint)
        require(result.isSuccess) {
            "Failed to list secret keys for $fingerprint:\n${result.stderr}"
        }
        return parseColonListing(result.stdout)
    }

    internal fun parseColonListing(listing: String): List<GpgAgentKeyMetadataKey> {
        data class Pending(
            val capabilities: Set<String>,
            val algorithm: String,
            var fingerprint: String? = null,
            var keygrip: String? = null,
        )

        val result = mutableListOf<GpgAgentKeyMetadataKey>()
        var current: Pending? = null

        fun flush() {
            val pending = current ?: return
            val fpr = pending.fingerprint
            val grp = pending.keygrip
            if (fpr != null && grp != null) {
                result += GpgAgentKeyMetadataKey(
                    keygrip = grp,
                    fingerprint = fpr,
                    algorithm = pending.algorithm,
                    capabilities = pending.capabilities,
                )
            }
            current = null
        }

        for (line in listing.lineSequence()) {
            val fields = line.split(":")
            when (fields.getOrNull(0)) {
                "sec", "ssb" -> {
                    flush()
                    val capsField = fields.getOrNull(11).orEmpty()
                    // Per-(sub)key usable capabilities are the lowercase letters; the
                    // uppercase letters describe the whole key's combined capabilities.
                    val capabilities = capsField
                        .filter { it.isLowerCase() }
                        .map { it.toString() }
                        .toSet()
                    val algorithm = fields.getOrNull(16).orEmpty()
                    current = Pending(
                        capabilities = capabilities,
                        algorithm = algorithm,
                    )
                }

                "fpr" -> {
                    val fpr = fields.getOrNull(9)?.takeIf { it.isNotBlank() }
                    if (fpr != null && current != null && current!!.fingerprint == null) {
                        current!!.fingerprint = fpr
                    }
                }

                "grp" -> {
                    val grp = fields.getOrNull(9)?.takeIf { it.isNotBlank() }
                    if (grp != null && current != null && current!!.keygrip == null) {
                        current!!.keygrip = grp
                    }
                }
            }
        }
        flush()
        return result
    }
}
