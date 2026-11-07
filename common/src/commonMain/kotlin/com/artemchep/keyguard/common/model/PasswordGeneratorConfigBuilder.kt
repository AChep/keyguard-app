package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.auth.common.util.REGEX_DOMAIN
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.isPublicEmailDomain

/**
 * Characters that a name may not contain, as they would
 * change the structure of a `Name <email>` GPG User ID.
 */
const val GPG_KEY_NAME_RESERVED_CHARS = "<>()"

sealed interface PasswordGeneratorConfigBuilder2 {
    data class Value(
        val data: String,
    ) : PasswordGeneratorConfigBuilder2

    data class Passphrase(
        val length: Int,
        val delimiter: String,
        val capitalize: Boolean,
        val includeNumber: Boolean,
        val customWord: String,
        val wordlistId: Long?,
        val wordlists: List<DGeneratorWordlist>,
        val wordlist: List<String>?,
    ) : PasswordGeneratorConfigBuilder2

    data class Username(
        val length: Int,
        val delimiter: String,
        val capitalize: Boolean,
        val includeNumber: Boolean,
        val customWord: String,
        val wordlistId: Long?,
        val wordlists: List<DGeneratorWordlist>,
        val wordlist: List<String>?,
    ) : PasswordGeneratorConfigBuilder2

    data class Password(
        val length: Int,
        val includeUppercaseCharacters: Boolean = true,
        val includeUppercaseCharactersMin: Long = 1L,
        val includeLowercaseCharacters: Boolean = true,
        val includeLowercaseCharactersMin: Long = 1L,
        val includeNumbers: Boolean = true,
        val includeNumbersMin: Long = 1L,
        val includeSymbols: Boolean = true,
        val includeSymbolsMin: Long = 1L,
        val excludeSimilarCharacters: Boolean = true,
        val excludeAmbiguousCharacters: Boolean = true,
    ) : PasswordGeneratorConfigBuilder2

    data class PinCode(
        val length: Int,
    ) : PasswordGeneratorConfigBuilder2

    data class EmailCatchAll(
        val payload: EmailPayload,
        val domain: String,
    ) : PasswordGeneratorConfigBuilder2

    data class EmailPlusAddressing(
        val payload: EmailPayload,
        val email: String,
    ) : PasswordGeneratorConfigBuilder2

    data class EmailSubdomainAddressing(
        val payload: EmailPayload,
        val email: String,
    ) : PasswordGeneratorConfigBuilder2

    sealed interface EmailPayload {
        data class RandomlyGenerated(
            val length: Int,
        ) : EmailPayload

        data class Value(
            val data: String,
        ) : EmailPayload
    }

    data class EmailRelay(
        val emailRelay: com.artemchep.keyguard.common.service.relays.api.EmailRelay,
        val config: DGeneratorEmailRelay,
    ) : PasswordGeneratorConfigBuilder2

    data class KeyPair(
        val config: KeyPairConfig,
    ) : PasswordGeneratorConfigBuilder2

    /**
     * @param name the raw name the [GpgKeyConfig.userId] is built from
     * @param email the raw email the [GpgKeyConfig.userId] is built from
     */
    data class GpgKey(
        val config: GpgKeyConfig,
        val name: String,
        val email: String,
    ) : PasswordGeneratorConfigBuilder2
}

enum class GeneratorInputError {
    EMPTY,
    PUBLIC_DOMAIN,
    INVALID_DOMAIN,
    INVALID_EMAIL,
    RESERVED_CHARS,
}

fun PasswordGeneratorConfigBuilder2.EmailCatchAll.validateDomain(): GeneratorInputError? {
    val value = domain.trim()
    return when {
        value.isEmpty() -> GeneratorInputError.EMPTY
        isPublicEmailDomain(value) -> GeneratorInputError.PUBLIC_DOMAIN
        !REGEX_DOMAIN.matches(value) -> GeneratorInputError.INVALID_DOMAIN
        else -> null
    }
}

fun PasswordGeneratorConfigBuilder2.EmailPlusAddressing.validateEmail(): GeneratorInputError? {
    val value = email.trim()
    return when {
        value.isEmpty() -> GeneratorInputError.EMPTY
        !REGEX_EMAIL.matches(value) -> GeneratorInputError.INVALID_EMAIL
        else -> null
    }
}

fun PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing.validateEmail(): GeneratorInputError? {
    val value = email.trim()
    return when {
        value.isEmpty() -> GeneratorInputError.EMPTY
        isPublicEmailDomain(value.substringAfterLast('@', "")) ->
            GeneratorInputError.PUBLIC_DOMAIN

        !REGEX_EMAIL.matches(value) -> GeneratorInputError.INVALID_EMAIL
        else -> null
    }
}

fun PasswordGeneratorConfigBuilder2.GpgKey.validateName(): GeneratorInputError? {
    val value = name.trim()
    return when {
        value.isEmpty() -> GeneratorInputError.EMPTY
        value.any { it in GPG_KEY_NAME_RESERVED_CHARS } -> GeneratorInputError.RESERVED_CHARS
        else -> null
    }
}

/** The email is optional, so an empty value is valid. */
fun PasswordGeneratorConfigBuilder2.GpgKey.validateEmail(): GeneratorInputError? {
    val value = email.trim()
    return when {
        value.isEmpty() -> null
        !REGEX_EMAIL.matches(value) -> GeneratorInputError.INVALID_EMAIL
        else -> null
    }
}

/**
 * Returns `false` if any of the inputs has a validation error,
 * so the generator must not produce a value.
 */
fun PasswordGeneratorConfigBuilder2.isValid(): Boolean = when (this) {
    is PasswordGeneratorConfigBuilder2.EmailCatchAll -> validateDomain() == null
    is PasswordGeneratorConfigBuilder2.EmailPlusAddressing -> validateEmail() == null
    is PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing -> validateEmail() == null
    is PasswordGeneratorConfigBuilder2.GpgKey ->
        validateName() == null && validateEmail() == null

    is PasswordGeneratorConfigBuilder2.Value,
    is PasswordGeneratorConfigBuilder2.Password,
    is PasswordGeneratorConfigBuilder2.Passphrase,
    is PasswordGeneratorConfigBuilder2.PinCode,
    is PasswordGeneratorConfigBuilder2.Username,
    is PasswordGeneratorConfigBuilder2.EmailRelay,
    is PasswordGeneratorConfigBuilder2.KeyPair,
    -> true
}

fun PasswordGeneratorConfigBuilder2.build(): PasswordGeneratorConfig = when (this) {
    is PasswordGeneratorConfigBuilder2.Value -> build()
    is PasswordGeneratorConfigBuilder2.Password -> build()
    is PasswordGeneratorConfigBuilder2.Passphrase -> build()
    is PasswordGeneratorConfigBuilder2.PinCode -> build()
    is PasswordGeneratorConfigBuilder2.Username -> build()
    is PasswordGeneratorConfigBuilder2.EmailCatchAll -> build()
    is PasswordGeneratorConfigBuilder2.EmailPlusAddressing -> build()
    is PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing -> build()
    is PasswordGeneratorConfigBuilder2.EmailRelay -> build()
    is PasswordGeneratorConfigBuilder2.KeyPair -> build()
    is PasswordGeneratorConfigBuilder2.GpgKey -> build()
}

fun PasswordGeneratorConfigBuilder2.Value.build(): PasswordGeneratorConfig {
    return PasswordGeneratorConfig.Value(
        data = data,
    )
}

fun PasswordGeneratorConfigBuilder2.Password.build(): PasswordGeneratorConfig {
    val uppercaseChars = mutableListOf<Char>()
    val lowercaseChars = mutableListOf<Char>()
    val numberChars = mutableListOf<Char>()
    val symbolChars = mutableListOf<Char>()

    if (includeUppercaseCharacters) {
        uppercaseChars += 'A'..'Z'
    }
    if (includeLowercaseCharacters) {
        lowercaseChars += 'a'..'z'
    }
    if (includeNumbers) {
        numberChars += '0'..'9'
    }
    if (includeSymbols) {
        symbolChars += """!"#$%&'()*+-./:;<=>?@[\]^_`{|}~"""
            .toCharArray()
            .toList()
    }
    if (excludeSimilarCharacters) {
        // uppercase
        uppercaseChars -= 'L'
        uppercaseChars -= 'O'
        // lowercase
        lowercaseChars -= 'i'
        lowercaseChars -= 'l'
        lowercaseChars -= 'o'
        // numbers
        numberChars -= '1'
        numberChars -= '0'
        // symbols
        symbolChars -= '|'
    }
    if (excludeAmbiguousCharacters) {
        symbolChars -= """{}[]()/\'"`~,;:.<>"""
            .toCharArray()
            .toSet()
    }
    return PasswordGeneratorConfig.Password(
        length = length,
        // chars
        uppercaseChars = uppercaseChars,
        lowercaseChars = lowercaseChars,
        numberChars = numberChars,
        symbolChars = symbolChars,
        // config
        uppercaseMin = includeUppercaseCharactersMin,
        lowercaseMin = includeLowercaseCharactersMin,
        numbersMin = includeNumbersMin,
        symbolsMin = includeSymbolsMin,
    )
}

fun PasswordGeneratorConfigBuilder2.Passphrase.build() =
    PasswordGeneratorConfig.Passphrase(
        length = length,
        delimiter = delimiter,
        capitalize = capitalize,
        includeNumber = includeNumber,
        customWord = customWord.trim().takeUnless { it.isEmpty() },
        wordlist = wordlist,
    )

fun PasswordGeneratorConfigBuilder2.PinCode.build() =
    PasswordGeneratorConfig.PinCode(
        length = length,
    )

fun PasswordGeneratorConfigBuilder2.Username.build() =
    PasswordGeneratorConfig.Passphrase(
        length = length,
        delimiter = delimiter,
        capitalize = capitalize,
        includeNumber = includeNumber,
        customWord = customWord.trim().takeUnless { it.isEmpty() },
        wordlist = wordlist,
    )

fun PasswordGeneratorConfigBuilder2.EmailCatchAll.build(): PasswordGeneratorConfig {
    val config = buildEmailFriendlyGeneratorConfig(
        payload = payload,
    )
    val domain = domain.trim()
        .takeIf { validateDomain() == null }
    return PasswordGeneratorConfig.Composite(
        config = config,
        transform = { pwd ->
            if (domain != null) {
                "$pwd@$domain"
            } else {
                null
            }
        },
    )
}

fun PasswordGeneratorConfigBuilder2.EmailPlusAddressing.build(): PasswordGeneratorConfig {
    val config = buildEmailFriendlyGeneratorConfig(
        payload = payload,
    )
    val email = email.trim()
        .takeIf { validateEmail() == null }
        ?.let(::parseEmail)
    return PasswordGeneratorConfig.Composite(
        config = config,
        transform = { pwd ->
            if (email != null) {
                "${email.username}+$pwd@${email.domain}"
            } else {
                null
            }
        },
    )
}

fun PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing.build(): PasswordGeneratorConfig {
    val config = buildEmailFriendlyGeneratorConfig(
        payload = payload,
    )
    val email = email.trim()
        .takeIf { validateEmail() == null }
        ?.let(::parseEmail)
    return PasswordGeneratorConfig.Composite(
        config = config,
        transform = { pwd ->
            if (email != null) {
                "${email.username}@$pwd.${email.domain}"
            } else {
                null
            }
        },
    )
}

fun PasswordGeneratorConfigBuilder2.EmailRelay.build(): PasswordGeneratorConfig {
    return PasswordGeneratorConfig.EmailRelay(
        emailRelay = emailRelay,
        config = config,
    )
}

fun PasswordGeneratorConfigBuilder2.KeyPair.build(): PasswordGeneratorConfig {
    return PasswordGeneratorConfig.KeyPair(
        config = config,
    )
}

fun PasswordGeneratorConfigBuilder2.GpgKey.build(): PasswordGeneratorConfig {
    return PasswordGeneratorConfig.GpgKey(
        config = config,
    )
}

private fun buildEmailFriendlyGeneratorConfig(
    payload: PasswordGeneratorConfigBuilder2.EmailPayload,
) = when (payload) {
    is PasswordGeneratorConfigBuilder2.EmailPayload.Value -> {
        PasswordGeneratorConfigBuilder2.Value(
            data = payload.data,
        ).build()
    }
    is PasswordGeneratorConfigBuilder2.EmailPayload.RandomlyGenerated -> {
        PasswordGeneratorConfigBuilder2.Password(
            length = payload.length,
            includeUppercaseCharacters = false,
            includeLowercaseCharacters = true,
            includeNumbers = true,
            includeSymbols = false,
            excludeSimilarCharacters = true,
            excludeAmbiguousCharacters = true,
        ).build()
    }
}

private fun parseEmail(email: String): ParsedEmail? {
    val index = email.indexOf("@")
    if (index != -1) {
        val username = email.take(index)
        val domain = email.substring(startIndex = index + 1)
        return ParsedEmail(
            username = username,
            domain = domain,
        )
    }

    return null
}

private data class ParsedEmail(
    val username: String,
    val domain: String,
)
