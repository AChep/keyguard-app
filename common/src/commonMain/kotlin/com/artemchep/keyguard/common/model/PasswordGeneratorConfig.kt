package com.artemchep.keyguard.common.model

sealed interface PasswordGeneratorConfig {
    data class Passphrase(
        val length: Int,
        val delimiter: String,
        val capitalize: Boolean,
        val includeNumber: Boolean,
        val customWord: String? = null,
    ) : PasswordGeneratorConfig

    data class Password(
        val length: Int,
        val uppercaseChars: List<Char>,
        val lowercaseChars: List<Char>,
        val numberChars: List<Char>,
        val symbolChars: List<Char>,
        val uppercaseMin: Long,
        val lowercaseMin: Long,
        val numbersMin: Long,
        val symbolsMin: Long,
    ) : PasswordGeneratorConfig {
        val allChars: List<Char> = uppercaseChars + lowercaseChars + numberChars + symbolChars
    }

    data class EmailRelay(
        val emailRelay: com.artemchep.keyguard.common.service.relays.api.EmailRelay,
        val config: DGeneratorEmailRelay,
    ) : PasswordGeneratorConfig

    data class Composite(
        val config: PasswordGeneratorConfig,
        val transform: (String) -> String?,
    ) : PasswordGeneratorConfig
}

fun PasswordGeneratorConfig.isExpensive(): Boolean = when (this) {
    is PasswordGeneratorConfig.Passphrase -> false
    is PasswordGeneratorConfig.Password -> false
    is PasswordGeneratorConfig.EmailRelay -> true
    is PasswordGeneratorConfig.Composite -> config.isExpensive()
}
