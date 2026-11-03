package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.ignores
import com.artemchep.keyguard.common.service.crypto.SshCertificateParser
import com.artemchep.keyguard.common.service.crypto.SshCertificateProblem
import com.artemchep.keyguard.common.service.crypto.problemAt
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val SSH_CERTIFICATES_VERSION = "1"
private const val SECONDS_IN_DAY = 86_400L

/**
 * Flags the SSH keys whose certificate the SSH agent does not offer, for
 * the same reasons the item shows.
 */
class WatchtowerSshCertificateUnusable(
    private val sshCertificateParser: SshCertificateParser,
) : WatchtowerClientTyped {
    override val type: Long
        get() = DWatchtowerAlertType.SSH_CERTIFICATE_UNUSABLE.value

    // A certificate expires or becomes valid as time passes, so re-evaluate daily.
    override fun version(): Flow<String> = flow {
        val days = Clock.System.now().epochSeconds / SECONDS_IN_DAY
        emit("$SSH_CERTIFICATES_VERSION|$days")
    }

    override suspend fun process(
        ciphers: List<DSecret>,
    ): List<WatchtowerClientResult> {
        val now = Clock.System.now().epochSeconds
        return ciphers.map { cipher ->
            val value = if (cipher.ignores(DWatchtowerAlertType.SSH_CERTIFICATE_UNUSABLE)) {
                null
            } else {
                cipher.sshCertificateProblemAt(now)?.code()
            }
            WatchtowerClientResult(
                value = value,
                threat = value != null,
                cipher = cipher,
            )
        }
    }

    private fun DSecret.sshCertificateProblemAt(
        epochSeconds: Long,
    ): SshCertificateProblem? {
        val publicKey = sshKey?.publicKey
        val certificate = sshKey?.certificate
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return sshCertificateParser
            .parse(
                certificate = certificate,
                publicKey = publicKey,
            )
            .problemAt(
                epochSeconds = epochSeconds,
                hasPublicKey = !publicKey.isNullOrBlank(),
            )
    }
}

private fun SshCertificateProblem.code(): String = when (this) {
    SshCertificateProblem.Malformed -> "malformed"
    SshCertificateProblem.HostCertificate -> "host_certificate"
    SshCertificateProblem.KeyMismatch -> "key_mismatch"
    is SshCertificateProblem.NotYetValid -> "not_yet_valid"
    is SshCertificateProblem.Expired -> "expired"
}
