package com.artemchep.keyguard.common.model

/**
 * Outcome of publishing a public key. Only VKS reports anything,
 * an HKP upload yields an empty result.
 */
data class DGpgKeyserverUploadResult(
    /** Normalized fingerprint confirmed by the keyserver, `null` on HKP. */
    val fingerprint: String? = null,
    /** Keyed by the address as the keyserver reports it. */
    val emailStatus: Map<String, EmailStatus> = emptyMap(),
    val token: String? = null,
) {
    enum class EmailStatus {
        /** Stored, not searchable until verified. */
        UNPUBLISHED,

        /** Verification e-mail sent, awaiting confirmation. */
        PENDING,

        /** Verified and searchable. */
        PUBLISHED,

        /** Previously published, since revoked or removed. */
        REVOKED,
    }

    /**
     * Addresses a verification request may act on. Pending ones are
     * included so a lost e-mail can be re-sent.
     */
    val verifiableEmails: Set<String>
        get() = emailsWithStatus(EmailStatus.UNPUBLISHED, EmailStatus.PENDING)

    /** Addresses that are verified and searchable. */
    val publishedEmails: Set<String>
        get() = emailsWithStatus(EmailStatus.PUBLISHED)

    private fun emailsWithStatus(
        vararg statuses: EmailStatus,
    ): Set<String> = emailStatus
        .filterValues { it in statuses }
        .keys
}
