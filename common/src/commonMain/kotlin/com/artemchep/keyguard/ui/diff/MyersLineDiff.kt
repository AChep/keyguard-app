package com.artemchep.keyguard.ui.diff

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Bounded Myers search. Each saved frontier contains only its visited diagonals, not the whole file. */
internal class MyersLineDiff(
    private val before: List<DiffSourceLine>,
    private val after: List<DiffSourceLine>,
    private val context: CoroutineContext,
    private var remainingWork: Int,
) {
    fun calculate(): List<DiffLine>? {
        val trace = mutableListOf<IntArray>()
        search@ for (depth in 0..(before.size + after.size)) {
            context.ensureActive()
            if (remainingWork < depth + 1) break
            val frontier = IntArray(2 * depth + 1)
            for (diagonal in -depth..depth step 2) {
                val start = startX(trace.lastOrNull(), depth, diagonal)
                val x = followMatches(start, diagonal) ?: break@search
                val y = x - diagonal
                frontier[diagonal + depth] = x
                if (x == before.size && y == after.size) {
                    trace += frontier
                    return backtrack(trace)
                }
            }
            trace += frontier
        }
        return null
    }

    private fun followMatches(start: Int, diagonal: Int): Int? {
        remainingWork -= 1 // Visiting this diagonal also consumes work when it matches no lines.
        var x = start
        var y = x - diagonal
        while (x < before.size && y < after.size && before[x] == after[y]) {
            if (--remainingWork < 0) return null
            if (remainingWork % CANCELLATION_INTERVAL == 0) context.ensureActive()
            x += 1
            y += 1
        }
        return x.takeIf { remainingWork >= 0 }
    }

    private fun startX(previous: IntArray?, depth: Int, diagonal: Int): Int {
        if (previous == null) return 0
        return if (fromAddition(previous, depth, diagonal)) {
            previous[diagonal + depth]
        } else {
            previous[diagonal + depth - 2] + 1
        }
    }

    /** Whether the path reaching [diagonal] at [depth] extends the previous frontier's next diagonal. */
    private fun fromAddition(previous: IntArray, depth: Int, diagonal: Int) = diagonal == -depth ||
        (diagonal != depth && previous[diagonal + depth - 2] < previous[diagonal + depth])

    private fun backtrack(trace: List<IntArray>): List<DiffLine> {
        var x = before.size
        var y = after.size
        val reversed = mutableListOf<DiffLine>()
        for (depth in trace.lastIndex downTo 1) {
            context.ensureActive()
            val previous = trace[depth - 1]
            val diagonal = x - y
            val fromAddition = fromAddition(previous, depth, diagonal)
            val previousDiagonal = diagonal + if (fromAddition) 1 else -1
            val previousX = previous[previousDiagonal + depth - 1]
            val previousY = previousX - previousDiagonal
            while (x > previousX && y > previousY) {
                if (x % CANCELLATION_INTERVAL == 0) context.ensureActive()
                reversed += DiffLine(DiffLineKind.CONTEXT, before[--x])
                y -= 1
            }
            reversed += if (fromAddition) {
                DiffLine(DiffLineKind.ADDED, after[--y])
            } else {
                DiffLine(DiffLineKind.REMOVED, before[--x])
            }
        }
        while (x > 0 && y > 0) {
            reversed += DiffLine(DiffLineKind.CONTEXT, before[--x])
            y -= 1
        }
        return reversed.asReversed()
    }

    private companion object {
        const val CANCELLATION_INTERVAL = 256
    }
}
