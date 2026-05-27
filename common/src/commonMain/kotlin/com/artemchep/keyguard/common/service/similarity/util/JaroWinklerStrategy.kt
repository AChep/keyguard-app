package com.artemchep.keyguard.common.service.similarity.util

object JaroWinklerStrategy {
    private const val DEFAULT_SCALING_FACTOR = 0.1
    private const val MAX_PREFIX_LENGTH = 4

    fun score(first: String, second: String): Double {
        val normalizedFirst = first.lowercase()
        val normalizedSecond = second.lowercase()
        val (shorter, longer) = if (normalizedFirst.length > normalizedSecond.length) {
            normalizedSecond to normalizedFirst
        } else {
            normalizedFirst to normalizedSecond
        }

        val jaro = jaroScore(shorter, longer)
        if (jaro == 0.0) {
            return 0.0
        }

        val prefixLength = commonPrefixLength(shorter, longer)
        return jaro + DEFAULT_SCALING_FACTOR * prefixLength * (1.0 - jaro)
    }

    private fun jaroScore(
        shorter: String,
        longer: String,
    ): Double {
        if (shorter.isEmpty() || longer.isEmpty()) {
            return 0.0
        }

        val matchingRange = shorter.length / 2 + 1
        val shorterMatches = matchingCharactersWithin(
            source = shorter,
            target = longer,
            limit = matchingRange,
        )
        val longerMatches = matchingCharactersWithin(
            source = longer,
            target = shorter,
            limit = matchingRange,
        )

        if (shorterMatches.isEmpty() || longerMatches.isEmpty()) {
            return 0.0
        }
        if (shorterMatches.length != longerMatches.length) {
            return 0.0
        }

        val transpositions = transpositions(shorterMatches, longerMatches)
        val matches = shorterMatches.length.toDouble()
        val sourceRatio = matches / shorter.length
        val targetRatio = matches / longer.length
        val transpositionRatio = (matches - transpositions) / matches
        return (sourceRatio + targetRatio + transpositionRatio) / 3.0
    }

    private fun matchingCharactersWithin(
        source: String,
        target: String,
        limit: Int,
    ): String {
        val matches = StringBuilder()
        val targetMatches = BooleanArray(target.length)
        for (i in source.indices) {
            val start = maxOf(0, i - limit)
            val end = minOf(i + limit, target.length)
            for (j in start until end) {
                if (!targetMatches[j] && source[i] == target[j]) {
                    matches.append(source[i])
                    targetMatches[j] = true
                    break
                }
            }
        }
        return matches.toString()
    }

    private fun transpositions(
        first: String,
        second: String,
    ): Int {
        var transpositions = 0
        for (i in first.indices) {
            if (first[i] != second[i]) {
                transpositions++
            }
        }
        return transpositions / 2
    }

    private fun commonPrefixLength(
        first: String,
        second: String,
    ): Int {
        val maxPrefixLength = minOf(MAX_PREFIX_LENGTH, first.length, second.length)
        for (i in 0 until maxPrefixLength) {
            if (first[i] != second[i]) {
                return i
            }
        }
        return maxPrefixLength
    }
}
