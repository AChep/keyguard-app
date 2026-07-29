package com.artemchep.keyguard.util.io

// JNI export names in keyguard-io-jni are pinned to this package. Moving this
// object compiles but fails at runtime with UnsatisfiedLinkError.
// Keeping the complete fixed ABI together makes JNI name parity auditable.
@Suppress("TooManyFunctions")
internal object NativeIoJni {
    external fun abiVersion(): Int

    external fun directoryOpen(directory: String): Long

    external fun directoryClose(handle: Long): Long

    external fun txnBegin(
        destination: String,
        options: IntArray,
    ): Long

    external fun txnBeginAtDirectory(
        directoryHandle: Long,
        relativeDestination: String,
        options: IntArray,
    ): Long

    external fun txnWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    external fun txnCommit(handle: Long): Long

    external fun txnAbort(handle: Long): Long

    external fun scratchOpen(directory: String): Long

    external fun scratchWrite(
        handle: Long,
        input: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    external fun scratchSeal(handle: Long): Long

    external fun scratchLength(handle: Long): Long

    external fun scratchReadAt(
        handle: Long,
        position: Long,
        output: ByteArray,
        offset: Int,
        length: Int,
    ): Long

    external fun scratchClose(handle: Long): Long

    external fun sweepOrphans(
        directory: String,
        olderThanMs: Long,
        roleMask: Int,
    ): LongArray
}
