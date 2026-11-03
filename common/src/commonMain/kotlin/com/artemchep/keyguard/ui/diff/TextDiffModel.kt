package com.artemchep.keyguard.ui.diff

import kotlinx.collections.immutable.ImmutableList

internal data class PreparedTextDiff(val model: TextDiffModel, val patch: String)

internal enum class DiffLineEnding(val value: String) { NONE(""), LF("\n"), CRLF("\r\n") }
internal enum class DiffLineKind(val prefix: Char) { CONTEXT(' '), REMOVED('-'), ADDED('+') }

internal data class DiffSourceLine(val text: String, val ending: DiffLineEnding)
internal data class DiffLine(val kind: DiffLineKind, val source: DiffSourceLine)

internal data class DiffHunk(
    val beforeStart: Int,
    val beforeCount: Int,
    val afterStart: Int,
    val afterCount: Int,
    val lines: ImmutableList<DiffLine>,
) {
    val header get() = "@@ -$beforeStart,$beforeCount +$afterStart,$afterCount @@"
}

internal data class TextDiffModel(
    val beforeExists: Boolean,
    val afterExists: Boolean,
    val hunks: ImmutableList<DiffHunk>,
    val showLineEndings: Boolean,
    val simplified: Boolean,
) {
    /** Standard unified text, without UI labels or color escapes. Source line endings remain intact. */
    fun patch(path: String): String = buildString {
        if (hunks.isEmpty() && beforeExists == afterExists) return@buildString
        val name = quoteDiffPath(path)
        append("--- ").append(if (beforeExists) name else "/dev/null").append('\n')
        append("+++ ").append(if (afterExists) name else "/dev/null").append('\n')
        hunks.forEach { hunk ->
            append(hunk.header).append('\n')
            hunk.lines.forEach { line ->
                append(line.kind.prefix).append(line.source.text)
                if (line.source.ending == DiffLineEnding.NONE) {
                    append("\n\\ No newline at end of file\n")
                } else {
                    append(line.source.ending.value)
                }
            }
        }
    }
}

private fun quoteDiffPath(path: String): String {
    if (path.none { it.isWhitespace() || it == '"' || it == '\\' }) return path
    return buildString {
        append('"')
        path.forEach {
            append(when (it) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> it.toString()
            })
        }
        append('"')
    }
}
