package com.artemchep.keyguard.provider.bitwarden.upload

/**
 * Uses caller-owned key material for one operation and clears the mutable
 * buffer when that operation completes, fails, or is cancelled.
 *
 * Inlining lets [block] suspend when the caller does, without forcing
 * non-suspending users to wrap their work in a suspending lambda.
 */
internal inline fun <T> ByteArray.useAndClear(
    block: (ByteArray) -> T,
): T = try {
    block(this)
} finally {
    fill(0)
}

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
