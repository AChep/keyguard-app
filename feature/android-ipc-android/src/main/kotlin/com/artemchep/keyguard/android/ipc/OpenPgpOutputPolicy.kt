package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpOperationKind

internal enum class OpenPgpOutputPolicy {
    /** The operation exists to produce stream output; a valid output pipe is mandatory. */
    REQUIRED,

    /**
     * Output is written to the pipe if the caller provided one, otherwise
     * silently discarded. Used when a caller may run the operation only for
     * its side results, e.g. decrypting just to verify a signature.
     */
    OPTIONAL,

    /**
     * The operation streams through the pipeline, but the produced bytes are
     * always thrown away; only the result intent extras matter.
     */
    DISCARD,

    /** The operation produces no stream output; requesting a sink is a programming error. */
    NONE,
}

/**
 * Returns the [OpenPgpOutputPolicy] describing how the given operation
 * [kind] uses the caller-supplied output stream.
 */
internal fun openPgpOutputPolicy(
    kind: GpgOpenPgpOperationKind,
): OpenPgpOutputPolicy = when (kind) {
    GpgOpenPgpOperationKind.GET_KEY,
    GpgOpenPgpOperationKind.CLEAR_SIGN,
    GpgOpenPgpOperationKind.ENCRYPT,
    GpgOpenPgpOperationKind.SIGN_AND_ENCRYPT,
    -> OpenPgpOutputPolicy.REQUIRED

    GpgOpenPgpOperationKind.DECRYPT_VERIFY -> OpenPgpOutputPolicy.OPTIONAL
    GpgOpenPgpOperationKind.DECRYPT_METADATA -> OpenPgpOutputPolicy.DISCARD

    GpgOpenPgpOperationKind.CHECK_PERMISSION,
    GpgOpenPgpOperationKind.GET_SIGN_KEY_ID,
    GpgOpenPgpOperationKind.GET_KEY_IDS,
    GpgOpenPgpOperationKind.DETACHED_SIGN,
    GpgOpenPgpOperationKind.AUTOCRYPT_STATUS,
    -> OpenPgpOutputPolicy.NONE
}
