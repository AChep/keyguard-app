package com.artemchep.keyguard.android.autofill.v2.util

/**
 * Aho-Corasick multi-pattern substring matching automaton.
 *
 * Builds a trie from a set of patterns (each tagged with a bitmask),
 * computes failure links, and searches text in
 * O(text_length + total_pattern_length) time regardless of the number
 * of patterns.
 *
 * Each pattern is associated with a [Long] bitmask tag. When [match]
 * scans a text, it returns the OR of all tags whose patterns appear as
 * substrings anywhere in the text.
 */
class AhoCorasickAutomaton private constructor(
    private val goto: Array<HashMap<Char, Int>>,
    private val fail: IntArray,
    private val output: LongArray,
) {
    /**
     * Scans [text] through the automaton and returns a bitmask of all
     * keyword tags that were matched as substrings.
     */
    fun match(text: String): Long {
        var result = 0L
        var state = 0
        for (ch in text) {
            while (state != 0 && ch !in goto[state]) {
                state = fail[state]
            }
            state = goto[state][ch] ?: 0
            result = result or output[state]
        }
        return result
    }

    /**
     * Builder that accumulates patterns with their bitmask tags and
     * constructs the automaton with failure links.
     */
    class Builder {
        private val goto = arrayListOf(HashMap<Char, Int>())
        private val outputList = arrayListOf(0L)

        /**
         * Adds a [pattern] string tagged with [tag]. If the same pattern
         * is added multiple times with different tags, the tags are OR-ed.
         */
        fun addPattern(
            pattern: String,
            tag: Long,
        ): Builder {
            var state = 0
            for (ch in pattern) {
                state =
                    goto[state].getOrPut(ch) {
                        goto.add(HashMap())
                        outputList.add(0L)
                        goto.size - 1
                    }
            }
            outputList[state] = outputList[state] or tag
            return this
        }

        /**
         * Convenience: registers every string in [keywords] with the same [tag].
         */
        fun addAll(
            keywords: List<String>,
            tag: Long,
        ): Builder {
            for (kw in keywords) addPattern(kw, tag)
            return this
        }

        /**
         * Builds the automaton by computing BFS failure links and
         * propagating output (dictionary suffix links) along the
         * failure chain.
         */
        fun build(): AhoCorasickAutomaton {
            val size = goto.size
            val fail = IntArray(size)
            val output = outputList.toLongArray()

            // BFS to compute failure links.
            val queue = ArrayDeque<Int>()

            // Depth-1 nodes: failure link points to root.
            for (child in goto[0].values) {
                fail[child] = 0
                queue.addLast(child)
            }

            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                for ((ch, v) in goto[u]) {
                    queue.addLast(v)
                    var f = fail[u]
                    while (f != 0 && ch !in goto[f]) {
                        f = fail[f]
                    }
                    fail[v] = goto[f][ch] ?: 0
                    if (fail[v] == v) fail[v] = 0 // avoid self-loop
                    // Propagate output from failure chain (dictionary suffix links).
                    output[v] = output[v] or output[fail[v]]
                }
            }

            return AhoCorasickAutomaton(
                goto = goto.toTypedArray(),
                fail = fail,
                output = output,
            )
        }
    }
}
