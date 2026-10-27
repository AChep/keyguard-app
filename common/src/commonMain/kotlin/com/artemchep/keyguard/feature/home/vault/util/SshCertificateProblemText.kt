package com.artemchep.keyguard.feature.home.vault.util

import com.artemchep.keyguard.common.service.crypto.SshCertificateProblem
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlin.time.Instant

/** Explains why a certificate is not offered with its SSH key. */
internal suspend fun TranslatorScope.formatSshCertificateProblem(
    problem: SshCertificateProblem,
    dateFormatter: DateFormatter,
): String = when (problem) {
    SshCertificateProblem.Malformed ->
        translate(Res.string.ssh_certificate_problem_malformed)

    SshCertificateProblem.HostCertificate ->
        translate(Res.string.ssh_certificate_problem_host_certificate)

    SshCertificateProblem.KeyMismatch ->
        translate(Res.string.ssh_certificate_problem_key_mismatch)

    is SshCertificateProblem.NotYetValid -> translate(
        Res.string.ssh_certificate_problem_not_yet_valid,
        formatSshCertificateTime(problem.validAfter, dateFormatter),
    )

    is SshCertificateProblem.Expired -> translate(
        Res.string.ssh_certificate_problem_expired,
        formatSshCertificateTime(problem.validBefore, dateFormatter),
    )
}

// A local date-time can not represent every Unix second; 9999-12-31 is far enough.
private const val MAX_FORMATTED_EPOCH_SECONDS = 253_402_300_799L

/** Formats the Unix seconds of a certificate's validity window. */
internal fun formatSshCertificateTime(
    epochSeconds: Long,
    dateFormatter: DateFormatter,
): String {
    val seconds = epochSeconds.coerceIn(0L, MAX_FORMATTED_EPOCH_SECONDS)
    return dateFormatter.formatDateTime(Instant.fromEpochSeconds(seconds))
}
