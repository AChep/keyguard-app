package com.artemchep.keyguard.common.service.imports.mapping

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService

/**
 * Bounds the work a hostile value can cause; a 16384-bit RSA key in PEM is
 * well under this.
 */
private const val MAX_SSH_PRIVATE_KEY_LENGTH = 64 * 1024

/**
 * Converts an exported private key — PKCS#8, PEM, OpenSSH or PuTTY — into the
 * key pair Keyguard stores, or `null` if it cannot be read with the supplied
 * passphrase or at all.
 *
 * The key is read without a passphrase first; the [passphrase] is used only
 * if the key turns out to be encrypted, so a stale one next to an unencrypted
 * key changes nothing. An empty passphrase is no passphrase.
 */
internal fun importedSshKeyOrNull(
    privateKey: String?,
    sshKeyImportService: SshKeyImportService,
    passphrase: String? = null,
): CreateRequest.SshKey? {
    val text = privateKey
        ?.takeIf { it.isNotBlank() && it.length <= MAX_SSH_PRIVATE_KEY_LENGTH }
        ?: return null
    return importedSshKeyPairOrNull(text.encodeToByteArray(), sshKeyImportService, passphrase)
        ?.toImportedSshKey()
}

/**
 * The key pair of the private key in [content], see [importedSshKeyOrNull].
 * The [content] is not cleared.
 */
internal fun importedSshKeyPairOrNull(
    content: ByteArray,
    sshKeyImportService: SshKeyImportService,
    passphrase: String? = null,
): KeyPair? {
    val result = sshKeyImportService.importOrNull(content, passphrase = null)
        .let { result ->
            val retryPassphrase = passphrase?.takeIf { it.isNotEmpty() }
            if (result is SshKeyImportResult.NeedsPassphrase && retryPassphrase != null) {
                sshKeyImportService.importOrNull(content, passphrase = retryPassphrase)
            } else {
                result
            }
        }
    return (result as? SshKeyImportResult.Success)?.keyPair
}

/** The key pair as the vault stores an SSH key. */
internal fun KeyPair.toImportedSshKey(): CreateRequest.SshKey = CreateRequest.SshKey(
    privateKey = privateKey.ssh,
    publicKey = publicKey.ssh,
    fingerprint = publicKey.fingerprint,
)

private fun SshKeyImportService.importOrNull(
    content: ByteArray,
    passphrase: String?,
): SshKeyImportResult? = runCatchingNonFatal {
    import(
        SshKeyImportRequest(
            content = content,
            passphrase = passphrase,
        ),
    )
}.getOrNull()

/**
 * The fields that keep an SSH key the vault could not convert. The item
 * becomes a note: an SSH key item with an unreadable private key would still
 * be offered by the SSH agent, which could not sign with it.
 */
internal fun importedSshKeyFallbackFields(
    privateKeys: List<String?>,
    publicKey: String?,
    fingerprint: String?,
): List<DSecret.Field> = privateKeys
    .distinct()
    .mapNotNull { importedFieldOrNull(IMPORT_SSH_PRIVATE_KEY_LABEL, it, hidden = true) } +
    listOfNotNull(
        importedFieldOrNull(IMPORT_SSH_PUBLIC_KEY_LABEL, publicKey),
        importedFieldOrNull(IMPORT_SSH_FINGERPRINT_LABEL, fingerprint),
    )

/**
 * The source's public key and fingerprint, kept as fields when they disagree
 * with the ones derived from the private key. The derived ones go into the
 * key; a matching copy is dropped.
 */
internal fun CreateRequest.SshKey.mismatchedSourceFields(
    publicKey: String?,
    fingerprint: String?,
): List<DSecret.Field> = listOfNotNull(
    publicKey
        ?.takeUnless(::hasPublicKey)
        ?.let { importedFieldOrNull(IMPORT_SSH_PUBLIC_KEY_LABEL, it) },
    fingerprint
        ?.takeUnless { it.trim() == this.fingerprint?.trim() }
        ?.let { importedFieldOrNull(IMPORT_SSH_FINGERPRINT_LABEL, it) },
)

/**
 * Whether [publicKey] is this key's public key. Public keys match on their
 * algorithm and key data, ignoring the comment.
 */
internal fun CreateRequest.SshKey.hasPublicKey(
    publicKey: String,
): Boolean = this.publicKey?.sshPublicKeyIdentity() == publicKey.sshPublicKeyIdentity()

private val whitespace = Regex("\\s+")

/** The algorithm and key data of an OpenSSH public key, without its comment. */
private fun String.sshPublicKeyIdentity(): List<String> = trim()
    .split(whitespace)
    .take(2)
