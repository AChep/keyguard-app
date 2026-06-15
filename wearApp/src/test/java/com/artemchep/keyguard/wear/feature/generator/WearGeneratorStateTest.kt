package com.artemchep.keyguard.wear.feature.generator

import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.generator.GeneratorState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearGeneratorStateTest {
    @Test
    fun `wear generator config stays read only and password only`() {
        val config = wearGeneratorConfig()

        assertTrue(config.args.password)
        assertFalse(config.args.username)
        assertFalse(config.args.sshKey)
        assertFalse(config.allowHistory)
        assertFalse(config.allowWordlists)
        assertFalse(config.allowEmailRelays)
        assertFalse(config.allowWrites)
    }

    @Test
    fun `wear generator settings keep only supported controls`() {
        var lengthValue = -1
        var numbersEnabled = false
        var capitalizeEnabled = false

        val filter = GeneratorState.Filter(
            tip = null,
            length = GeneratorState.Filter.Length(
                value = 14,
                min = 3,
                max = 20,
                onChange = { lengthValue = it },
            ),
            items = persistentListOf(
                GeneratorState.Filter.Item.Switch(
                    key = "password.include_numbers",
                    title = "Digits",
                    model = SwitchFieldModel(
                        checked = true,
                        onChange = { numbersEnabled = it },
                    ),
                ),
                GeneratorState.Filter.Item.Switch(
                    key = "password.exclude_ambiguous_chars",
                    title = "Exclude ambiguous symbols",
                    model = SwitchFieldModel(
                        checked = true,
                    ),
                ),
                GeneratorState.Filter.Item.Switch(
                    key = "passphrase.capitalize",
                    title = "Capitalize",
                    model = SwitchFieldModel(
                        checked = false,
                        onChange = { capitalizeEnabled = it },
                    ),
                ),
                GeneratorState.Filter.Item.Text(
                    key = "passphrase.delimiter",
                    title = "Delimiter",
                    model = TextFieldModel2(
                        state = mutableStateOf("-"),
                        text = "-",
                        onChange = {},
                    ),
                ),
                GeneratorState.Filter.Item.Section(
                    key = "passphrase.section",
                ),
            ),
        )

        val settings = filter.toWearGeneratorSettings()

        assertEquals(
            listOf(
                "length",
                "password.include_numbers",
                "passphrase.capitalize",
            ),
            settings.map { it.key },
        )

        val length = settings.first() as WearGeneratorSetting.Length
        val passwordNumbers = settings[1] as WearGeneratorSetting.Switch
        val passphraseCapitalize = settings[2] as WearGeneratorSetting.Switch

        assertEquals(14, length.value)
        assertEquals(3, length.min)
        assertEquals(20, length.max)
        assertTrue(passwordNumbers.checked)
        assertFalse(passphraseCapitalize.checked)

        assertNotNull(length.onChange)
        assertNotNull(passwordNumbers.onChange)
        assertNotNull(passphraseCapitalize.onChange)

        length.onChange?.invoke(18)
        passwordNumbers.onChange?.invoke(false)
        passphraseCapitalize.onChange?.invoke(true)

        assertEquals(18, lengthValue)
        assertFalse(numbersEnabled)
        assertTrue(capitalizeEnabled)
    }
}
