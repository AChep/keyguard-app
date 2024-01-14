package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.auth.common.util.REGEX_DOMAIN
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL

sealed interface PasswordGeneratorConfigBuilder2 {
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

    data class EmailCatchAll(
        val length: Int,
        val domain: String,
    ) : PasswordGeneratorConfigBuilder2

    data class EmailPlusAddressing(
        val length: Int,
        val email: String,
    ) : PasswordGeneratorConfigBuilder2

    data class EmailSubdomainAddressing(
        val length: Int,
        val email: String,
    ) : PasswordGeneratorConfigBuilder2

    data class EmailRelay(
        val emailRelay: com.artemchep.keyguard.common.service.relays.api.EmailRelay,
        val config: DGeneratorEmailRelay,
    ) : PasswordGeneratorConfigBuilder2
}

fun PasswordGeneratorConfigBuilder2.build(): PasswordGeneratorConfig = when (this) {
    is PasswordGeneratorConfigBuilder2.Password -> build()
    is PasswordGeneratorConfigBuilder2.Passphrase -> build()
    is PasswordGeneratorConfigBuilder2.Username -> build()
    is PasswordGeneratorConfigBuilder2.EmailCatchAll -> build()
    is PasswordGeneratorConfigBuilder2.EmailPlusAddressing -> build()
    is PasswordGeneratorConfigBuilder2.EmailSubdomainAddressing -> build()
    is PasswordGeneratorConfigBuilder2.EmailRelay -> build()
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
        length = length,
    )
    val domain = domain
        .takeIf { REGEX_DOMAIN.matches(it) }
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
        length = length,
    )
    val email = email
        .takeIf { REGEX_EMAIL.matches(it) }
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
        length = length,
    )
    val email = email
        .takeIf { REGEX_EMAIL.matches(it) }
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

private fun buildEmailFriendlyGeneratorConfig(
    length: Int,
) = PasswordGeneratorConfigBuilder2.Password(
    length = length,
    includeUppercaseCharacters = false,
    includeLowercaseCharacters = true,
    includeNumbers = true,
    includeSymbols = false,
    excludeSimilarCharacters = true,
    excludeAmbiguousCharacters = true,
).build()

private fun parseEmail(email: String): ParsedEmail? {
    val index = email.indexOf("@")
    if (index != -1) {
        val username = email.substring(startIndex = 0, endIndex = index)
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
