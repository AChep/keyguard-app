package com.artemchep.keyguard.feature.environments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvironmentDotenvTest {
    @Test
    fun `parses comments exports empty values and unset keys`() {
        val definition = EnvironmentDotenv.parse("# comment\r\nexport KEY=value # comment\r\nEMPTY=\r\nREQUIRED\r\n")
        assertEquals(listOf(
            EnvironmentVariable("KEY", EnvironmentValueSource.Literal("value")),
            EnvironmentVariable("EMPTY", EnvironmentValueSource.Literal("")),
            EnvironmentVariable("REQUIRED", EnvironmentValueSource.Unset),
        ), definition.variables)
    }

    @Test
    fun `lone carriage returns end lines`() {
        val definition = EnvironmentDotenv.parse("A=1\rB='x\ry'\r")
        assertEquals(listOf(
            EnvironmentVariable("A", EnvironmentValueSource.Literal("1")),
            EnvironmentVariable("B", EnvironmentValueSource.Literal("x\ny")),
        ), definition.variables)
    }

    @Test
    fun `values with edge characters other parsers trim are quoted`() {
        listOf("﻿it's", "it's﻿", "\u0085it's", "it's\u0085").forEach { value ->
            val definition = EnvironmentDefinition(listOf(
                EnvironmentVariable("KEY", EnvironmentValueSource.Literal(value)),
            ))
            assertEquals("KEY=\"$value\"\n", EnvironmentDotenv.format(definition))
        }
    }

    @Test
    fun `quoted multiline and literal interpolation round trip`() {
        val definition = EnvironmentDefinition(listOf(
            EnvironmentVariable("MULTILINE", EnvironmentValueSource.Literal("line one\nline two\r\t")),
            EnvironmentVariable("LITERAL", EnvironmentValueSource.Literal("\${HOME} $(touch x) # \"")),
            EnvironmentVariable("UNICODE", EnvironmentValueSource.Literal("🔑 Київ")),
        ))
        assertEquals(definition, EnvironmentDotenv.parse(EnvironmentDotenv.format(definition)))
        assertEquals(
            EnvironmentValueSource.Literal("line one\nline two"),
            EnvironmentDotenv.parse("KEY='line one\nline two'").variables.single().source,
        )
    }

    @Test
    fun `malformed documents fail with safe diagnostics`() {
        listOf("KEY=\"secret", "KEY='secret' garbage", "KEY=one\nKEY=two", "BAD-NAME=secret").forEach { text ->
            val error = assertFailsWith<EnvironmentDotenvException> { EnvironmentDotenv.parse(text) }
            assertTrue("secret" !in error.message.orEmpty())
        }
    }

    @Test
    fun `delivery preserves backslashes quotes carriage returns and empty values`() {
        val values = listOf("", "C:\\path\\folder", "${'$'}{HOME}", "it's \"quoted\"", "a\\\\b",
            "line one\nline two", "a\rb", "a\t b", "line's #one\n\\q", "ends\\")
        val definition = EnvironmentDefinition(values.mapIndexed { i, value ->
            EnvironmentVariable("KEY_$i", EnvironmentValueSource.Literal(value))
        })
        assertEquals(definition, EnvironmentDotenv.parse(EnvironmentDotenv.format(definition)))
    }

    @Test
    fun `ambiguous dotenv encodings fail instead of corrupting the delivered value`() {
        val values = listOf("both ' and \" #", "newline\nwith ' and \"", "\\\\ #", "'\\t\n")
        values.forEach { value ->
            assertFailsWith<EnvironmentDotenvException> {
                EnvironmentDotenv.format(EnvironmentDefinition(listOf(
                    EnvironmentVariable("SECRET", EnvironmentValueSource.Literal(value)),
                )))
            }
        }
    }

    @Test
    fun `backslash followed by carriage return only blocks dotenv delivery`() {
        val values = listOf("\\\r", "\\\rx", "prefix\\\r\nsuffix")
        values.forEach { value ->
            val definition = EnvironmentDefinition(listOf(
                EnvironmentVariable("SECRET", EnvironmentValueSource.Literal(value)),
            ))
            val stored = EnvironmentFormat.decode(EnvironmentFormat.encode(definition))!!.definition
            assertEquals(definition, stored)
            val resolved = assertIs<EnvironmentResolutionResult.Resolved>(EnvironmentResolver.resolve(stored))
            assertEquals(mapOf("SECRET" to value), resolved.snapshot.variables)

            assertFailsWith<EnvironmentDotenvException> { EnvironmentDotenv.format(stored) }
            assertFailsWith<EnvironmentDotenvException> { EnvironmentDotenv.format(resolved.snapshot) }
            assertEquals(mapOf("SECRET" to value), resolved.snapshot.variables)
        }
    }

    @Test
    fun `oversize and NUL imports fail`() {
        assertFailsWith<EnvironmentDotenvException> { EnvironmentDotenv.parse("KEY=" + "x".repeat(65_536)) }
        assertFailsWith<EnvironmentDotenvException> { EnvironmentDotenv.parse("KEY=\u0000") }
    }
}
