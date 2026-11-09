package com.artemchep.keyguard.feature.p2psync.clock

/**
 * Issues [Hlc] stamps for one [node].
 *
 * Stamps only move forward, even if the physical clock goes backwards. Every issued stamp
 * is handed to [persist] before it is returned, so a restarted clock, created with the
 * persisted stamp as [restored], never issues a stamp twice.
 *
 * Not thread-safe: callers serialize access.
 *
 * @param physicalNow returns the physical time in milliseconds since the epoch.
 */
class HlcClock(
    val node: String,
    val physicalNow: () -> Long,
    private val persist: (Hlc) -> Unit,
    restored: Hlc? = null,
) {
    private var last: Hlc = restored?.copy(node = node) ?: Hlc(wall = 0L, counter = 0, node = node)

    /** Issues a stamp for a local event. */
    fun now(): Hlc = issue(physical = physicalNow(), floor = null)

    /**
     * Witnesses the [remote] stamp of a received event and issues a stamp above both it and
     * every stamp issued before. A remote stamp more than [MAX_FUTURE_DRIFT_MS] ahead of the
     * physical clock is never adopted: the clock then issues a stamp as if [now] was called.
     */
    fun receive(remote: Hlc): Hlc {
        val physical = physicalNow()
        return issue(physical = physical, floor = remote.takeIf { isAdoptable(it, physical) })
    }

    /**
     * Issues a stamp above [floor], a stamp of the local state such as the update stamp of a
     * roster record. A floor within [MAX_FUTURE_DRIFT_MS] of the physical clock is adopted,
     * as by [receive]. One further ahead is not: roster stamps are never drift-checked, and
     * adopting one would stamp every later entry in the future, which peers reject. The
     * stamp is then the one right after the floor, and the clock stays where it was.
     */
    fun nowAfter(floor: Hlc): Hlc {
        val physical = physicalNow()
        if (isAdoptable(floor, physical)) return issue(physical = physical, floor = floor)
        val stamp = issue(physical = physical, floor = null)
        return if (stamp > floor) stamp else floor.next(node)
    }

    private fun isAdoptable(stamp: Hlc, physical: Long): Boolean = stamp.wall - physical <= MAX_FUTURE_DRIFT_MS

    private fun issue(physical: Long, floor: Hlc?): Hlc {
        val wall = maxOf(physical, last.wall, floor?.wall ?: Long.MIN_VALUE)
        var counter = -1
        if (wall == last.wall) counter = maxOf(counter, last.counter)
        if (floor != null && wall == floor.wall) counter = maxOf(counter, floor.counter)
        val stamp = Hlc(wall = wall, counter = counter, node = node).next(node)
        persist(stamp)
        last = stamp
        return stamp
    }

    companion object {
        /** How far ahead of the local physical clock a received stamp may be. */
        const val MAX_FUTURE_DRIFT_MS: Long = 5L * 60L * 1000L
    }
}

/** The stamp of [node] right after this one; the counter rolls over into the next millisecond. */
private fun Hlc.next(node: String): Hlc = if (counter == Int.MAX_VALUE) {
    Hlc(wall = wall + 1, counter = 0, node = node)
} else {
    Hlc(wall = wall, counter = counter + 1, node = node)
}
