package com.artemchep.keyguard.ui.diff

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

private const val DEFAULT_DIFF_WORK = 1_000_000

internal suspend fun buildTextDiff(
    before: String?,
    after: String?,
    contextLines: Int = 3,
    workLimit: Int = DEFAULT_DIFF_WORK,
): TextDiffModel {
    require(contextLines >= 0 && workLimit >= 0)
    coroutineContext.ensureActive()
    if (before == after) return TextDiffModel(before != null, after != null, persistentListOf(), false, false)
    val old = splitDiffLines(before.orEmpty())
    val new = splitDiffLines(after.orEmpty())
    val (prefix, suffix) = commonEdges(old, new)
    val oldMiddle = old.subList(prefix, old.size - suffix)
    val newMiddle = new.subList(prefix, new.size - suffix)
    val middle = when {
        oldMiddle.isEmpty() -> newMiddle.map { DiffLine(DiffLineKind.ADDED, it) }
        newMiddle.isEmpty() -> oldMiddle.map { DiffLine(DiffLineKind.REMOVED, it) }
        else -> MyersLineDiff(oldMiddle, newMiddle, coroutineContext, workLimit).calculate()
    }
    val lines = buildList {
        old.take(prefix).forEach { add(DiffLine(DiffLineKind.CONTEXT, it)) }
        if (middle == null) {
            oldMiddle.forEach { add(DiffLine(DiffLineKind.REMOVED, it)) }
            newMiddle.forEach { add(DiffLine(DiffLineKind.ADDED, it)) }
        } else {
            addAll(orderReplacements(middle))
        }
        old.takeLast(suffix).forEach { add(DiffLine(DiffLineKind.CONTEXT, it)) }
    }
    coroutineContext.ensureActive()
    return TextDiffModel(
        beforeExists = before != null,
        afterExists = after != null,
        hunks = diffHunks(lines, contextLines).toImmutableList(),
        showLineEndings = hasChangedLineEndings(lines),
        simplified = middle == null,
    )
}

private suspend fun commonEdges(old: List<DiffSourceLine>, new: List<DiffSourceLine>): Pair<Int, Int> {
    var prefix = 0
    while (prefix < minOf(old.size, new.size) && old[prefix] == new[prefix]) {
        coroutineContext.ensureActive()
        prefix += 1
    }
    var suffix = 0
    while (suffix < minOf(old.size, new.size) - prefix &&
        old[old.lastIndex - suffix] == new[new.lastIndex - suffix]
    ) {
        coroutineContext.ensureActive()
        suffix += 1
    }
    return prefix to suffix
}

private suspend fun splitDiffLines(text: String): List<DiffSourceLine> = buildList {
    var start = 0
    while (start < text.length) {
        coroutineContext.ensureActive()
        val end = text.indexOf('\n', start)
        if (end == -1) {
            add(DiffSourceLine(text.substring(start), DiffLineEnding.NONE))
            break
        }
        val crlf = end > start && text[end - 1] == '\r'
        add(DiffSourceLine(
            text.substring(start, if (crlf) end - 1 else end),
            if (crlf) DiffLineEnding.CRLF else DiffLineEnding.LF,
        ))
        start = end + 1
    }
}

/** Keep each replacement readable: old lines first, then new lines, preserving each side's order. */
private fun orderReplacements(lines: List<DiffLine>): List<DiffLine> = buildList {
    var index = 0
    while (index < lines.size) {
        if (lines[index].kind == DiffLineKind.CONTEXT) {
            add(lines[index++])
        } else {
            val start = index
            while (index < lines.size && lines[index].kind != DiffLineKind.CONTEXT) index += 1
            val block = lines.subList(start, index)
            addAll(block.filter { it.kind == DiffLineKind.REMOVED })
            addAll(block.filter { it.kind == DiffLineKind.ADDED })
        }
    }
}

private suspend fun diffHunks(lines: List<DiffLine>, context: Int): List<DiffHunk> {
    val ranges = mutableListOf<IntRange>()
    lines.forEachIndexed { index, line ->
        coroutineContext.ensureActive()
        if (line.kind != DiffLineKind.CONTEXT) {
            val start = (index - context).coerceAtLeast(0)
            val end = (index + context).coerceAtMost(lines.lastIndex)
            val last = ranges.lastOrNull()
            if (last != null && start <= last.last + 1) {
                ranges[ranges.lastIndex] = last.first..end
            } else {
                ranges += start..end
            }
        }
    }
    var cursor = 0
    var oldLine = 1
    var newLine = 1
    return ranges.map { range ->
        while (cursor < range.first) {
            if (lines[cursor].kind != DiffLineKind.ADDED) oldLine += 1
            if (lines[cursor].kind != DiffLineKind.REMOVED) newLine += 1
            cursor += 1
        }
        val chunk = lines.subList(range.first, range.last + 1)
        val oldCount = chunk.count { it.kind != DiffLineKind.ADDED }
        val newCount = chunk.count { it.kind != DiffLineKind.REMOVED }
        DiffHunk(
            beforeStart = if (oldCount == 0) oldLine - 1 else oldLine,
            beforeCount = oldCount,
            afterStart = if (newCount == 0) newLine - 1 else newLine,
            afterCount = newCount,
            lines = chunk.toImmutableList(),
        )
    }
}

private fun hasChangedLineEndings(lines: List<DiffLine>): Boolean {
    val removed = lines.filter { it.kind == DiffLineKind.REMOVED }
        .groupBy({ it.source.text }, { it.source.ending })
    return lines.any { line ->
        line.kind == DiffLineKind.ADDED &&
            removed[line.source.text]?.any { it != line.source.ending } == true
    }
}
