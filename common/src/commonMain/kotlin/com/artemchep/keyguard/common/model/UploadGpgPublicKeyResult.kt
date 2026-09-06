package com.artemchep.keyguard.common.model

data class UploadGpgPublicKeyResult(
    /** Addresses the keyserver was asked to send a verification e-mail to. */
    val verificationRequestedEmails: Set<String> = emptySet(),
    /** Requested addresses that were skipped because they are already published. */
    val alreadyPublishedEmails: Set<String> = emptySet(),
)
