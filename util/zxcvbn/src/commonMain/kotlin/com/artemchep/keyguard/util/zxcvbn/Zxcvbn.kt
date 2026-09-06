package com.artemchep.keyguard.util.zxcvbn

import com.artemchep.keyguard.util.zxcvbn.bridge.NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG
import com.artemchep.keyguard.util.zxcvbn.bridge.NATIVE_ZXCVBN_BRIDGE_CODE_INTERNAL
import com.artemchep.keyguard.util.zxcvbn.bridge.NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT
import com.artemchep.keyguard.util.zxcvbn.bridge.NATIVE_ZXCVBN_BRIDGE_CODE_PANIC
import com.artemchep.keyguard.util.zxcvbn.bridge.NATIVE_ZXCVBN_JNI_FIELD_COUNT
import com.artemchep.keyguard.util.zxcvbn.bridge.NativeZxcvbn
import com.artemchep.keyguard.util.zxcvbn.bridge.decodeNativeZxcvbnFailure
import com.artemchep.keyguard.util.zxcvbn.bridge.decodeNativeZxcvbnResult
import com.artemchep.keyguard.util.zxcvbn.bridge.isNativeZxcvbnFailure

/**
 * The zxcvbn password strength estimator, backed by the `zxcvbn` Rust crate.
 *
 * One [estimate] call is one call into native code: the caller allocates the
 * result array, the native side fills it in, and nothing is allocated by
 * native code on the way back.
 *
 * Thread safety: the underlying estimator is a pure function over its
 * arguments, so this object holds no mutable estimation state and takes no
 * lock. Concurrent calls from any number of threads are safe and do not
 * contend with each other. The only shared state is the one-time library load
 * and ABI handshake, which is idempotent and internally synchronised.
 *
 * The dictionaries and regular expressions the estimator needs are built
 * lazily on the first non-empty estimate, which costs tens of milliseconds,
 * so the first estimate should run off the main thread. Tests can call
 * [ensureZxcvbnAvailable] to pay it up front.
 */
object Zxcvbn {
    /**
     * Estimates the strength of [password].
     *
     * @param userInputs site- or user-specific words — a name, an email, a
     * service name — that the estimator should treat as guessable. At most 64
     * entries; each entry and the password itself are limited to 256 UTF-8
     * bytes.
     * @throws ZxcvbnException when the native library is unavailable, an
     * argument is rejected, or the native result violates the ABI.
     */
    fun estimate(
        password: String,
        userInputs: List<String> = emptyList(),
    ): ZxcvbnResult {
        val fields = LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT)
        val status = NativeZxcvbn.estimate(
            password = password,
            userInputs = userInputs,
            out = fields,
        )
        if (isNativeZxcvbnFailure(status)) {
            throw zxcvbnFailureException(status)
        }
        if (status != NATIVE_ZXCVBN_STATUS_SUCCESS) {
            throw ZxcvbnException("Native zxcvbn returned an unexpected status $status")
        }
        return decodeNativeZxcvbnResult(fields)
    }
}

/**
 * Eagerly verifies that the native estimator is loadable, speaks the expected
 * ABI, and has finished its lazy initialisation.
 *
 * Useful in tests and diagnostics to make a packaging problem surface as an
 * explicit failure. Applications need not call it: the estimator initialises
 * itself on the first estimate, and callers already run estimates off the
 * main thread.
 *
 * @throws ZxcvbnException when the native library is missing or incompatible.
 */
fun ensureZxcvbnAvailable() {
    // The password must be non-empty: upstream returns the trivial result for
    // an empty password without touching the dictionaries, which would leave
    // the initialisation cost for the first real call.
    Zxcvbn.estimate(WARMUP_PASSWORD)
}

/** Thrown when a native estimate cannot be performed or cannot be trusted. */
class ZxcvbnException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A complete zxcvbn estimate.
 *
 * @param score the coarse `0..4` strength score; `0` is the weakest.
 * @param guesses estimated number of guesses needed to crack the password,
 * saturated at [Long.MAX_VALUE].
 * @param guessesLog10 base-10 logarithm of [guesses], carried separately
 * because the true guess count can exceed [Long.MAX_VALUE]. Negative infinity
 * for an empty password.
 * @param crackTimes estimated crack times under four attack scenarios.
 * @param warning the single most relevant reason the password is weak, or
 * `null` when the estimator has no warning.
 * @param suggestions actionable advice; empty when the estimator has none.
 */
data class ZxcvbnResult(
    val score: Int,
    val guesses: Long,
    val guessesLog10: Double,
    val crackTimes: ZxcvbnCrackTimes,
    val warning: ZxcvbnWarning?,
    val suggestions: Set<ZxcvbnSuggestion>,
)

/**
 * Estimated crack times in seconds under the four scenarios upstream models.
 *
 * @param onlineThrottling100PerHour online attack against a rate-limited
 * service.
 * @param onlineNoThrottling10PerSecond online attack without rate limiting.
 * @param offlineSlowHashing1e4PerSecond offline attack against a slow hash,
 * the scenario Keyguard reports to the user.
 * @param offlineFastHashing1e10PerSecond offline attack against a fast hash.
 */
data class ZxcvbnCrackTimes(
    val onlineThrottling100PerHour: Double,
    val onlineNoThrottling10PerSecond: Double,
    val offlineSlowHashing1e4PerSecond: Double,
    val offlineFastHashing1e10PerSecond: Double,
)

/**
 * The reason a password is weak.
 *
 * [wireCode] is the ABI value and equals the declaration index; both are
 * pinned by tests on either side of the bridge, so an entry may never be
 * reordered or removed.
 */
enum class ZxcvbnWarning {
    StraightRowsOfKeysAreEasyToGuess,
    ShortKeyboardPatternsAreEasyToGuess,
    RepeatsLikeAaaAreEasyToGuess,
    RepeatsLikeAbcAbcAreOnlySlightlyHarderToGuess,
    ThisIsATop10Password,
    ThisIsATop100Password,
    ThisIsACommonPassword,
    ThisIsSimilarToACommonlyUsedPassword,
    SequencesLikeAbcAreEasyToGuess,
    RecentYearsAreEasyToGuess,
    AWordByItselfIsEasyToGuess,
    DatesAreOftenEasyToGuess,
    NamesAndSurnamesByThemselvesAreEasyToGuess,
    CommonNamesAndSurnamesAreEasyToGuess,
    ;

    /** The `keyguard_zxcvbn_warning` value of this warning. */
    val wireCode: Int get() = ordinal
}

/**
 * Actionable advice for strengthening a password.
 *
 * [wireBit] is the ABI bit and equals `1 shl` the declaration index; both are
 * pinned by tests on either side of the bridge, so an entry may never be
 * reordered or removed.
 */
enum class ZxcvbnSuggestion {
    UseAFewWordsAvoidCommonPhrases,
    NoNeedForSymbolsDigitsOrUppercaseLetters,
    AddAnotherWordOrTwo,
    CapitalizationDoesntHelpVeryMuch,
    AllUppercaseIsAlmostAsEasyToGuessAsAllLowercase,
    ReversedWordsArentMuchHarderToGuess,
    PredictableSubstitutionsDontHelpVeryMuch,
    UseALongerKeyboardPatternWithMoreTurns,
    AvoidRepeatedWordsAndCharacters,
    AvoidSequences,
    AvoidRecentYears,
    AvoidYearsThatAreAssociatedWithYou,
    AvoidDatesAndYearsThatAreAssociatedWithYou,
    ;

    /** The `keyguard_zxcvbn_suggestion` bit of this suggestion. */
    val wireBit: Int get() = 1 shl ordinal
}

private const val NATIVE_ZXCVBN_STATUS_SUCCESS: Long = 0L

private const val WARMUP_PASSWORD = "keyguard-zxcvbn-warmup"

private fun zxcvbnFailureException(packedResult: Long): ZxcvbnException {
    val failure = decodeNativeZxcvbnFailure(packedResult)
    val message = when (failure.bridgeCode) {
        NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT ->
            "Native zxcvbn rejected an estimate argument"

        NATIVE_ZXCVBN_BRIDGE_CODE_INPUT_TOO_LONG ->
            "Native zxcvbn rejected an input that is too long"

        NATIVE_ZXCVBN_BRIDGE_CODE_PANIC ->
            "Native zxcvbn panicked while estimating"

        NATIVE_ZXCVBN_BRIDGE_CODE_INTERNAL ->
            "Native zxcvbn failed internally"

        else ->
            "Native zxcvbn failed with ${failure.kind} bridge code ${failure.bridgeCode}"
    }
    return ZxcvbnException(message)
}
