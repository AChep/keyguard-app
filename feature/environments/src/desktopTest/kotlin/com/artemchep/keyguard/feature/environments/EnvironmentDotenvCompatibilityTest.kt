package com.artemchep.keyguard.feature.environments

import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/** Synthetic fixtures consumed by scripts/check_environment_dotenv.py in native CI. */
class EnvironmentDotenvCompatibilityTest {
    @Test
    fun `write exact value compatibility fixture`() {
        val values = mapOf(
            "EMPTY" to "",
            "UNICODE" to "🔑 Київ",
            "MULTILINE" to "line one\nline two",
            "CRLF" to "line one\r\nline two",
            "TABS" to "\tinside\tvalue\t",
            "LITERAL" to "\${HOME} $(echo literal) # data",
            "PATH" to "C:\\path\\folder",
            "BACKSLASH_N" to "literal\\n",
            "DOUBLE_BACKSLASH" to "path\\\\name",
            "QUOTES" to "it's \"quoted\"",
            "HASH_QUOTES" to "value # \"quoted\"",
            "SINGLE_MULTILINE" to "line's # one\n\\q",
            "TRAILING_SLASH" to "ends\\",
            "SPACES" to " padded value ",
            "BOM_EDGES" to "﻿it's﻿",
            "NEL_EDGES" to "\u0085it's\u0085",
        )
        val definition = EnvironmentDefinition(values.map { (name, value) ->
            EnvironmentVariable(name, EnvironmentValueSource.Literal(value))
        })
        val encoded = EnvironmentDotenv.format(definition)
        assertEquals(definition, EnvironmentDotenv.parse(encoded))
        val fixture = buildJsonObject {
            put("text", encoded)
            put("values", buildJsonObject { values.forEach { (key, value) -> put(key, JsonPrimitive(value)) } })
        }
        File("build/dotenv-compatibility.json").apply { parentFile.mkdirs() }.writeText(fixture.toString())
    }
}
