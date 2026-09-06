package com.artemchep.keyguard.util.zxcvbn.bridge

/**
 * The complete per-platform surface of the native zxcvbn core (ABI v1).
 *
 * [estimate] returns a packed scalar: `0` means the caller-allocated array was
 * filled in, and a negative value is a packed bridge failure decoded by
 * [decodeNativeZxcvbnFailure]. Implementations load the native library, verify
 * the ABI version once, and map linkage failures to
 * [com.artemchep.keyguard.util.zxcvbn.ZxcvbnException] — an estimate fails
 * closed rather than falling back to a weaker heuristic.
 *
 * A bridge that rejects an argument before dispatching to the native ABI
 * returns [NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT] rather than throwing, so
 * every platform answers identically for identical input.
 */
internal expect object NativeZxcvbn {
    /**
     * Estimates the strength of [password] against [userInputs] and writes
     * the result into [out], which must hold exactly
     * [NATIVE_ZXCVBN_JNI_FIELD_COUNT] slots.
     */
    fun estimate(
        password: String,
        userInputs: List<String>,
        out: LongArray,
    ): Long
}
