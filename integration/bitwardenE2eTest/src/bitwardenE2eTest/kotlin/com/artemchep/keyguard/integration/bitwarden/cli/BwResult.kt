package com.artemchep.keyguard.integration.bitwarden.cli

import com.artemchep.keyguard.integration.bitwarden.TestKdf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.PrintStream

/** What a command prints: readable [lines], then [json] as the last line. */
internal class BwResult(
    val lines: List<String>,
    val json: JsonObject,
) {
    fun print(out: PrintStream, jsonOnly: Boolean) {
        if (!jsonOnly) {
            lines.forEach(out::println)
        }
        out.println(json.toString())
        out.flush()
    }
}

/** The JSON line of a successful command against [server]; null for a command without one. */
internal fun bwJson(server: String?, content: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("ok", true)
    put("server", server)
    content()
}

internal fun TestKdf.toJson(): JsonObject = buildJsonObject {
    when (this@toJson) {
        is TestKdf.Pbkdf2 -> {
            put("type", "pbkdf2")
            put("iterations", iterations)
        }

        is TestKdf.Argon2id -> {
            put("type", "argon2id")
            put("iterations", iterations)
            put("memoryMib", memoryMib)
            put("parallelism", parallelism)
        }
    }
}

internal fun TestKdf.describe(): String = when (this) {
    is TestKdf.Pbkdf2 -> "PBKDF2-SHA256, $iterations iterations"
    is TestKdf.Argon2id -> "Argon2id, $iterations iterations, $memoryMib MiB, $parallelism lanes"
}

/** "1 login", "2 logins". */
internal fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
