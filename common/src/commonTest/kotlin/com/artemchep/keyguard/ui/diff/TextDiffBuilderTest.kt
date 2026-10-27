package com.artemchep.keyguard.ui.diff

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextDiffBuilderTest {
    @Test
    fun `creation removal and empty files retain file identity`() = runTest {
        val created = buildTextDiff(null, "Host *\n  IdentityAgent keyguard\n")
        assertEquals("@@ -0,0 +1,2 @@", created.hunks.single().header)
        assertTrue(created.hunks.single().lines.all { it.kind == DiffLineKind.ADDED })
        assertEquals(
            "--- /dev/null\n+++ config\n@@ -0,0 +1,2 @@\n+Host *\n+  IdentityAgent keyguard\n",
            created.patch("config"),
        )
        val removed = buildTextDiff("one\ntwo\n", null)
        assertEquals("@@ -1,2 +0,0 @@", removed.hunks.single().header)
        assertTrue(removed.patch("config").startsWith("--- config\n+++ /dev/null\n"))
        val empty = buildTextDiff(null, "")
        assertTrue(empty.hunks.isEmpty())
        assertFalse(empty.beforeExists)
        assertTrue(empty.afterExists)
        assertEquals("--- /dev/null\n+++ empty\n", empty.patch("empty"))
        assertFalse(buildTextDiff("", null).afterExists)
        assertEquals("", buildTextDiff("same\n", "same\n").patch("config"))
    }

    @Test
    fun `replacement preserves whitespace and emits removals before additions`() = runTest {
        val before = "# profile\n\texport OLD=1  \n# end\n"
        val after = "# profile\n\texport NEW=2\n# end\n"
        val diff = buildTextDiff(before, after)
        assertEquals(
            listOf(DiffLineKind.CONTEXT, DiffLineKind.REMOVED, DiffLineKind.ADDED, DiffLineKind.CONTEXT),
            diff.hunks.single().lines.map { it.kind },
        )
        assertTrue(diff.patch("profile").contains("-\texport OLD=1  \n+\texport NEW=2\n"))
        assertEquals(after, applyHunks(before, diff))
    }

    @Test
    fun `three context lines bound distant hunks and adjacent context merges`() = runTest {
        val before = (1..30).joinToString("\n", postfix = "\n") { "line $it" }
        val separated = before.replace("line 5\n", "changed 5\n").replace("line 25\n", "changed 25\n")
        val diff = buildTextDiff(before, separated)
        assertEquals(listOf("@@ -2,7 +2,7 @@", "@@ -22,7 +22,7 @@"), diff.hunks.map { it.header })
        assertFalse(diff.patch("file").contains("line 15"))
        assertEquals(separated, applyHunks(before, diff))
        val adjacent = before.replace("line 5\n", "changed 5\n").replace("line 12\n", "changed 12\n")
        assertEquals(1, buildTextDiff(before, adjacent).hunks.size)
    }

    @Test
    fun `hunk positions account for earlier insertions and deletions`() = runTest {
        val before = (1..30).joinToString("\n", postfix = "\n") { "line $it" }
        val after = "new\n" + before.replace("line 25\n", "")
        val diff = buildTextDiff(before, after)
        assertEquals(listOf("@@ -1,3 +1,4 @@", "@@ -22,7 +23,6 @@"), diff.hunks.map { it.header })
        assertEquals(after, applyHunks(before, diff))
        val noContext = buildTextDiff("a\nb\n", "a\nx\nb\n", contextLines = 0)
        assertEquals("@@ -1,0 +2,1 @@", noContext.hunks.single().header)
    }

    @Test
    fun `CRLF and final newline changes remain visible and copy exactly`() = runTest {
        val diff = buildTextDiff("a\r\nb\r\n", "a\nb\r\n")
        assertTrue(diff.showLineEndings)
        assertTrue(diff.patch("file").contains("-a\r\n+a\n b\r\n"))
        assertEquals("a\nb\r\n", applyHunks("a\r\nb\r\n", diff))
        val eof = buildTextDiff("last", "last\n")
        assertEquals(
            "--- file\n+++ file\n@@ -1,1 +1,1 @@\n-last\n\\ No newline at end of file\n+last\n",
            eof.patch("file"),
        )
        assertEquals("last", applyHunks("last\n", buildTextDiff("last\n", "last")))
    }

    @Test
    fun `budget exhaustion keeps a complete correct block replacement`() = runTest {
        val before = "prefix\nold\nkept\nend\n"
        val after = "prefix\nnew\nkept\nmore\nend\n"
        val diff = buildTextDiff(before, after, workLimit = 0)
        assertTrue(diff.simplified)
        assertEquals(after, applyHunks(before, diff))
        val large = "unchanged\n".repeat(100_000)
        val smallChange = buildTextDiff(large + "old\n", large + "new\n")
        assertEquals(5, smallChange.hunks.single().lines.size)
        assertFalse(smallChange.simplified)
    }

    @Test
    fun `repeated lines produce lossless minimal edits`() = runTest {
        val random = Random(42)
        repeat(300) {
            val old = List(random.nextInt(20)) { "${random.nextInt(4)}\n" }
            val new = List(random.nextInt(20)) { "${random.nextInt(4)}\n" }
            val before = old.joinToString("")
            val after = new.joinToString("")
            val diff = buildTextDiff(before, after)
            assertEquals(after, applyHunks(before, diff))
            val changed = diff.hunks.sumOf { h -> h.lines.count { it.kind != DiffLineKind.CONTEXT } }
            assertEquals(minimalEditCount(old, new), changed)
        }
    }

    @Test
    fun `cancelled work stops and filenames cannot inject patch headers`() = runTest {
        var cancelled = false
        val job = launch {
            cancel()
            try {
                buildTextDiff("a", "b")
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        job.join()
        assertTrue(cancelled)
        assertTrue(buildTextDiff(null, "value\n").patch("a\nb\t\"c").startsWith(
            "--- /dev/null\n+++ \"a\\nb\\t\\\"c\"\n",
        ))
    }
}

/** Apply hunks using their original positions, checking every context/removal against the input. */
private fun applyHunks(before: String, diff: TextDiffModel): String {
    val original = Regex("[^\\n]*\\n|[^\\n]+$").findAll(before).map { it.value }.toList()
    var cursor = 0
    return buildString {
        diff.hunks.forEach { hunk ->
            val start = if (hunk.beforeCount == 0) hunk.beforeStart else hunk.beforeStart - 1
            while (cursor < start) append(original[cursor++])
            hunk.lines.forEach { line ->
                val raw = line.source.text + line.source.ending.value
                if (line.kind != DiffLineKind.ADDED) assertEquals(raw, original[cursor++])
                if (line.kind != DiffLineKind.REMOVED) append(raw)
            }
        }
        while (cursor < original.size) append(original[cursor++])
    }
}

/** Small independent dynamic-programming oracle for the randomized Myers cases. */
private fun minimalEditCount(old: List<String>, new: List<String>): Int {
    var previous = IntArray(new.size + 1) { it }
    old.forEachIndexed { index, line ->
        val next = IntArray(new.size + 1)
        next[0] = index + 1
        new.forEachIndexed { column, value ->
            next[column + 1] = if (line == value) previous[column] else minOf(next[column], previous[column + 1]) + 1
        }
        previous = next
    }
    return previous.last()
}
