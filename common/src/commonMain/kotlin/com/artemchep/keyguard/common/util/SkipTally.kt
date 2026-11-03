package com.artemchep.keyguard.common.util

import kotlin.reflect.KClass

/**
 * A tally of skip reasons, keyed by an enum.
 *
 * The reasons are an enum rather than named fields so that [totalCount] and
 * [plus] derive from what is stored, and so that the render site's exhaustive
 * `when` fails to compile for a reason with nothing to say to the user.
 *
 * Non-positive counts are not stored, so two tallies that counted the same
 * things are equal however they were built, [counted] never yields a zero row,
 * and [toString] lists only what actually happened.
 *
 * A tally also carries the titles of the items behind each reason, attached with
 * [titled] and read back with [titlesOf]. **They are an annotation, not part of
 * the tally's identity** — see [equals].
 */
class SkipTally<R : Enum<R>> private constructor(
    private val reasonType: KClass<R>,
    private val counts: Map<R, Int>,
    private val titles: Map<R, Map<String, Int>> = emptyMap(),
) {
    /** How many times [reason] fired; `0` when it never did. */
    operator fun get(reason: R): Int = counts[reason] ?: 0

    val totalCount: Int = counts.values.sum()

    val isEmpty: Boolean get() = counts.isEmpty()

    /**
     * The reasons that actually fired, in enum-declaration order — which is
     * also the order the review screen renders them in.
     */
    val counted: List<Pair<R, Int>>
        get() = counts.entries
            .sortedBy { it.key.ordinal }
            .map { it.key to it.value }

    /**
     * The titles of the items behind [reason], each mapped to how many of that
     * reason's count it accounts for. Empty when nothing was attributed.
     *
     * Best-effort, and deliberately incomplete: several reasons fire on input
     * that has no readable title at all — a non-object account entry, an item
     * whose JSON shell failed to decode, a whole account whose mapping threw.
     * The sum of these counts is therefore always `<=` [get], never `>`, and
     * [get] remains the only authority on how much was lost. A caller that
     * renders these must say so, rather than imply the list is the whole story.
     */
    fun titlesOf(reason: R): Map<String, Int> = titles[reason].orEmpty()

    operator fun plus(reason: R): SkipTally<R> = plus(reason, count = 1)

    /**
     * Adds [count] occurrences of [reason]. A non-positive [count] is ignored
     * rather than rejected, because callers compute counts by subtraction.
     */
    fun plus(reason: R, count: Int): SkipTally<R> = if (count <= 0) {
        this
    } else {
        SkipTally(reasonType, counts + (reason to get(reason) + count), titles)
    }

    operator fun plus(other: SkipTally<R>): SkipTally<R> = when {
        other.isEmpty -> this
        isEmpty -> other
        else -> {
            val mergedCounts = counts.toMutableMap()
            other.counts.forEach { (reason, count) ->
                mergedCounts[reason] = (mergedCounts[reason] ?: 0) + count
            }
            SkipTally(reasonType, mergedCounts, mergeTitles(titles, other.titles))
        }
    }

    /**
     * Attributes every reason currently counted here to [title], keeping each
     * reason's own count.
     *
     * Meant for the per-item folds, where a whole sub-tally provably belongs to
     * one vault item: one call annotates all of its reasons at once, so the
     * individual `skips += reason` sites never have to know about titles.
     *
     * Careful: this attributes every reason it can see, so calling it on a
     * running accumulator would relabel everything folded in before it. Title
     * the item's own tally, then add that.
     *
     * A blank [title] attributes nothing, so an unnamed item falls into the
     * count the review screen reports as "and N more". Naming it would mean a
     * placeholder, and this layer has no translator to build one with — the
     * export review does not render one for its ordinary rows either, so
     * inventing one only here would make the two disagree.
     */
    fun titled(title: String): SkipTally<R> {
        if (isEmpty || title.isBlank()) {
            return this
        }
        val attributed = counts.mapValues { (_, count) -> mapOf(title to count) }
        return SkipTally(reasonType, counts, mergeTitles(titles, attributed))
    }

    /**
     * Two tallies are equal when they counted the same things, **whatever their
     * titles**.
     *
     * The count is the tally's identity and the titles are a display annotation
     * over it, so a tally that knows a name is the same loss as one that does
     * not. This is load-bearing rather than incidental: the mapper suites assert
     * whole tallies to stop one cause leaking into a neighbouring reason, and
     * the round-trip harness *declares* its expectations instead of deriving
     * them. Folding titles in here would make every one of those expectations
     * restate names that no rule under test depends on.
     */
    override fun equals(other: Any?): Boolean = other is SkipTally<*> &&
        reasonType == other.reasonType &&
        counts == other.counts

    /** Consistent with [equals]: derived from the counts alone. */
    override fun hashCode(): Int = counts.hashCode()

    override fun toString(): String = counted
        .joinToString(prefix = "${reasonType.simpleName}Skips(", postfix = ")") { (reason, count) ->
            val names = titlesOf(reason)
            if (names.isEmpty()) {
                "$reason=$count"
            } else {
                "$reason=$count${names.keys.joinToString(prefix = "[", postfix = "]")}"
            }
        }

    /** Accumulates skips without copying previously attributed titles on each addition. */
    internal class Builder<R : Enum<R>>(
        private val reasonType: KClass<R>,
    ) {
        private val counts = mutableMapOf<R, Int>()
        private val titles = mutableMapOf<R, MutableMap<String, Int>>()

        fun add(reason: R, title: String? = null, count: Int = 1) {
            if (count <= 0) return

            counts[reason] = (counts[reason] ?: 0) + count
            if (!title.isNullOrBlank()) {
                val names = titles.getOrPut(reason) { mutableMapOf() }
                names[title] = (names[title] ?: 0) + count
            }
        }

        /** Merges only the incoming entries, without sharing their mutable title maps. */
        fun add(other: Builder<R>) {
            other.counts.forEach { (reason, count) ->
                counts[reason] = (counts[reason] ?: 0) + count
            }
            other.titles.forEach { (reason, names) ->
                val merged = titles.getOrPut(reason) { mutableMapOf() }
                names.forEach { (title, count) ->
                    merged[title] = (merged[title] ?: 0) + count
                }
            }
        }

        /** Takes a snapshot that subsequent additions to this builder cannot change. */
        fun build(): SkipTally<R> = SkipTally(
            reasonType = reasonType,
            counts = counts.toMap(),
            titles = titles.mapValues { (_, names) -> names.toMap() },
        )
    }

    internal companion object {
        fun <R : Enum<R>> of(
            reasonType: KClass<R>,
            entries: Array<out Pair<R, Int>>,
        ): SkipTally<R> = entries.fold(SkipTally(reasonType, emptyMap())) { acc, (reason, count) ->
            acc.plus(reason, count)
        }

        private fun <R : Enum<R>> mergeTitles(
            base: Map<R, Map<String, Int>>,
            extra: Map<R, Map<String, Int>>,
        ): Map<R, Map<String, Int>> = when {
            extra.isEmpty() -> base
            base.isEmpty() -> extra
            else -> {
                val merged = base.toMutableMap()
                extra.forEach { (reason, names) ->
                    val existing = merged[reason]
                    merged[reason] = if (existing == null) {
                        names
                    } else {
                        val combined = existing.toMutableMap()
                        names.forEach { (title, count) ->
                            combined[title] = (combined[title] ?: 0) + count
                        }
                        combined
                    }
                }
                merged
            }
        }
    }
}
