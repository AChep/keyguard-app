package com.artemchep.keyguard.provider.bitwarden.upload

/**
 * Coordinates the lifecycle of encrypted local files that must survive until a
 * later Bitwarden sync upload.
 *
 * A selected source file is staged immediately, before the local cipher/send
 * model is written. Staging encrypts the source bytes with the attachment/send
 * file key and stores the resulting ciphertext in a deterministic local path
 * derived from [PendingUploadTarget]. The returned [PendingUploadFile] is then
 * embedded into the local model so sync can upload it later.
 *
 * Call [persist] around the database write that stores the model containing the
 * new pending uploads. If the write fails, [persist] removes newly staged files.
 * If the write succeeds, [persist] removes old staged files that are no longer
 * referenced by the saved model.
 *
 * Example:
 * ```
 * val pendingUpload = pendingUploadCoordinator.stage(
 *     target = PendingUploadTarget.CipherAttachment(
 *         accountId = cipher.accountId,
 *         cipherId = cipher.cipherId,
 *         attachmentId = attachment.id,
 *     ),
 *     sourceUri = attachment.uri.toString(),
 *     fileKey = fileKey,
 * )
 *
 * val updatedCipher = cipher.copy(
 *     attachments = cipher.attachments.add(
 *         attachment.copy(pendingUpload = pendingUpload),
 *     ),
 * )
 *
 * pendingUploadCoordinator.persist(
 *     createdPendingUploads = listOf(pendingUpload),
 *     removedPendingUploads = obsoletePendingUploads,
 * ) {
 *     dao.transaction {
 *         dao.insert(data = updatedCipher)
 *     }
 * }
 * ```
 *
 * @see PendingUploadTarget
 * @see PendingUploadFile
 */
interface PendingUploadCoordinator {
    /**
     * Encrypts [sourceUri] into a local pending-upload file for [target].
     *
     * The staged file is ready to upload as ciphertext. The caller remains
     * responsible for saving the returned [PendingUploadFile] into the local
     * cipher/send model and for wrapping that save in [persist].
     *
     * Example:
     * ```
     * val pendingUpload = pendingUploadCoordinator.stage(
     *     target = PendingUploadTarget.SendFile(
     *         accountId = send.accountId,
     *         sendId = send.sendId,
     *     ),
     *     sourceUri = selectedFileUri.toString(),
     *     fileKey = fileKey,
     * )
     * ```
     *
     * @param target Stable local destination for the staged encrypted file.
     * @param sourceUri URI of the plain source file selected by the user. This
     * can be a `content:` URI on Android or a `file:` URI on desktop.
     * @param fileKey Raw attachment/send file key used to encrypt the staged
     * bytes.
     */
    suspend fun stage(
        target: PendingUploadTarget,
        sourceUri: String,
        fileKey: ByteArray,
    ): PendingUploadFile

    /**
     * Deletes a staged upload file and any temporary sibling created during
     * staging.
     *
     * Deletion is best-effort in the default coordinator implementation when it
     * is called from [persist], but direct callers can use this method when they
     * intentionally discard a pending upload outside that flow.
     *
     * Example:
     * ```
     * send.file?.pendingUpload?.let { pendingUpload ->
     *     pendingUploadCoordinator.delete(pendingUpload)
     * }
     * ```
     */
    suspend fun delete(
        pendingUpload: PendingUploadFile,
    )

    suspend fun markUploaded(
        pendingUpload: PendingUploadFile,
    )

    suspend fun isUploaded(
        pendingUpload: PendingUploadFile,
    ): Boolean

    /**
     * Runs [block] as the persistence boundary for staged upload metadata.
     *
     * Use this around the database transaction that stores local models
     * containing [createdPendingUploads] and no longer referencing
     * [removedPendingUploads].
     *
     * Cleanup rules:
     *
     * - If [block] succeeds, [removedPendingUploads] are deleted because the new
     *   local state no longer points at them.
     * - If [block] fails, [createdPendingUploads] are deleted because no durable
     *   local state points at them.
     *
     * Example:
     * ```
     * pendingUploadCoordinator.persist(
     *     createdPendingUploads = prepared.createdPendingUploads,
     *     removedPendingUploads = prepared.removedPendingUploads,
     * ) {
     *     dao.transaction {
     *         dao.insert(data = prepared.cipher)
     *     }
     * }
     * ```
     */
    suspend fun <T> persist(
        createdPendingUploads: Collection<PendingUploadFile>,
        removedPendingUploads: Collection<PendingUploadFile> = emptyList(),
        block: suspend () -> T,
    ): T
}
