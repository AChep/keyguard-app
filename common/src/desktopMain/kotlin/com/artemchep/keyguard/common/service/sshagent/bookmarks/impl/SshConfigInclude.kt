package com.artemchep.keyguard.common.service.sshagent.bookmarks.impl

import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

private const val SSH_CONFIG_INCLUDE_KEYWORD = "include"

/** OpenSSH stops reading includes at this depth. */
private const val SSH_CONFIG_INCLUDE_MAX_DEPTH = 16

private val SSH_CONFIG_GLOB_CHARS = charArrayOf('*', '?')

private val WINDOWS_ABSOLUTE_PATH_REGEX = Regex("^[A-Za-z]:[\\\\/]")

/**
 * Whether the user's SSH configuration [text] includes the [file] for every
 * host: by its path or by a pattern, directly or through the files that it
 * includes. As in the setup, an `Include` after a `Host` or a `Match` line
 * applies only to some hosts, so it doesn't count.
 */
internal fun sshConfigIncludes(
    text: String,
    file: Path,
    userHome: Path,
): Boolean = SshConfigIncludeSearch(
    target = file.toAbsolutePath().normalize(),
    userHome = userHome,
).includes(text, depth = 0)

private class SshConfigIncludeSearch(
    private val target: Path,
    private val userHome: Path,
) {
    private val visited = mutableSetOf<Path>()

    fun includes(
        text: String,
        depth: Int,
    ): Boolean = text
        .lineSequence()
        .map { line -> line to sshConfigKeyword(line) }
        .takeWhile { (_, keyword) ->
            !keyword.equals("host", ignoreCase = true) &&
                !keyword.equals("match", ignoreCase = true)
        }
        .any { (line, keyword) ->
            keyword.equals(SSH_CONFIG_INCLUDE_KEYWORD, ignoreCase = true) &&
                sshConfigArguments(line, keyword).any { includesArgument(it, depth) }
        }

    private fun includesArgument(
        argument: String,
        depth: Int,
    ): Boolean {
        val path = sshConfigIncludePath(argument, userHome)
        val nested = depth + 1 < SSH_CONFIG_INCLUDE_MAX_DEPTH
        return sshConfigIncludeMatches(path, target) ||
            nested && sshConfigIncludeFiles(path).any { file ->
                visited.add(file) &&
                    readOrNull(file)?.let { includes(it, depth + 1) } == true
            }
    }

    // A file that OpenSSH can't read fails the login anyway.
    private fun readOrNull(file: Path): String? = try {
        Files.readString(file)
    } catch (_: IOException) {
        null
    }
}

private fun sshConfigKeyword(line: String): String = line
    .trim()
    .takeWhile { !it.isWhitespace() && it != '=' }

/** The arguments of the line that starts with the [keyword]. */
private fun sshConfigArguments(
    line: String,
    keyword: String,
): List<String> {
    val arguments = line
        .trim()
        .drop(keyword.length)
        .trimStart()
        .removePrefix("=")
    return splitSshConfigArguments(arguments)
}

/**
 * Splits the arguments as OpenSSH does: quotes group words, a backslash
 * escapes only a quote, a backslash or a space, and an unquoted `#` at
 * the start of an argument starts a comment.
 */
private fun splitSshConfigArguments(text: String): List<String> {
    val arguments = mutableListOf<String>()
    var i = 0
    while (true) {
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] == '#') return arguments
        val (argument, end) = readSshConfigArgument(text, i)
            // OpenSSH rejects a line with an unterminated quote.
            ?: return emptyList()
        arguments += argument
        i = end
    }
}

/**
 * Reads the argument that starts at [start], and the index after it;
 * `null` if its quote is unterminated.
 */
private fun readSshConfigArgument(
    text: String,
    start: Int,
): Pair<String, Int>? {
    val argument = StringBuilder()
    var quote: Char? = null
    var i = start
    while (i < text.length) {
        val c = text[i]
        when {
            isSshConfigEscape(text, i, quote) -> argument.append(text[++i])
            quote == null && c.isWhitespace() -> break
            quote == null && (c == '"' || c == '\'') -> quote = c
            c == quote -> quote = null
            else -> argument.append(c)
        }
        i++
    }
    return (argument.toString() to i).takeIf { quote == null }
}

private fun isSshConfigEscape(
    text: String,
    i: Int,
    quote: Char?,
): Boolean {
    val next = text.getOrNull(i + 1)
    return text[i] == '\\' &&
        (next == '\'' || next == '"' || next == '\\' || (quote == null && next == ' '))
}

/**
 * The path that an `Include` [argument] names. OpenSSH expands a leading `~/`,
 * and reads other relative paths of the user's configuration from `~/.ssh`.
 */
private fun sshConfigIncludePath(
    argument: String,
    userHome: Path,
): String = when {
    argument.startsWith("~/") -> "$userHome/${argument.substring(2)}"
    argument.startsWith('/') || WINDOWS_ABSOLUTE_PATH_REGEX.containsMatchIn(argument) -> argument
    else -> "${userHome.resolve(".ssh")}/$argument"
}

/** Whether the [path] names the [target], which doesn't have to exist yet. */
private fun sshConfigIncludeMatches(
    path: String,
    target: Path,
): Boolean {
    if (path.any { it in SSH_CONFIG_GLOB_CHARS }) {
        return sshConfigGlobRegex(path.withSlashes())
            .matches(target.toString().withSlashes())
    }
    return try {
        Path.of(path).normalize() == target
    } catch (_: InvalidPathException) {
        false
    }
}

/** The existing files that the [path] names. */
private fun sshConfigIncludeFiles(path: String): List<Path> = try {
    val glob = path.indexOfFirst { it in SSH_CONFIG_GLOB_CHARS }
    if (glob < 0) {
        listOf(Path.of(path).toAbsolutePath().normalize())
            .filter { Files.isRegularFile(it) }
    } else {
        // Walk from the directory before the first pattern,
        // as deep as the pattern has directories.
        val pattern = path.withSlashes()
        val root = pattern.substring(0, glob).substringBeforeLast('/')
        val depth = pattern.substring(root.length).count { it == '/' }
        val regex = sshConfigGlobRegex(pattern)
        Files.walk(Path.of(root.ifEmpty { "/" }), depth, FileVisitOption.FOLLOW_LINKS).use { paths ->
            paths
                .filter { regex.matches(it.toString().withSlashes()) && Files.isRegularFile(it) }
                .map { it.toAbsolutePath().normalize() }
                .toList()
        }
    }
} catch (_: IOException) {
    emptyList()
} catch (_: UncheckedIOException) {
    emptyList()
} catch (_: InvalidPathException) {
    emptyList()
}

/**
 * Matches `*` and `?` as OpenSSH's glob does: never across a `/`,
 * and never a leading `.` of a name.
 */
private fun sshConfigGlobRegex(glob: String): Regex = glob
    .split('/')
    .joinToString("/") { name ->
        val pattern = name
            .split('*')
            .joinToString("[^/]*") { part ->
                part
                    .split('?')
                    .joinToString("[^/]") { Regex.escape(it) }
            }
        if (name.startsWith('*') || name.startsWith('?')) "(?!\\.)$pattern" else pattern
    }
    .let(::Regex)

private fun String.withSlashes(): String =
    if (File.separatorChar == '\\') replace('\\', '/') else this
