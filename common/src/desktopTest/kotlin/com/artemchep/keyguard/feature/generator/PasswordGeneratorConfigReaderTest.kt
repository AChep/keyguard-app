package com.artemchep.keyguard.feature.generator

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.model.PasswordGeneratorConfigBuilder2
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class PasswordGeneratorConfigReaderTest {
    @Test
    fun `the keys are the ones the generator screen has always persisted`() {
        // Changing them loses the settings that users saved.
        assertEquals("generator@*", GeneratorPersistedKeys.MAIN_SCREEN_STATE)
        assertEquals("generator", GeneratorPersistedKeys.storage(key = null, storageKey = null))
        assertEquals("a:b:generator", GeneratorPersistedKeys.storage(key = "a", storageKey = "b"))
        with(GeneratorPersistedKeys.Password) {
            assertEquals("password.length", LENGTH)
            assertEquals("password.include_uppercase_chars", INCLUDE_UPPERCASE_CHARS)
            assertEquals("password.include_uppercase_chars.min", INCLUDE_UPPERCASE_CHARS_MIN)
            assertEquals("password.include_lowercase_chars", INCLUDE_LOWERCASE_CHARS)
            assertEquals("password.include_lowercase_chars.min", INCLUDE_LOWERCASE_CHARS_MIN)
            assertEquals("password.include_numbers", INCLUDE_NUMBERS)
            assertEquals("password.include_numbers.min", INCLUDE_NUMBERS_MIN)
            assertEquals("password.include_symbols", INCLUDE_SYMBOLS)
            assertEquals("password.include_symbols.min", INCLUDE_SYMBOLS_MIN)
            assertEquals("password.exclude_similar_chars", EXCLUDE_SIMILAR_CHARS)
            assertEquals("password.exclude_ambiguous_chars", EXCLUDE_AMBIGUOUS_CHARS)
        }
    }

    @Test
    fun `nothing saved reads the defaults of the generator`() = runTest {
        val reader = reader(emptyMap())
        assertEquals(PasswordGeneratorConfigBuilder2.Password(length = 16), reader.read())
    }

    @Test
    fun `the saved settings are read, the length within its bounds`() = runTest {
        val reader = reader(
            mapOf(
                "password.length" to 1_000L,
                "password.include_lowercase_chars" to false,
                "password.include_numbers.min" to 3L,
                "password.exclude_ambiguous_chars" to false,
            ),
        )
        val expected = PasswordGeneratorConfigBuilder2.Password(
            length = 128,
            includeLowercaseCharacters = false,
            includeNumbersMin = 3L,
            excludeAmbiguousCharacters = false,
        )
        assertEquals(expected, reader.read())
    }

    @Test
    fun `a corrupt minimum is kept within what the generator screen saves`() = runTest {
        // The generator loops for a minimum, so an unbounded one exhausts memory.
        val reader = reader(
            mapOf(
                "password.include_numbers.min" to Long.MAX_VALUE,
                "password.include_symbols.min" to 0L,
            ),
        )
        val expected = PasswordGeneratorConfigBuilder2.Password(
            length = 16,
            includeNumbersMin = 128L,
            includeSymbolsMin = 1L,
        )
        assertEquals(expected, reader.read())
    }

    @Test
    fun `unreadable settings read the defaults, as on the generator screen`() = runTest {
        val reader = reader(
            state = flow { throw IOException("unreadable") },
            generate = { _, _ -> GetPasswordResult.Value("password") },
        )
        assertEquals(PasswordGeneratorConfigBuilder2.Password(length = 16), reader.read())
        assertEquals("password", reader.generate(host = null))
    }

    @Test
    fun `generate passes the host of the page`() = runTest {
        var context: GeneratorContext? = null
        val reader = reader(emptyMap()) { ctx, _ ->
            context = ctx
            GetPasswordResult.Value("password")
        }
        assertEquals("password", reader.generate("example.com"))
        assertEquals(GeneratorContext(host = "example.com"), context)
    }

    private fun reader(
        state: Map<String, Any?>,
        generate: (GeneratorContext, PasswordGeneratorConfig) -> GetPasswordResult? = { _, _ -> null },
    ) = reader(
        state = flowOf(state),
        generate = generate,
    )

    private fun reader(
        state: Flow<Map<String, Any?>>,
        generate: (GeneratorContext, PasswordGeneratorConfig) -> GetPasswordResult?,
    ) = PasswordGeneratorConfigReader(
        getScreenState = object : GetScreenState {
            override fun invoke(key: String): Flow<Map<String, Any?>> =
                if (key == "generator@*") state else flowOf(emptyMap())
        },
        getPassword = object : GetPassword {
            override fun invoke(
                context: GeneratorContext,
                config: PasswordGeneratorConfig,
            ): IO<GetPasswordResult?> = ioEffect { generate(context, config) }
        },
    )
}
