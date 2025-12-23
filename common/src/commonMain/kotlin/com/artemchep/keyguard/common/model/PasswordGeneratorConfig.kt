package com.artemchep.keyguard.common.model

sealed interface PasswordGeneratorConfig {
    data class Value(
        val data: String,
    ) : PasswordGeneratorConfig

    data class Passphrase(
        val length: Int,
        val delimiter: String,
        val capitalize: Boolean,
        val includeNumber: Boolean,
        val customWord: String? = null,
        val wordlist: List<String>? = null,
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

    data class PinCode(
        val length: Int,
    ) : PasswordGeneratorConfig

    data class EmailRelay(
        val emailRelay: com.artemchep.keyguard.common.service.relays.api.EmailRelay,
        val config: DGeneratorEmailRelay,
    ) : PasswordGeneratorConfig

    data class KeyPair(
        val config: KeyPairConfig,
    ) : PasswordGeneratorConfig

    data class Composite(
        val config: PasswordGeneratorConfig,
        val transform: (String) -> String?,
    ) : PasswordGeneratorConfig
}

fun PasswordGeneratorConfig.isExpensive(): Boolean = when (this) {
    is PasswordGeneratorConfig.Value -> false
    is PasswordGeneratorConfig.Passphrase -> false
    is PasswordGeneratorConfig.Password -> false
    is PasswordGeneratorConfig.PinCode -> false
    is PasswordGeneratorConfig.EmailRelay -> true
    is PasswordGeneratorConfig.KeyPair -> true
    is PasswordGeneratorConfig.Composite -> config.isExpensive()
}
