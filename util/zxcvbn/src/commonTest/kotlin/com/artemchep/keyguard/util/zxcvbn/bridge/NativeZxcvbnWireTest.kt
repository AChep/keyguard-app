package com.artemchep.keyguard.util.zxcvbn.bridge

import com.artemchep.keyguard.util.zxcvbn.ZxcvbnException
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnSuggestion
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnWarning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden wire vectors mirrored byte-identically by the Rust
 * `keyguard-zxcvbn-core` test module; changing any value is an ABI break.
 */
private object GoldenVectors {
    /** `Bridge / InvalidInput / Bridge domain / code 1`. */
    val BRIDGE_INVALID_ARGUMENT: Long = "8000000001030800".toULong(16).toLong()

    /** `Bridge / Internal / Bridge domain / code 2`. */
    val BRIDGE_PANIC: Long = "8000000002030C00".toULong(16).toLong()

    /** `Bridge / Internal / Bridge domain / code 3`. */
    val BRIDGE_INTERNAL: Long = "8000000003030C00".toULong(16).toLong()

    /** `Bridge / InvalidInput / Bridge domain / code 4`. */
    val BRIDGE_INPUT_TOO_LONG: Long = "8000000004030800".toULong(16).toLong()
}

class NativeZxcvbnWireTest {
    @Test
    fun nativeAbiVersionIsOne() {
        assertEquals(1, NATIVE_ZXCVBN_ABI_VERSION)
    }

    @Test
    fun bridgeInvalidArgumentConstantMatchesTheGoldenVector() {
        // A bridge that refuses an argument before dispatch must emit the
        // ABI's own scalar, not a lookalike; pin the constant to the vector.
        assertEquals(
            GoldenVectors.BRIDGE_INVALID_ARGUMENT,
            NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT,
        )
    }

    @Test
    fun bridgeFailureVectorsDecode() {
        assertTrue(isNativeZxcvbnFailure(GoldenVectors.BRIDGE_INVALID_ARGUMENT))
        assertEquals(
            NativeZxcvbnFailure(
                kind = NativeZxcvbnFailureKind.InvalidInput,
                bridgeCode = NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT,
            ),
            decodeNativeZxcvbnFailure(GoldenVectors.BRIDGE_INVALID_ARGUMENT),
        )
        assertEquals(
            NativeZxcvbnFailure(
                kind = NativeZxcvbnFailureKind.Internal,
                bridgeCode = NATIVE_ZXCVBN_BRIDGE_CODE_PANIC,
            ),
            decodeNativeZxcvbnFailure(GoldenVectors.BRIDGE_PANIC),
        )
        assertEquals(
            NativeZxcvbnFailure(
                kind = NativeZxcvbnFailureKind.Internal,
                bridgeCode = NATIVE_ZXCVBN_BRIDGE_CODE_INTERNAL,
            ),
            decodeNativeZxcvbnFailure(GoldenVectors.BRIDGE_INTERNAL),
        )
        assertEquals(
            NativeZxcvbnFailure(
                kind = NativeZxcvbnFailureKind.InvalidInput,
                bridgeCode = NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG,
            ),
            decodeNativeZxcvbnFailure(GoldenVectors.BRIDGE_INPUT_TOO_LONG),
        )
    }

    @Test
    fun successStatusIsNotAFailure() {
        assertTrue(!isNativeZxcvbnFailure(0L))
    }

    @Test
    fun failureDecoderRejectsLayoutViolations() {
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnFailure(0L)
        }
        // A reserved bit set: bits 56..62 must be zero.
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnFailure(
                GoldenVectors.BRIDGE_INVALID_ARGUMENT or (1L shl 56),
            )
        }
        // The estimator has no operation other than the bridge itself.
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnFailure(GoldenVectors.BRIDGE_INVALID_ARGUMENT or 1L)
        }
        // Only the bridge error domain occurs.
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnFailure(
                (GoldenVectors.BRIDGE_INVALID_ARGUMENT xor (3L shl 16)) or (1L shl 16),
            )
        }
    }

    @Test
    fun warningWireCodesAreTheDeclarationIndexes() {
        assertEquals(14, ZxcvbnWarning.entries.size)
        ZxcvbnWarning.entries.forEach { warning ->
            assertEquals(warning.ordinal, warning.wireCode)
        }
        assertEquals(0, ZxcvbnWarning.StraightRowsOfKeysAreEasyToGuess.wireCode)
        assertEquals(4, ZxcvbnWarning.ThisIsATop10Password.wireCode)
        assertEquals(13, ZxcvbnWarning.CommonNamesAndSurnamesAreEasyToGuess.wireCode)
    }

    @Test
    fun suggestionWireBitsAreDistinctPowersOfTwo() {
        assertEquals(13, ZxcvbnSuggestion.entries.size)
        ZxcvbnSuggestion.entries.forEach { suggestion ->
            assertEquals(1 shl suggestion.ordinal, suggestion.wireBit)
        }
        val mask = ZxcvbnSuggestion.entries.fold(0) { acc, suggestion ->
            acc or suggestion.wireBit
        }
        assertEquals(0x1FFF, mask)
    }

    @Test
    fun resultDecoderRoundTripsAHandBuiltArray() {
        val fields = resultFields(
            score = 3,
            warning = ZxcvbnWarning.DatesAreOftenEasyToGuess.wireCode.toLong(),
            suggestions = (
                ZxcvbnSuggestion.AddAnotherWordOrTwo.wireBit or
                    ZxcvbnSuggestion.AvoidRecentYears.wireBit
                ).toLong(),
            guesses = 123_456_789L,
        )

        val result = decodeNativeZxcvbnResult(fields)

        assertEquals(3, result.score)
        assertEquals(123_456_789L, result.guesses)
        assertEquals(8.0915, result.guessesLog10, absoluteTolerance = 1e-9)
        assertEquals(ZxcvbnWarning.DatesAreOftenEasyToGuess, result.warning)
        assertEquals(
            setOf(
                ZxcvbnSuggestion.AddAnotherWordOrTwo,
                ZxcvbnSuggestion.AvoidRecentYears,
            ),
            result.suggestions,
        )
        assertEquals(1.0, result.crackTimes.onlineThrottling100PerHour)
        assertEquals(2.0, result.crackTimes.onlineNoThrottling10PerSecond)
        assertEquals(3.0, result.crackTimes.offlineSlowHashing1e4PerSecond)
        assertEquals(4.0, result.crackTimes.offlineFastHashing1e10PerSecond)
    }

    @Test
    fun resultDecoderAcceptsTheAbsenceOfAWarning() {
        val fields = resultFields(warning = NATIVE_ZXCVBN_WARNING_NONE.toLong())

        assertNull(decodeNativeZxcvbnResult(fields).warning)
        assertEquals(
            emptySet<ZxcvbnSuggestion>(),
            decodeNativeZxcvbnResult(fields).suggestions,
        )
    }

    @Test
    fun resultDecoderRejectsAWrongFieldCount() {
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT - 1))
        }
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT + 1))
        }
    }

    @Test
    fun resultDecoderRejectsAWrongStructSize() {
        val fields = resultFields()
        fields[NATIVE_ZXCVBN_FIELD_SIZE] = NATIVE_ZXCVBN_RESULT_SIZE_BYTES - 1L

        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(fields)
        }
    }

    @Test
    fun resultDecoderRejectsAWrongVersion() {
        val fields = resultFields()
        fields[NATIVE_ZXCVBN_FIELD_VERSION] = NATIVE_ZXCVBN_RESULT_VERSION + 1L

        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(fields)
        }
    }

    @Test
    fun resultDecoderRejectsAnOutOfRangeScore() {
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(resultFields(score = -1L))
        }
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(
                resultFields(score = NATIVE_ZXCVBN_MAX_SCORE + 1L),
            )
        }
    }

    @Test
    fun resultDecoderRejectsAnOutOfRangeWarning() {
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(resultFields(warning = -2L))
        }
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(
                resultFields(warning = ZxcvbnWarning.entries.size.toLong()),
            )
        }
    }

    @Test
    fun resultDecoderRejectsUnknownSuggestionBits() {
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(
                resultFields(suggestions = 1L shl ZxcvbnSuggestion.entries.size),
            )
        }
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(resultFields(suggestions = 0xFFFFFFFFL))
        }
    }

    @Test
    fun resultDecoderRejectsANegativeGuessCount() {
        assertFailsWith<ZxcvbnException> {
            decodeNativeZxcvbnResult(resultFields(guesses = -1L))
        }
    }
}

private fun resultFields(
    score: Long = 0L,
    warning: Long = NATIVE_ZXCVBN_WARNING_NONE.toLong(),
    suggestions: Long = 0L,
    guesses: Long = 1L,
): LongArray {
    val fields = LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT)
    fields[NATIVE_ZXCVBN_FIELD_SIZE] = NATIVE_ZXCVBN_RESULT_SIZE_BYTES
    fields[NATIVE_ZXCVBN_FIELD_VERSION] = NATIVE_ZXCVBN_RESULT_VERSION
    fields[NATIVE_ZXCVBN_FIELD_SCORE] = score
    fields[NATIVE_ZXCVBN_FIELD_WARNING] = warning
    fields[NATIVE_ZXCVBN_FIELD_SUGGESTIONS] = suggestions
    fields[NATIVE_ZXCVBN_FIELD_GUESSES] = guesses
    fields[NATIVE_ZXCVBN_FIELD_GUESSES_LOG10] = 8.0915.toRawBits()
    fields[NATIVE_ZXCVBN_FIELD_ONLINE_THROTTLING_100_PER_HOUR] = 1.0.toRawBits()
    fields[NATIVE_ZXCVBN_FIELD_ONLINE_NO_THROTTLING_10_PER_SECOND] = 2.0.toRawBits()
    fields[NATIVE_ZXCVBN_FIELD_OFFLINE_SLOW_HASHING_1E4_PER_SECOND] = 3.0.toRawBits()
    fields[NATIVE_ZXCVBN_FIELD_OFFLINE_FAST_HASHING_1E10_PER_SECOND] = 4.0.toRawBits()
    return fields
}
