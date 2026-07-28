package com.artemchep.keyguard.common.util

/**
 * Returns this throwable followed by its transitive causes, guarding against
 * cycles (a cause that points back into the chain).
 */
fun Throwable.causeChain(): List<Throwable> {
    val result = ArrayList<Throwable>()
    var current: Throwable? = this
    while (current != null) {
        // Reference equality: a cause chain can legitimately contain equal-by-value
        // exceptions, but the same instance appearing twice means a cycle.
        if (result.any { it === current }) {
            break
        }
        result.add(current)
        current = current.cause
    }
    return result
}
