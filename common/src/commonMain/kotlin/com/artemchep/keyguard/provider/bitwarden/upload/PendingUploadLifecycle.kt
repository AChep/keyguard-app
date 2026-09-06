package com.artemchep.keyguard.provider.bitwarden.upload

/**
 * Deletes each distinct staged upload without allowing cleanup failures to
 * replace the result of the primary mutation or sync operation.
 */
internal suspend fun PendingUploadCoordinator.deleteBestEffort(
    pendingUploads: Iterable<PendingUploadFile>,
) {
    pendingUploads
        .distinctBy { pendingUpload -> pendingUpload.path }
        .forEach { pendingUpload ->
            runCatching {
                delete(pendingUpload)
            }
        }
}

/**
 * Deletes the staged uploads that [previous] referenced and [saved] no longer
 * does, after a successful local save.
 */
internal suspend fun PendingUploadCoordinator.deleteObsoletePendingUploads(
    previous: Set<PendingUploadFile>,
    saved: Set<PendingUploadFile>,
) {
    if (previous.isEmpty()) return
    val savedPaths = saved.mapTo(mutableSetOf()) { pendingUpload -> pendingUpload.path }
    val obsoletePendingUploads = previous
        .filterNot { pendingUpload -> pendingUpload.path in savedPaths }
    if (obsoletePendingUploads.isEmpty()) return

    deleteBestEffort(obsoletePendingUploads)
}
