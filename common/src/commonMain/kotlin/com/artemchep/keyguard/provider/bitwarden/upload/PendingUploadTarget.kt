package com.artemchep.keyguard.provider.bitwarden.upload

/**
 * Stable location descriptor for a file that is staged locally before it is
 * uploaded to Bitwarden server.
 *
 * A target is not the remote upload URL. It only describes where Keyguard keeps
 * the encrypted pending file on local storage. [namespace] separates unrelated
 * upload domains under the same account, and [fileId] is the deterministic file
 * name inside that namespace. Reusing the same target replaces the same staged
 * file.
 *
 * Example:
 * ```
 * val target = PendingUploadTarget.CipherAttachment(
 *     accountId = "account-1",
 *     cipherId = "cipher-1",
 *     attachmentId = "attachment-1",
 * )
 *
 * // Staged under:
 * // account-1 / cipher_attachment_uploads / cipher-1.attachment-1.bin
 * pendingUploadCoordinator.stage(
 *     target = target,
 *     sourceUri = "content://documents/document/42",
 *     fileKey = attachmentKey,
 * )
 * ```
 *
 * @see PendingUploadCoordinator.stage
 */
sealed interface PendingUploadTarget {
    /**
     * Account that owns the staged file. Pending uploads are scoped per account
     * so files from different vault accounts never share a staging directory.
     */
    val accountId: String

    /**
     * Upload-domain folder name under [accountId], for example
     * `"cipher_attachment_uploads"` or `"send_uploads"`.
     */
    val namespace: String

    /**
     * Deterministic file stem inside [namespace]. The platform staging service
     * appends its own file extension to this value.
     */
    val fileId: String

    /**
     * Pending upload for a file attached to an existing or newly saved cipher.
     *
     * The resulting [fileId] combines [cipherId] and [attachmentId], making it
     * stable for retries of the same local attachment mutation.
     *
     * Example:
     * ```
     * val target = PendingUploadTarget.CipherAttachment(
     *     accountId = cipher.accountId,
     *     cipherId = cipher.cipherId,
     *     attachmentId = attachment.id,
     * )
     * ```
     */
    data class CipherAttachment(
        override val accountId: String,
        val cipherId: String,
        val attachmentId: String,
    ) : PendingUploadTarget {
        override val namespace: String = "cipher_attachment_uploads"
        override val fileId: String = "$cipherId.$attachmentId"
    }

    /**
     * Pending upload for a Bitwarden Send file.
     *
     * A send has at most one file payload, so [sendId] is sufficient as the
     * stable [fileId].
     *
     * Example:
     * ```
     * val target = PendingUploadTarget.SendFile(
     *     accountId = send.accountId,
     *     sendId = send.sendId,
     * )
     * ```
     */
    data class SendFile(
        override val accountId: String,
        val sendId: String,
    ) : PendingUploadTarget {
        override val namespace: String = "send_uploads"
        override val fileId: String = sendId
    }
}
