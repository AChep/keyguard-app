package com.artemchep.keyguard.gpge2e

import com.artemchep.keyguard.common.service.crypto.GpgKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.crypto.NativeGpgKeyImportService
import java.nio.file.Path

/**
 * Issue 1684: an Ed25519 key whose primary key and Cv25519 subkey
 * are protected by different passphrases, as gpg exports it.
 */
data class SplitPassphraseKey(
    val userId: String,
    /** The armored gpg export, still protected per component. */
    val protectedSecretArmored: String,
    val primaryPassphrase: String,
    val subkeyPassphrase: String,
    /** gpg's view of the primary key and the subkey, in that order. */
    val components: List<Component>,
) {
    val primary: Component get() = components.first()
    val subkey: Component get() = components.last()

    data class Component(
        val fingerprint: String,
        val keyId: String,
        val canSign: Boolean,
        val canEncrypt: Boolean,
        val keygrip: String,
    )

    /** Runs the app's importer on the protected export. */
    fun import(vararg passphrases: String): GpgKeyImportResult =
        NativeGpgKeyImportService.import(
            GpgKeyImportRequest(
                content = protectedSecretArmored.encodeToByteArray(),
                passphrases = passphrases.toList(),
            ),
        )
}

object GpgSplitPassphraseKeyFactory {
    private const val PRIMARY_PASSPHRASE = "primary-1684"
    private const val SUBKEY_PASSPHRASE = "subkey-1684"

    /**
     * Generate a [SplitPassphraseKey] in [home]. Leaves the gpg-agent
     * running; the caller is responsible for killing it.
     */
    fun generate(home: Path): SplitPassphraseKey {
        // The agent must not cache passphrases, or it answers a later prompt
        // with an earlier passphrase and protects a component with the wrong one.
        // The minimum S2K count keeps each passphrase derivation fast.
        GpgKeyFactory.prepareHome(
            home = home,
            agentConfig = "allow-loopback-pinentry\ndefault-cache-ttl 0\nmax-cache-ttl 0\ns2k-count 65536\n",
        )
        val gpg = GpgCli(home)
        val email = "split-passphrase@keyguard.test.invalid"
        val userId = "Keyguard Split Passphrase <$email>"
        val primaryFingerprint = GpgKeyFactory.createKey(
            gpg = gpg,
            userId = userId,
            email = email,
            primaryAlgo = "ed25519",
            encryptAlgo = "cv25519",
            passphrase = PRIMARY_PASSPHRASE,
        )
        val components = parseComponents(gpg, primaryFingerprint)
        require(components.size == 2 && components[0].canSign && components[1].canEncrypt) {
            "Expected a signing primary key and an encryption subkey, got: $components"
        }
        changePassphrase(gpg, keygrip = components[1].keygrip)
        return SplitPassphraseKey(
            userId = userId,
            protectedSecretArmored = exportPerComponent(gpg, primaryFingerprint),
            primaryPassphrase = PRIMARY_PASSPHRASE,
            subkeyPassphrase = SUBKEY_PASSPHRASE,
            components = components,
        )
    }

    /**
     * `gpg --passwd` re-protects every component, so change one
     * component's passphrase through the agent by its keygrip.
     */
    private fun changePassphrase(gpg: GpgCli, keygrip: String) {
        val result = gpg.connectAgent(
            "OPTION pinentry-mode=loopback",
            "/let old $PRIMARY_PASSPHRASE",
            "/let new $SUBKEY_PASSPHRASE",
            "/definq PASSPHRASE old",
            "/definq NEW_PASSPHRASE new",
            "PASSWD $keygrip",
            "/bye",
        )
        // gpg-connect-agent exits with 0 even when the agent answers ERR.
        val lines = result.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(
            result.isSuccess &&
                lines.lastOrNull() == "OK" &&
                lines.none { it.startsWith("ERR") },
        ) {
            "Failed to change the passphrase of $keygrip:\n${result.stdout}\n${result.stderr}"
        }
    }

    /**
     * gpg asks for one passphrase per exported secret component, primary key first.
     * A wrong answer makes it skip that component and exit with an error.
     */
    private fun exportPerComponent(gpg: GpgCli, fingerprint: String): String {
        val export = gpg.run(
            "--batch",
            "--pinentry-mode", "loopback",
            "--command-fd", "0",
            "--status-fd", "2",
            "--export-secret-keys", "--armor", fingerprint,
            stdin = "$PRIMARY_PASSPHRASE\n$SUBKEY_PASSPHRASE\n".encodeToByteArray(),
        )
        require(export.isSuccess && export.stdout.contains("PRIVATE KEY BLOCK")) {
            "Failed to export split passphrase key $fingerprint:\n${export.stderr}"
        }
        val packets = gpg.run("--list-packets", stdin = export.stdout.encodeToByteArray())
        require(
            packets.stdout.contains(":secret key packet:") &&
                packets.stdout.contains(":secret sub key packet:"),
        ) {
            "Expected both secret components in the export:\n${packets.stdout}\n${packets.stderr}"
        }
        return export.stdout
    }

    /** gpg lists the primary key (`sec`) first, then its subkeys (`ssb`). */
    private fun parseComponents(
        gpg: GpgCli,
        fingerprint: String,
    ): List<SplitPassphraseKey.Component> =
        GpgKeyFactory.listSecretKeys(gpg, fingerprint).map { (keyId, metadataKey) ->
            SplitPassphraseKey.Component(
                fingerprint = metadataKey.fingerprint,
                keyId = keyId,
                canSign = metadataKey.canSign,
                canEncrypt = metadataKey.canDecrypt,
                keygrip = metadataKey.keygrip,
            )
        }
}
