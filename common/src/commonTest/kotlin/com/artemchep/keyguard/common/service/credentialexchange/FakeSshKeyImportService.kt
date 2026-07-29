package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.SshKeyImportError
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService

/**
 * A test double for [SshKeyImportService] that returns a canned result and
 * records the requests, so mapper tests can assert the PEM the mapper builds
 * without touching the native crypto library.
 *
 * [results] scripts the outcome per call, falling back to [result] once
 * exhausted, so a document with two SSH keys can have one convert and one fail.
 * [error] makes the seam *raise* instead of returning — the real one is not
 * total (an oversized key or an unavailable backend throws), and the
 * `runCatching` that absorbs it in `importSshKeyPair` is otherwise untested.
 */
class FakeSshKeyImportService(
    private val result: SshKeyImportResult = SshKeyImportResult.Success(fakeSshKeyPair()),
    private val results: List<SshKeyImportResult> = emptyList(),
    private val error: Throwable? = null,
) : SshKeyImportService {
    val requests = mutableListOf<SshKeyImportRequest>()

    val lastRequest: SshKeyImportRequest? get() = requests.lastOrNull()

    val callCount: Int get() = requests.size

    override fun import(
        request: SshKeyImportRequest,
    ): SshKeyImportResult {
        requests += request
        error?.let { throw it }
        return results.getOrNull(requests.size - 1) ?: result
    }
}

@Suppress("LongParameterList")
fun fakeSshKeyPair(
    privateKeyPem: String = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n" +
        "-----END OPENSSH PRIVATE KEY-----\n",
    publicKeyOpenSsh: String = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFake test@example.com",
    fingerprint: String = "SHA256:fakefingerprint",
    privateFingerprint: String = "SHA256:fakeprivatefingerprint",
    type: KeyPair.Type = KeyPair.Type.ED25519,
) = KeyPair(
    type = type,
    privateKey = KeyPair.KeyParameter(
        encoded = byteArrayOf(),
        type = type,
        ssh = privateKeyPem,
        fingerprint = privateFingerprint,
    ),
    publicKey = KeyPair.KeyParameter(
        encoded = byteArrayOf(),
        type = type,
        ssh = publicKeyOpenSsh,
        fingerprint = fingerprint,
    ),
)

fun sshKeyImportFailure(
    reason: SshKeyImportError = SshKeyImportError.MalformedKey,
) = SshKeyImportResult.Error(
    reason = reason,
)

fun sshKeyImportNeedsPassphrase(
    formatLabel: String = "OpenSSH",
) = SshKeyImportResult.NeedsPassphrase(
    formatLabel = formatLabel,
)
