package com.artemchep.keyguard.common.model

/**
 * The result of checking a key against a public keyserver. [overall] is the
 * aggregate status; [perEmail] carries the per-identity status, which is the
 * meaningful granularity for keys.openpgp.org (each e-mail is verified
 * independently).
 */
data class GpgKeyserverVerifyStatus(
    val fingerprint: String,
    val overall: GpgKeyserverVerificationStatus,
    val perEmail: Map<String, GpgKeyserverVerificationStatus> = emptyMap(),
)
