package com.artemchep.keyguard.feature.generator

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.PasswordGeneratorConfigBuilder2
import com.artemchep.keyguard.common.model.build
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetScreenState
import com.artemchep.keyguard.feature.crashlytics.crashlyticsTap

/**
 * Generates passwords with the password settings that the user saved on the
 * main generator screen, see [GeneratorPersistedKeys.MAIN_SCREEN_STATE], or
 * with their defaults. It uses the password settings even when the user
 * selected another type of generator, such as a passphrase.
 */
class PasswordGeneratorConfigReader(
    private val getScreenState: GetScreenState,
    private val getPassword: GetPassword,
) {
    /** The saved settings of the password generator. */
    suspend fun read(): PasswordGeneratorConfigBuilder2.Password {
        // As the generator screen restores it: unreadable state reads as none.
        val state = getScreenState(GeneratorPersistedKeys.MAIN_SCREEN_STATE)
            .toIO()
            .crashlyticsTap()
            .attempt()
            .bind()
            .getOrNull()
            .orEmpty()
        return with(GeneratorPersistedKeys.Password) {
            PasswordGeneratorConfigBuilder2.Password(
                length = state.long(LENGTH, LENGTH_DEFAULT)
                    .coerceIn(LENGTH_MIN.toLong(), LENGTH_MAX.toLong())
                    .toInt(),
                includeUppercaseCharacters = state.boolean(
                    INCLUDE_UPPERCASE_CHARS,
                    INCLUDE_UPPERCASE_CHARS_DEFAULT,
                ),
                includeUppercaseCharactersMin = state.minimum(
                    INCLUDE_UPPERCASE_CHARS_MIN,
                    INCLUDE_UPPERCASE_CHARS_MIN_DEFAULT,
                ),
                includeLowercaseCharacters = state.boolean(
                    INCLUDE_LOWERCASE_CHARS,
                    INCLUDE_LOWERCASE_CHARS_DEFAULT,
                ),
                includeLowercaseCharactersMin = state.minimum(
                    INCLUDE_LOWERCASE_CHARS_MIN,
                    INCLUDE_LOWERCASE_CHARS_MIN_DEFAULT,
                ),
                includeNumbers = state.boolean(INCLUDE_NUMBERS, INCLUDE_NUMBERS_DEFAULT),
                includeNumbersMin = state.minimum(INCLUDE_NUMBERS_MIN, INCLUDE_NUMBERS_MIN_DEFAULT),
                includeSymbols = state.boolean(INCLUDE_SYMBOLS, INCLUDE_SYMBOLS_DEFAULT),
                includeSymbolsMin = state.minimum(INCLUDE_SYMBOLS_MIN, INCLUDE_SYMBOLS_MIN_DEFAULT),
                excludeSimilarCharacters = state.boolean(EXCLUDE_SIMILAR_CHARS, EXCLUDE_SIMILAR_CHARS_DEFAULT),
                excludeAmbiguousCharacters = state.boolean(
                    EXCLUDE_AMBIGUOUS_CHARS,
                    EXCLUDE_AMBIGUOUS_CHARS_DEFAULT,
                ),
            )
        }
    }

    /**
     * A new password with the saved settings, for the website of [host];
     * `null` if the generator made none.
     */
    suspend fun generate(host: String?): String? {
        val config = read().build()
        val result = getPassword(GeneratorContext(host = host), config)
            .crashlyticsTap()
            .attempt()
            .bind()
            .getOrNull()
        return (result as? GetPasswordResult.Value)
            ?.value
            ?.takeIf { it.isNotEmpty() }
    }
}

// A value of another type than the generator writes is ignored.

private fun Map<String, Any?>.long(key: String, default: Long): Long =
    (get(key) as? Number)?.toLong() ?: default

private fun Map<String, Any?>.boolean(key: String, default: Boolean): Boolean =
    get(key) as? Boolean ?: default

/**
 * A minimum count of characters, within what the generator screen saves. The
 * generator loops for the count, so a corrupt value must not reach it.
 */
private fun Map<String, Any?>.minimum(key: String, default: Long): Long =
    long(key, default).coerceIn(1L, GeneratorPersistedKeys.Password.LENGTH_MAX.toLong())
