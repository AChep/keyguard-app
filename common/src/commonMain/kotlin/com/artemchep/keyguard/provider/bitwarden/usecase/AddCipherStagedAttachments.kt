package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.upload.deleteBestEffort
import com.artemchep.keyguard.util.io.toFileUriString
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal fun CreateRequest.Attachment.Staged.toBitwardenLocalAttachment(): BitwardenCipher.Attachment.Local =
    BitwardenCipher.Attachment.Local(
        id = id,
        // Never read, as the file is staged already; the edit
        // screen only needs a URI it can parse.
        url = LocalPath(file.path).toFileUriString(),
        fileName = name,
        size = file.plainSize,
        keyBase64 = keyBase64,
        pendingUpload = file,
    )

/**
 * The pending uploads a save owns until it hands them to
 * [PendingUploadCoordinator.persist], which deletes them itself if the write
 * fails: the ones the caller staged, and the ones the save staged for the
 * ciphers it built.
 */
internal class UnsavedPendingUploads(
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    files: Collection<PendingUploadFile>,
) {
    private val files = files.toMutableList()

    private var handedOver = false

    operator fun plusAssign(files: Collection<PendingUploadFile>) {
        this.files += files
    }

    fun handOver() {
        handedOver = true
    }

    /**
     * Runs [block], deleting the files if it fails before they are handed
     * over: also when it is cancelled, as nothing saved points at them.
     */
    suspend fun <T> discardOnFailure(
        block: suspend () -> T,
    ): T {
        var success = false
        try {
            return block().also { success = true }
        } finally {
            if (!success && !handedOver) {
                withContext(NonCancellable) {
                    pendingUploadCoordinator.deleteBestEffort(files)
                }
            }
        }
    }
}
