package com.artemchep.keyguard.wear.feature.generator

import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.generator.GeneratorState

private const val PASSWORD_KEY_INCLUDE_NUMBERS = "password.include_numbers"
private const val PASSWORD_KEY_INCLUDE_SYMBOLS = "password.include_symbols"
private const val PASSWORD_KEY_INCLUDE_UPPERCASE = "password.include_uppercase_chars"
private const val PASSWORD_KEY_INCLUDE_LOWERCASE = "password.include_lowercase_chars"
private const val PASSPHRASE_KEY_CAPITALIZE = "passphrase.capitalize"
private const val PASSPHRASE_KEY_INCLUDE_NUMBER = "passphrase.include_number"

internal sealed interface WearGeneratorSetting {
    val key: String

    data class Length(
        override val key: String,
        val value: Int,
        val min: Int,
        val max: Int,
        val onChange: ((Int) -> Unit)?,
    ) : WearGeneratorSetting

    data class Switch(
        override val key: String,
        val title: String,
        val text: String?,
        val checked: Boolean,
        val onChange: ((Boolean) -> Unit)?,
    ) : WearGeneratorSetting
}

internal data class WearGeneratorConfig(
    val args: GeneratorRoute.Args,
    val allowHistory: Boolean,
    val allowWordlists: Boolean,
    val allowEmailRelays: Boolean,
    val allowWrites: Boolean,
)

internal fun wearGeneratorArgs(): GeneratorRoute.Args = GeneratorRoute.Args(
    password = true,
    username = false,
    sshKey = false,
)

internal fun wearGeneratorConfig(): WearGeneratorConfig = WearGeneratorConfig(
    args = wearGeneratorArgs(),
    allowHistory = false,
    allowWordlists = false,
    allowEmailRelays = false,
    allowWrites = false,
)

internal fun GeneratorState.Filter.toWearGeneratorSettings(): List<WearGeneratorSetting> = buildList {
    length?.let { length ->
        add(
            WearGeneratorSetting.Length(
                key = "length",
                value = length.value,
                min = length.min,
                max = length.max,
                onChange = length.onChange,
            ),
        )
    }

    items.forEach { item ->
        when (item) {
            is GeneratorState.Filter.Item.Switch ->
                if (item.key.isSupportedWearGeneratorSwitch()) {
                    add(
                        WearGeneratorSetting.Switch(
                            key = item.key,
                            title = item.title,
                            text = item.text,
                            checked = item.model.checked,
                            onChange = item.model.onChange,
                        ),
                    )
                }

            else -> Unit
        }
    }
}

internal fun String.isSupportedWearGeneratorSwitch(): Boolean = when (this) {
    PASSWORD_KEY_INCLUDE_NUMBERS,
    PASSWORD_KEY_INCLUDE_SYMBOLS,
    PASSWORD_KEY_INCLUDE_UPPERCASE,
    PASSWORD_KEY_INCLUDE_LOWERCASE,
    PASSPHRASE_KEY_CAPITALIZE,
    PASSPHRASE_KEY_INCLUDE_NUMBER,
    -> true

    else -> false
}
