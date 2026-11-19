package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The pairing attempts of every session that the user hasn't seen, README
 * section 8. Each accepted `pair_commit` takes a slot until the user sees its
 * prompt, or for [BrowserProtocol.UNPROMPTED_COMMIT_WINDOW_MS]. An attempt
 * that is abandoned, replaced, or revealed without a prompt keeps its slot,
 * so that nobody tries pairing codes unseen. App-scoped: a new session
 * starts with the slots of the others.
 */
internal class BrowserPairingAttempts(
    private val timeSource: TimeSource,
) {
    /** The slot of one attempt, taken at [takenAt]. */
    class Slot(val takenAt: TimeMark)

    private val window = BrowserProtocol.UNPROMPTED_COMMIT_WINDOW_MS.milliseconds

    private val lock = SynchronizedObject()

    // Guarded by the lock.
    private val slots = mutableListOf<Slot>()

    /**
     * Takes a slot for a new attempt; `null` while
     * [BrowserProtocol.MAX_UNPROMPTED_COMMITS] are taken.
     */
    fun take(): Slot? = synchronized(lock) {
        slots.removeAll { it.takenAt.elapsedNow() >= window }
        if (slots.size < BrowserProtocol.MAX_UNPROMPTED_COMMITS) {
            Slot(timeSource.markNow()).also(slots::add)
        } else {
            null
        }
    }

    /** The user saw the prompt of the attempt of [slot]: it no longer counts. */
    fun release(slot: Slot) {
        synchronized(lock) { slots.remove(slot) }
    }
}
