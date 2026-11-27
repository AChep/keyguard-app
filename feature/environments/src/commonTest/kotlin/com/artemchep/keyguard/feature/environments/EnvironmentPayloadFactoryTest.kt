package com.artemchep.keyguard.feature.environments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvironmentPayloadFactoryTest {
    @Test
    fun `a name must be non-blank and fit the limit`() {
        assertTrue(isValidEnvironmentName("api"))
        assertTrue(isValidEnvironmentName("a".repeat(256)))
        assertFalse(isValidEnvironmentName(" "))
        assertFalse(isValidEnvironmentName("a".repeat(257)))
    }

    @Test
    fun `a coerced name is valid`() {
        val name = coerceEnvironmentName("a".repeat(300))
        assertEquals(256, name.length)
        assertTrue(isValidEnvironmentName(name))
    }

    @Test
    fun `a valid definition gives a lossless payload`() {
        val definition = EnvironmentDefinition(listOf(
            EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("abc")),
        ))
        val result = assertIs<EnvironmentPayloadResult.Ok>(environmentPayloadOf(definition))
        assertEquals(definition, result.payload.definition)
        assertEquals(EnvironmentFormat.encode(definition), result.payload.document)
        assertTrue(result.payload.lossless)
    }

    @Test
    fun `an invalid or oversized definition is rejected`() {
        val duplicate = EnvironmentVariable("TOKEN", EnvironmentValueSource.Literal("abc"))
        assertEquals(
            EnvironmentPayloadResult.Invalid,
            environmentPayloadOf(EnvironmentDefinition(listOf(duplicate, duplicate))),
        )
        val large = EnvironmentVariable(
            "TOKEN",
            EnvironmentValueSource.Literal("a".repeat(EnvironmentFormat.MAX_PAYLOAD_BYTES)),
        )
        assertEquals(
            EnvironmentPayloadResult.TooLarge,
            environmentPayloadOf(EnvironmentDefinition(listOf(large))),
        )
    }
}
