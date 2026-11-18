package com.artemchep.keyguard.feature.generator

import com.artemchep.keyguard.feature.navigation.state.globalDiskHandleKey

/**
 * Where the generator persists its state, and the keys and defaults of its
 * password settings. The generator screen writes them; other features read
 * the user's password settings with them.
 */
internal object GeneratorPersistedKeys {
    /** The name of the generator's persisted state. */
    private const val STORAGE = "generator"

    /**
     * The persisted state of a generator with neither a key nor a storage key,
     * as `GetScreenState` names it: a generator stores its state globally. It is
     * the main generator screen on desktop, phones and Apple platforms; the one
     * of Wear OS has a key.
     */
    val MAIN_SCREEN_STATE = globalDiskHandleKey(storage(key = null, storageKey = null))

    /** The name of the persisted state of a generator with the [key] and [storageKey] prefixes. */
    fun storage(
        key: String?,
        storageKey: String?,
    ): String = key?.let { "$it:" }.orEmpty() +
        storageKey?.let { "$it:" }.orEmpty() +
        STORAGE

    /** The settings of the password generator. */
    object Password {
        const val PREFIX = "password"

        const val LENGTH = "$PREFIX.length"
        const val INCLUDE_UPPERCASE_CHARS = "$PREFIX.include_uppercase_chars"
        const val INCLUDE_UPPERCASE_CHARS_MIN = "$INCLUDE_UPPERCASE_CHARS.min"
        const val INCLUDE_LOWERCASE_CHARS = "$PREFIX.include_lowercase_chars"
        const val INCLUDE_LOWERCASE_CHARS_MIN = "$INCLUDE_LOWERCASE_CHARS.min"
        const val INCLUDE_NUMBERS = "$PREFIX.include_numbers"
        const val INCLUDE_NUMBERS_MIN = "$INCLUDE_NUMBERS.min"
        const val INCLUDE_SYMBOLS = "$PREFIX.include_symbols"
        const val INCLUDE_SYMBOLS_MIN = "$INCLUDE_SYMBOLS.min"
        const val EXCLUDE_SIMILAR_CHARS = "$PREFIX.exclude_similar_chars"
        const val EXCLUDE_AMBIGUOUS_CHARS = "$PREFIX.exclude_ambiguous_chars"

        const val LENGTH_DEFAULT = 16L
        const val LENGTH_MIN = 4
        const val LENGTH_MAX = 128
        const val INCLUDE_UPPERCASE_CHARS_DEFAULT = true
        const val INCLUDE_UPPERCASE_CHARS_MIN_DEFAULT = 1L
        const val INCLUDE_LOWERCASE_CHARS_DEFAULT = true
        const val INCLUDE_LOWERCASE_CHARS_MIN_DEFAULT = 1L
        const val INCLUDE_NUMBERS_DEFAULT = true
        const val INCLUDE_NUMBERS_MIN_DEFAULT = 1L
        const val INCLUDE_SYMBOLS_DEFAULT = true
        const val INCLUDE_SYMBOLS_MIN_DEFAULT = 1L
        const val EXCLUDE_SIMILAR_CHARS_DEFAULT = true
        const val EXCLUDE_AMBIGUOUS_CHARS_DEFAULT = true
    }
}
