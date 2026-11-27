package com.artemchep.keyguard.util.io

/**
 * A cheap snapshot of a file's identity and metadata, used to tell whether
 * the file changed since it was last read.
 *
 * An atomic replace publishes a new file, so its [identity] changes even if
 * the replacement has the same size and modification time. A changed
 * revision means the file likely changed; an equal one means it very
 * likely did not.
 *
 * @property identity the device and inode of the file, or `null` where the
 *   platform does not expose them.
 */
data class FileRevision(
    val modifiedNanos: Long,
    val size: Long,
    val identity: String?,
)

/**
 * Returns the current [FileRevision] of the file, or `null` if it is
 * missing. Other failures are propagated.
 */
expect fun LocalPath.fileRevisionOrNull(): FileRevision?
