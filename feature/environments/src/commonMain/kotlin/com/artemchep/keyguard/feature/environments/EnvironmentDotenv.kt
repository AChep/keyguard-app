package com.artemchep.keyguard.feature.environments

/** Deliberately contains no source text or secret values. */
class EnvironmentDotenvException(val line: Int? = null) : IllegalArgumentException(
    if (line == null) "Invalid environment document" else "Invalid environment document at line $line",
)

object EnvironmentDotenv {
    fun parse(text: String): EnvironmentDefinition {
        if (text.encodeToByteArray().size > EnvironmentFormat.MAX_PAYLOAD_BYTES || '\u0000' in text) {
            throw EnvironmentDotenvException()
        }
        // A lone CR ends a line too, as in Node and Python dotenv.
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val definition = Parser(normalized).parse()
        try {
            require(EnvironmentResolver.fitsLimits(definition))
            require(EnvironmentResolver.validate(definition).isEmpty())
            EnvironmentFormat.encode(definition)
        } catch (_: IllegalArgumentException) {
            throw EnvironmentDotenvException()
        }
        return definition
    }

    /** Formats a definition of literal values only; references are never resolved here. */
    fun format(definition: EnvironmentDefinition): String {
        if (!EnvironmentResolver.fitsLimits(definition)) throw EnvironmentDotenvException()
        val result = EnvironmentResolver.resolve(definition) as? EnvironmentResolutionResult.Resolved
            ?: throw EnvironmentDotenvException()
        EnvironmentFormat.encode(definition)
        return format(result.snapshot)
    }

    fun format(snapshot: EnvironmentResolvedSnapshot): String =
        snapshot.variables.entries.joinToString(separator = "\n", postfix = "\n") { (name, value) ->
            "$name=${quote(value)}"
        }.takeIf { snapshot.variables.isNotEmpty() }.orEmpty()

    /**
     * Node dotenv preserves most escapes that python-dotenv decodes. Prefer raw
     * single quotes, then unquoted text, then a double-quoted form whose only
     * generated escape is CR (understood by both). Never silently alter a value.
     * Consumers must disable optional interpolation (python: interpolate=False).
     */
    private fun quote(value: String): String = when {
        canUseSingleQuotes(value) -> "'$value'"
        canUseUnquoted(value) -> value
        canUseDoubleQuotes(value) -> "\"${value.replace("\r", "\\r")}\""
        else -> throw EnvironmentDotenvException()
    }

    private fun canUseSingleQuotes(value: String): Boolean =
        value.none { it in "\r'" } && "\\\\" !in value && !value.endsWith('\\')

    private fun canUseUnquoted(value: String): Boolean {
        // Node also trims U+FEFF and Python also strips U+0085; Kotlin's trim keeps both.
        val trimmed = value.trim { it.isWhitespace() || it in "﻿\u0085" }
        val needsQuotes = value.isEmpty() || trimmed != value || value.first() in "'\"`"
        return !needsQuotes && value.none { it in "\r\n#\u2028\u2029" }
    }

    private fun canUseDoubleQuotes(value: String): Boolean {
        // Escaping CR after a backslash creates \\r, which Node and Python decode differently.
        val incompatibleEscape = value.zipWithNext().any { (a, b) -> a == '\\' && b in "\\'\"abfnrtv\r" }
        return '"' !in value && !value.endsWith('\\') && !incompatibleEscape
    }

    private class Parser(private val text: String) {
        private var offset = 0
        private var line = 1

        fun parse(): EnvironmentDefinition {
            val variables = mutableListOf<EnvironmentVariable>()
            while (offset < text.length) {
                whitespace()
                if (offset == text.length) break
                when (text[offset]) {
                    '\n' -> advance()
                    '#' -> skipLine()
                    else -> variables += variable()
                }
            }
            return EnvironmentDefinition(variables)
        }

        private fun variable(): EnvironmentVariable {
            val startLine = line
            val exportPrefix = "export"
            val exportSeparator = text.getOrNull(offset + exportPrefix.length)
            if (text.startsWith(exportPrefix, offset) && exportSeparator in listOf(' ', '\t')) {
                offset += exportPrefix.length
                whitespace()
            }
            val start = offset
            while (offset < text.length && text[offset] !in "= \t\r\n#") advance()
            val name = text.substring(start, offset)
            if (!EnvironmentResolver.isValidName(name)) throw EnvironmentDotenvException(startLine)
            whitespace()
            if (offset == text.length || text[offset] == '\n' || text[offset] == '#') {
                skipLine()
                return EnvironmentVariable(name, EnvironmentValueSource.Unset)
            }
            if (text[offset] != '=') throw EnvironmentDotenvException(startLine)
            advance()
            whitespace()
            val value = when (text.getOrNull(offset)) {
                '\'', '"' -> quoted(startLine)
                else -> unquoted()
            }
            return EnvironmentVariable(name, EnvironmentValueSource.Literal(value))
        }

        private fun quoted(startLine: Int): String {
            val quote = text[offset]
            advance()
            val value = buildString {
                var closed = false
                while (offset < text.length) {
                    val char = text[offset]
                    advance()
                    if (char == quote) {
                        closed = true
                        break
                    }
                    if (quote == '"' && char == '\\') {
                        append(escapedCharacter(startLine))
                    } else append(char)
                }
                if (!closed) throw EnvironmentDotenvException(startLine)
            }
            whitespace()
            requireLineEnd()
            skipLine()
            return value
        }

        private fun escapedCharacter(startLine: Int): String {
            val escaped = text.getOrNull(offset) ?: throw EnvironmentDotenvException(startLine)
            advance()
            return when (escaped) {
                'n' -> "\n"
                'r' -> "\r"
                't' -> "\t"
                '\\', '"', '$' -> escaped.toString()
                else -> "\\$escaped"
            }
        }

        private fun requireLineEnd() {
            if (offset < text.length && text[offset] != '\n' && text[offset] != '#') {
                throw EnvironmentDotenvException(line)
            }
        }

        private fun unquoted(): String {
            val start = offset
            while (offset < text.length && text[offset] != '\n') advance()
            val raw = text.substring(start, offset)
            val comment = raw.indices.firstOrNull { index ->
                raw[index] == '#' && (index == 0 || raw[index - 1].isWhitespace())
            }
            return raw.substring(0, comment ?: raw.length).trimEnd()
        }

        private fun whitespace() {
            while (text.getOrNull(offset)?.let { it == ' ' || it == '\t' || it == '\r' } == true) advance()
        }

        private fun skipLine() {
            while (offset < text.length && text[offset] != '\n') advance()
            if (offset < text.length) advance()
        }

        private fun advance() {
            if (text[offset] == '\n') line++
            offset++
        }
    }
}
