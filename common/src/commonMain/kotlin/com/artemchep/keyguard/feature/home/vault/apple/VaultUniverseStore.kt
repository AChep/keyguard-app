package com.artemchep.keyguard.feature.home.vault.apple

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.SharedFlow

/** Key for sessions that can share an item universe and row cache. */
internal data class UniverseKey(
    val mode: Int,
    val trash: Int,
    val archive: Int,
    /** A canonical string form of the base filter; `""` = unfiltered. */
    val filterKey: String,
    val searchByPassword: Boolean,
)

/** Shared universe flow paired with the row cache used to scan it. */
internal class UniverseHandle<T>(
    val key: UniverseKey,
    val universe: SharedFlow<T>,
    val rowCache: VaultRowCache,
)

/** Thread-safe refcounted store of one handle per live [UniverseKey]. */
internal class VaultUniverseStore<H : Any>(
    /** Injectable for tests; defaults to the repo's plain println idiom. */
    private val log: (String) -> Unit = { message -> println(message) },
) {
    private val lock = SynchronizedObject()

    private val entries = HashMap<UniverseKey, Entry<H>>()

    private class Entry<H : Any>(
        val handle: H,
        var refCount: Int,
    )

    /** Acquires [key], creating its handle once and incrementing its refcount. */
    fun acquire(
        key: UniverseKey,
        create: () -> H,
    ): H = synchronized(lock) {
        val entry = entries.getOrPut(key) {
            Entry(
                handle = create(),
                refCount = 0,
            )
        }
        entry.refCount += 1
        entry.handle
    }

    /** Releases [key], returning `true` when the last reference was removed. */
    fun release(
        key: UniverseKey,
    ): Boolean = synchronized(lock) {
        val entry = entries[key]
        if (entry == null) {
            log("[E]/VaultUniverseStore: unbalanced release of $key, no live handle!")
            return@synchronized false
        }
        entry.refCount -= 1
        if (entry.refCount <= 0) {
            entries.remove(key)
            true
        } else {
            false
        }
    }

    /** The current refcount of [key]; `0` when no handle is live. */
    fun refCountOf(
        key: UniverseKey,
    ): Int = synchronized(lock) {
        entries[key]?.refCount ?: 0
    }

    /** The number of live handles. */
    fun size(): Int = synchronized(lock) {
        entries.size
    }
}
