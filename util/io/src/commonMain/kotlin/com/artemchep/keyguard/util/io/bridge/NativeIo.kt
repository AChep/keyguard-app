package com.artemchep.keyguard.util.io.bridge

/**
 * The complete per-platform surface of the native I/O core (ABI v1).
 *
 * Scalar functions return a packed value: a negative value (other than the
 * `-1` end-of-file marker of reads) is a packed protocol failure decoded by
 * [decodeNativeIoFailure]; a non-negative value is the function-specific
 * success payload. Implementations load the native library, verify the ABI
 * version once, and map linkage failures to [FileSystemOperationException]
 * with [FileSystemFailureKind.Internal] — atomic writes fail closed rather
 * than falling back to a weaker protocol. The sweeper returns a versioned
 * long-array report because its counters no longer fit one scalar.
 */
// The expect declaration intentionally presents the complete fixed native ABI as one surface.
@Suppress("TooManyFunctions")
internal expect object NativeIo {
    fun directoryOpen(directory: String): Long

    fun directoryClose(handle: Long): Long

    fun txnBegin(
        destination: String,
        options: NativeIoTxnOptions,
    ): Long

    fun txnBeginAtDirectory(
        directoryHandle: Long,
        relativeDestination: String,
        options: NativeIoTxnOptions,
    ): Long

    /**
     * Appends bytes. A failure permanently poisons this handle against
     * publication; later writes do not replay native I/O.
     */
    fun txnWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    fun txnCommit(handle: Long): Long

    fun txnAbort(handle: Long): Long

    fun scratchOpen(directory: String): Long

    fun scratchWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    fun scratchSeal(handle: Long): Long

    fun scratchLength(handle: Long): Long

    fun scratchReadAt(
        handle: Long,
        position: Long,
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    fun scratchClose(handle: Long): Long

    fun sweepOrphans(
        directory: String,
        olderThanMs: Long,
        roleMask: Int,
    ): LongArray
}

/**
 * JNI/C-neutral representation of `keyguard_io_txn_options_v1`.
 */
internal data class NativeIoTxnOptions(
    val publication: Int,
    val filePermissions: Int,
    val parentCreation: Int,
    val directoryPermissions: Int,
    val existingParentLinks: Int,
    val preferredSyncLevel: Int,
    val minimumSyncLevel: Int,
    val syncPolicyMode: Int,
    val flags: Int = 0,
    val reserved0: Int = 0,
    val reserved1: Int = 0,
    val reserved2: Int = 0,
    val reserved3: Int = 0,
    val reserved4: Int = 0,
) {
    fun toWireFields(): IntArray = intArrayOf(
        TXN_OPTIONS_SIZE_BYTES,
        TXN_OPTIONS_VERSION,
        publication,
        filePermissions,
        parentCreation,
        directoryPermissions,
        existingParentLinks,
        preferredSyncLevel,
        minimumSyncLevel,
        syncPolicyMode,
        flags,
        reserved0,
        reserved1,
        reserved2,
        reserved3,
        reserved4,
    )

    companion object {
        const val TXN_OPTIONS_SIZE_BYTES: Int = 64
        const val TXN_OPTIONS_VERSION: Int = 1
    }
}
