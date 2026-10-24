package com.artemchep.keyguard.util.zxcvbn.bridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the size limits and their precedence against invalid arguments.
 *
 * Desktop and the Android host run the JNI bridge, Apple targets the C one,
 * so the same expectations hold every bridge to identical answers.
 */
class NativeZxcvbnLimitTest {
    @Test
    fun aPasswordIsBoundedByItsUtf8Bytes() {
        boundaries.forEach { (input, expected) ->
            assertEquals(expected, estimateBridgeCode(password = input), input.describe())
        }
    }

    @Test
    fun aUserInputIsBoundedByItsUtf8Bytes() {
        boundaries.forEach { (input, expected) ->
            assertEquals(
                expected,
                estimateBridgeCode(userInputs = listOf(input)),
                input.describe(),
            )
        }
    }

    @Test
    fun aMalformedUserInputBeatsAnOversizedPassword() {
        assertEquals(
            NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT,
            estimateBridgeCode(password = OVERSIZED, userInputs = listOf(LONE_HIGH)),
        )
    }

    @Test
    fun aMalformedPasswordBeatsAnOversizedUserInput() {
        assertEquals(
            NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT,
            estimateBridgeCode(password = LONE_HIGH, userInputs = listOf(OVERSIZED)),
        )
    }

    @Test
    fun aMalformedUserInputBeatsAnEarlierOversizedOne() {
        assertEquals(
            NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT,
            estimateBridgeCode(userInputs = listOf(OVERSIZED, LONE_HIGH)),
        )
    }

    @Test
    fun anOversizedPasswordIsStillValidatedInFull() {
        listOf(
            // Malformed past the first chunk.
            OVERSIZED + LONE_HIGH + "a",
            OVERSIZED + LONE_LOW,
            // A lone high surrogate at the very end.
            OVERSIZED + LONE_HIGH,
        ).forEach { password ->
            assertEquals(
                NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT,
                estimateBridgeCode(password = password),
                password.describe(),
            )
        }
    }

    @Test
    fun aSurrogatePairAcrossAChunkBoundaryIsWellFormed() {
        // Between the two, a pair straddles a chunk boundary for any chunk
        // size.
        listOf(
            EMOJI.repeat(2000),
            "a" + EMOJI.repeat(2000),
        ).forEach { password ->
            assertEquals(
                NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG,
                estimateBridgeCode(password = password),
                password.describe(),
            )
        }
    }

    @Test
    fun tooManyUserInputsAreAnInvalidArgument() {
        assertEquals(
            NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT,
            estimateBridgeCode(userInputs = List(NATIVE_ZXCVBN_MAX_USER_INPUTS + 1) { "a" }),
        )
    }
}

/**
 * Inputs around the shared 256 byte limit, mapped to the bridge code of the
 * expected failure, or `null` when the estimate succeeds.
 */
private val boundaries: List<Pair<String, Int?>> = listOf(
    "a".repeat(MAX_BYTES) to null,
    "a".repeat(MAX_BYTES + 1) to NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG,
    // Two bytes per unit.
    E_ACUTE.repeat(128) to null,
    E_ACUTE.repeat(129) to NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG,
    // Three bytes per unit.
    EURO.repeat(85) to null,
    EURO.repeat(86) to NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG,
    // Four bytes per two units.
    EMOJI.repeat(64) to null,
    EMOJI.repeat(65) to NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG,
    EMOJI.repeat(129) to NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG,
)

/**
 * Estimates and returns the bridge code of the failure, or `null` when the
 * estimate succeeded.
 */
private fun estimateBridgeCode(
    password: String = "password",
    userInputs: List<String> = emptyList(),
): Int? {
    val out = LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT) { SENTINEL }
    val status = NativeZxcvbn.estimate(password, userInputs, out)
    if (!isNativeZxcvbnFailure(status)) {
        assertEquals(0L, status)
        return null
    }
    assertTrue(out.all { it == SENTINEL }, "A failed estimate wrote its output")
    val failure = decodeNativeZxcvbnFailure(status)
    assertEquals(NativeZxcvbnFailureKind.InvalidInput, failure.kind)
    return failure.bridgeCode
}

private fun String.describe(): String = "$length units starting with ${take(8)}"

/** `KEYGUARD_ZXCVBN_MAX_{PASSWORD,USER_INPUT}_BYTES` of the native ABI. */
private const val MAX_BYTES = 256

/** Far over the limit, so the JNI bridge scans it in several chunks. */
private val OVERSIZED = "a".repeat(10_000)

private const val E_ACUTE = "é"
private const val EURO = "€"
private const val EMOJI = "😀"
private const val LONE_HIGH = "\uD800"
private const val LONE_LOW = "\uDC00"

private const val SENTINEL = 0x5A5A5A5A5A5A5A5AL
