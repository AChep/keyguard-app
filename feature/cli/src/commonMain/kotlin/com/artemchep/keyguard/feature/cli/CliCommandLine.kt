package com.artemchep.keyguard.feature.cli

private const val SAFE_PUNCTUATION = "-_./:=@%+,"

private val HIDDEN_CATEGORIES = setOf(
    CharCategory.CONTROL,
    CharCategory.FORMAT,
    CharCategory.LINE_SEPARATOR,
    CharCategory.PARAGRAPH_SEPARATOR,
    CharCategory.PRIVATE_USE,
    CharCategory.UNASSIGNED,
)

/** Planes 14–16 hold tags, variation selectors and private use: nothing a command shows on purpose. */
private const val FIRST_HIDDEN_CODE_POINT = 0xE0000

private const val FIRST_SUPPLEMENTARY_CODE_POINT = 0x10000
private const val SURROGATE_BITS = 10
private const val HEX_RADIX = 16
private const val ESCAPE_DIGITS = 4

/** Bounds a command line or a path that a prompt shows, in characters. */
const val MAX_CLI_COMMAND_DISPLAY_LENGTH = 1024

/**
 * Shows a command as one line. Arguments are quoted like in a POSIX shell.
 * An argument with control, format or direction characters is shown in
 * `$'…'` quotes with those characters escaped, so an argument can not hide
 * or fake another one, and an escape can not pass for literal text.
 */
fun cliCommandLine(command: List<String>): String = command.joinToString(separator = " ", transform = ::cliArgument)

private fun cliArgument(argument: String): String = when {
    argument.isNotEmpty() && argument.all { it.isLetterOrDigit() || it in SAFE_PUNCTUATION } -> argument
    argument.indices.none(argument::isHiddenAt) -> "'" + argument.replace("'", "'\\''") + "'"
    else -> buildString {
        append("$'")
        argument.forEachIndexed { index, char ->
            when {
                argument.isHiddenAt(index) -> {
                    append("\\u")
                    append(char.code.toString(HEX_RADIX).padStart(ESCAPE_DIGITS, '0'))
                }
                char == '\\' || char == '\'' -> append('\\').append(char)
                else -> append(char)
            }
        }
        append('\'')
    }
}

private fun String.isHiddenAt(index: Int): Boolean {
    val char = this[index]
    return when {
        char.isHighSurrogate() -> {
            val low = getOrNull(index + 1)
            low == null || !low.isLowSurrogate() || codePoint(char, low) >= FIRST_HIDDEN_CODE_POINT
        }
        char.isLowSurrogate() -> {
            val high = getOrNull(index - 1)
            high == null || !high.isHighSurrogate() || codePoint(high, char) >= FIRST_HIDDEN_CODE_POINT
        }
        else -> char.category in HIDDEN_CATEGORIES
    }
}

private fun codePoint(high: Char, low: Char): Int = FIRST_SUPPLEMENTARY_CODE_POINT +
    ((high - Char.MIN_HIGH_SURROGATE) shl SURROGATE_BITS) +
    (low - Char.MIN_LOW_SURROGATE)

/**
 * Points at the CLI's own help, so the commands and flags are documented
 * in one place. A socket override is set as the variable every command reads.
 */
fun cliUsage(socketPath: String?): String = buildString {
    if (socketPath != null) {
        append("KEYGUARD_CLI_SOCKET=")
        append(cliCommandLine(listOf(socketPath)))
        append(' ')
    }
    append(cliCommandLine(listOf("keyguard", "--help")))
}

/** The arguments of the CLI that start its MCP server, with a socket override. */
fun cliMcpArguments(socketPath: String?): List<String> =
    listOf("mcp") + socketPath?.let { listOf("--socket", it) }.orEmpty()
