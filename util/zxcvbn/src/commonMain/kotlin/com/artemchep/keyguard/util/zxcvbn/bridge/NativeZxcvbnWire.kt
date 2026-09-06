package com.artemchep.keyguard.util.zxcvbn.bridge

import com.artemchep.keyguard.util.zxcvbn.ZxcvbnCrackTimes
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnException
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnResult
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnSuggestion
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnWarning

/** The native ABI version both bridges hand-shake against. */
internal const val NATIVE_ZXCVBN_ABI_VERSION: Int = 1

/** Number of slots of the caller-allocated result array. */
internal const val NATIVE_ZXCVBN_JNI_FIELD_COUNT: Int = 11

// Indexes into the caller-allocated result array. The layout mirrors
// `keyguard_zxcvbn_result_v1`, with the doubles carried as raw bit patterns
// so one primitive array can transport the whole result without boxing.
internal const val NATIVE_ZXCVBN_FIELD_SIZE: Int = 0
internal const val NATIVE_ZXCVBN_FIELD_VERSION: Int = 1
internal const val NATIVE_ZXCVBN_FIELD_SCORE: Int = 2
internal const val NATIVE_ZXCVBN_FIELD_WARNING: Int = 3
internal const val NATIVE_ZXCVBN_FIELD_SUGGESTIONS: Int = 4
internal const val NATIVE_ZXCVBN_FIELD_GUESSES: Int = 5
internal const val NATIVE_ZXCVBN_FIELD_GUESSES_LOG10: Int = 6
internal const val NATIVE_ZXCVBN_FIELD_ONLINE_THROTTLING_100_PER_HOUR: Int = 7
internal const val NATIVE_ZXCVBN_FIELD_ONLINE_NO_THROTTLING_10_PER_SECOND: Int = 8
internal const val NATIVE_ZXCVBN_FIELD_OFFLINE_SLOW_HASHING_1E4_PER_SECOND: Int = 9
internal const val NATIVE_ZXCVBN_FIELD_OFFLINE_FAST_HASHING_1E10_PER_SECOND: Int = 10

/** Byte size of `keyguard_zxcvbn_result_v1`; the C handshake rejects less. */
internal const val NATIVE_ZXCVBN_RESULT_SIZE_BYTES: Long = 88L

/** Wire value of `keyguard_zxcvbn_result_v1.version`. */
internal const val NATIVE_ZXCVBN_RESULT_VERSION: Long = 1L

/** Highest score upstream can report; scores are `0..4`. */
internal const val NATIVE_ZXCVBN_MAX_SCORE: Int = 4

/** `keyguard_zxcvbn_warning` value meaning "no warning". */
internal const val NATIVE_ZXCVBN_WARNING_NONE: Int = -1

/** Largest number of user inputs the ABI accepts in one call. */
internal const val NATIVE_ZXCVBN_MAX_USER_INPUTS: Int = 64

// Bridge failure codes of `keyguard-zxcvbn-core`.
internal const val NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT: Int = 1
internal const val NATIVE_ZXCVBN_BRIDGE_CODE_PANIC: Int = 2
internal const val NATIVE_ZXCVBN_BRIDGE_CODE_INTERNAL: Int = 3
internal const val NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG: Int = 4

// Failure layout (bit 63 set): bits 0..7 operation, 8..15 failure kind,
// 16..23 error domain, 24..55 bridge code, and 56..62 reserved zero. Only the
// bridge operation and the bridge error domain occur, because the estimator
// performs no I/O; the layout is nevertheless the one of `util/io` so a shared
// inspector can decode either module's scalars.
private const val FAILURE_KIND_SHIFT: Int = 8
private const val ERROR_DOMAIN_SHIFT: Int = 16
private const val BRIDGE_CODE_SHIFT: Int = 24
private const val RESERVED_SHIFT: Int = 56
private const val RESERVED_MASK: Long = 0x7fL
private const val BYTE_MASK: Long = 0xffL
private const val UINT32_MASK: Long = 0xffffffffL

private const val OPERATION_BRIDGE: Int = 0
private const val DOMAIN_BRIDGE: Int = 3
private const val KIND_INVALID_INPUT: Int = 8
private const val KIND_INTERNAL: Int = 12

/** Set of every defined suggestion bit; anything else is an ABI break. */
private val KNOWN_SUGGESTION_BITS: Int = ZxcvbnSuggestion.entries
    .fold(0) { mask, suggestion -> mask or suggestion.wireBit }

/**
 * Coarse classification of a packed bridge failure.
 *
 * [InvalidInput] covers arguments the estimator refuses (malformed UTF-8, an
 * over-long password, too many user inputs); [Internal] covers a contained
 * panic or an unexpected internal state. [Other] exists so an unknown future
 * kind decodes instead of throwing.
 */
internal enum class NativeZxcvbnFailureKind {
    InvalidInput,
    Internal,
    Other,
}

/** A decoded packed failure scalar. */
internal data class NativeZxcvbnFailure(
    val kind: NativeZxcvbnFailureKind,
    val bridgeCode: Int,
)

/**
 * The packed `Bridge / InvalidInput / Bridge domain / code 1` scalar.
 *
 * A bridge that rejects an argument before dispatching to the native ABI
 * returns this instead of throwing, so both bridges report an identical
 * scalar for identical input and the ordinary decode path produces the
 * platform-independent exception.
 */
internal const val NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT: Long =
    (1L shl 63) or
        (NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT.toLong() shl BRIDGE_CODE_SHIFT) or
        (DOMAIN_BRIDGE.toLong() shl ERROR_DOMAIN_SHIFT) or
        (KIND_INVALID_INPUT.toLong() shl FAILURE_KIND_SHIFT) or
        OPERATION_BRIDGE.toLong()

/** Whether [packedResult] carries a failure rather than a success status. */
internal fun isNativeZxcvbnFailure(packedResult: Long): Boolean = packedResult < 0L

/**
 * Decodes a packed bridge failure.
 *
 * @throws ZxcvbnException when the scalar violates the failure layout.
 */
internal fun decodeNativeZxcvbnFailure(packedResult: Long): NativeZxcvbnFailure {
    requireNativeZxcvbnWire(packedResult < 0L) {
        "Native zxcvbn result does not contain a failure"
    }
    requireNativeZxcvbnWire(((packedResult ushr RESERVED_SHIFT) and RESERVED_MASK) == 0L) {
        "Native zxcvbn returned non-zero reserved failure bits"
    }
    val operation = packedResult.byteAt(shift = 0)
    requireNativeZxcvbnWire(operation == OPERATION_BRIDGE) {
        "Native zxcvbn returned unknown failure operation $operation"
    }
    val domain = packedResult.byteAt(shift = ERROR_DOMAIN_SHIFT)
    requireNativeZxcvbnWire(domain == DOMAIN_BRIDGE) {
        "Native zxcvbn returned unknown error domain $domain"
    }
    val kind = when (packedResult.byteAt(shift = FAILURE_KIND_SHIFT)) {
        KIND_INVALID_INPUT -> NativeZxcvbnFailureKind.InvalidInput
        KIND_INTERNAL -> NativeZxcvbnFailureKind.Internal
        else -> NativeZxcvbnFailureKind.Other
    }
    return NativeZxcvbnFailure(
        kind = kind,
        bridgeCode = ((packedResult ushr BRIDGE_CODE_SHIFT) and UINT32_MASK).toInt(),
    )
}

/**
 * Decodes the caller-allocated result array a successful estimate filled in.
 *
 * Every field is validated, because a mismatched native library would
 * otherwise surface as a silently wrong password strength instead of an
 * error.
 *
 * @throws ZxcvbnException when the array violates the result layout.
 */
internal fun decodeNativeZxcvbnResult(fields: LongArray): ZxcvbnResult {
    requireNativeZxcvbnWire(fields.size == NATIVE_ZXCVBN_JNI_FIELD_COUNT) {
        "Native zxcvbn returned ${fields.size} fields; " +
            "expected $NATIVE_ZXCVBN_JNI_FIELD_COUNT"
    }
    val size = fields[NATIVE_ZXCVBN_FIELD_SIZE]
    requireNativeZxcvbnWire(size == NATIVE_ZXCVBN_RESULT_SIZE_BYTES) {
        "Native zxcvbn returned a $size byte result; " +
            "expected $NATIVE_ZXCVBN_RESULT_SIZE_BYTES"
    }
    val version = fields[NATIVE_ZXCVBN_FIELD_VERSION]
    requireNativeZxcvbnWire(version == NATIVE_ZXCVBN_RESULT_VERSION) {
        "Unsupported native zxcvbn result version $version; " +
            "expected $NATIVE_ZXCVBN_RESULT_VERSION"
    }
    val score = fields[NATIVE_ZXCVBN_FIELD_SCORE]
    requireNativeZxcvbnWire(score >= 0L && score <= NATIVE_ZXCVBN_MAX_SCORE.toLong()) {
        "Native zxcvbn returned an out of range score $score"
    }
    val guesses = fields[NATIVE_ZXCVBN_FIELD_GUESSES]
    requireNativeZxcvbnWire(guesses >= 0L) {
        "Native zxcvbn returned a negative guess count $guesses"
    }
    return ZxcvbnResult(
        score = score.toInt(),
        guesses = guesses,
        guessesLog10 = fields.doubleAt(NATIVE_ZXCVBN_FIELD_GUESSES_LOG10),
        crackTimes = ZxcvbnCrackTimes(
            onlineThrottling100PerHour = fields.doubleAt(
                NATIVE_ZXCVBN_FIELD_ONLINE_THROTTLING_100_PER_HOUR,
            ),
            onlineNoThrottling10PerSecond = fields.doubleAt(
                NATIVE_ZXCVBN_FIELD_ONLINE_NO_THROTTLING_10_PER_SECOND,
            ),
            offlineSlowHashing1e4PerSecond = fields.doubleAt(
                NATIVE_ZXCVBN_FIELD_OFFLINE_SLOW_HASHING_1E4_PER_SECOND,
            ),
            offlineFastHashing1e10PerSecond = fields.doubleAt(
                NATIVE_ZXCVBN_FIELD_OFFLINE_FAST_HASHING_1E10_PER_SECOND,
            ),
        ),
        warning = decodeNativeZxcvbnWarning(fields[NATIVE_ZXCVBN_FIELD_WARNING]),
        suggestions = decodeNativeZxcvbnSuggestions(fields[NATIVE_ZXCVBN_FIELD_SUGGESTIONS]),
    )
}

/**
 * Throws when the native side violated the wire layout.
 *
 * The checks read as guard clauses but each one has to raise the module's own
 * exception type, so they funnel through one helper instead of a `throw` per
 * check.
 */
private inline fun requireNativeZxcvbnWire(value: Boolean, message: () -> String) {
    if (!value) {
        throw ZxcvbnException(message())
    }
}

private fun decodeNativeZxcvbnWarning(wireCode: Long): ZxcvbnWarning? {
    if (wireCode == NATIVE_ZXCVBN_WARNING_NONE.toLong()) return null
    return ZxcvbnWarning.entries.firstOrNull { warning ->
        warning.wireCode.toLong() == wireCode
    } ?: throw ZxcvbnException("Native zxcvbn returned unknown warning code $wireCode")
}

private fun decodeNativeZxcvbnSuggestions(wireMask: Long): Set<ZxcvbnSuggestion> {
    // The mask is carried in an unsigned 32 bit field, so a set high bit must
    // not be read as a negative Long.
    requireNativeZxcvbnWire(wireMask >= 0L && wireMask <= UINT32_MASK) {
        "Native zxcvbn returned an out of range suggestion mask $wireMask"
    }
    val mask = wireMask.toInt()
    requireNativeZxcvbnWire((mask and KNOWN_SUGGESTION_BITS.inv()) == 0) {
        "Native zxcvbn returned unknown suggestion bits in mask $wireMask"
    }
    return ZxcvbnSuggestion.entries
        .filterTo(mutableSetOf()) { suggestion -> (mask and suggestion.wireBit) != 0 }
}

private fun LongArray.doubleAt(index: Int): Double = Double.fromBits(this[index])

private fun Long.byteAt(shift: Int): Int = ((this ushr shift) and BYTE_MASK).toInt()
