package com.artemchep.keyguard.core.store

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

class DatabaseSyncer(
    private val cryptoGenerator: CryptoGenerator,
) {
    sealed interface Key {
        data class User(
            val id: String,
        ) : Key

        data class Profile(
            val id: String,
        ) : Key

        data class Cipher(
            val id: String,
        ) : Key

        data class Folder(
            val id: String,
        ) : Key

        data class Collection(
            val id: String,
        ) : Key

        data class Organization(
            val id: String,
        ) : Key
    }

    private interface SimpleMutex {
        /**
         * Locks this mutex, suspending caller while the mutex is locked.
         *
         * This suspending function is cancellable. If the [Job] of the current coroutine is cancelled or completed while this
         * function is suspended, this function immediately resumes with [CancellationException].
         * There is a **prompt cancellation guarantee**. If the job was cancelled while this function was
         * suspended, it will not resume successfully. See [suspendCancellableCoroutine] documentation for low-level details.
         * This function releases the lock if it was already acquired by this function before the [CancellationException]
         * was thrown.
         *
         * Note that this function does not check for cancellation when it is not suspended.
         * Use [yield] or [CoroutineScope.isActive] to periodically check for cancellation in tight loops if needed.
         *
         * This function is fair; suspended callers are resumed in first-in-first-out order.
         *
         * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
         *        is already locked with the same token (same identity), this function throws [IllegalStateException].
         */
        suspend fun lock(owner: Any? = null)

        /**
         * Unlocks this mutex. Throws [IllegalStateException] if invoked on a mutex that is not locked or
         * was locked with a different owner token (by identity).
         *
         * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
         *        was locked with the different token (by identity), this function throws [IllegalStateException].
         */
        fun unlock(owner: Any? = null)
    }

    /**
     * Executes the given [action] under this mutex's lock.
     *
     * @param owner Optional owner token for debugging. When `owner` is specified (non-null value) and this mutex
     *        is already locked with the same token (same identity), this function throws [IllegalStateException].
     *
     * @return the return value of the action.
     */
    @OptIn(ExperimentalContracts::class)
    private suspend inline fun <T> SimpleMutex.withLock(owner: Any? = null, action: () -> T): T {
        contract {
            callsInPlace(action, InvocationKind.EXACTLY_ONCE)
        }

        lock(owner)
        try {
            return action()
        } finally {
            unlock(owner)
        }
    }

    private data class Entry(
        val key: String,
        val mutex: SimpleMutex,
    )

    private val map: MutableMap<Key, MutableList<Entry>> = mutableMapOf()

    suspend fun <T> withLock(
        vararg keys: Key,
        block: suspend () -> T,
    ): T {
        val id = cryptoGenerator.uuid()
        try {
            val mutex = synchronized(map) {
                val entry = kotlin.run {
                    val existingLocks = keys.flatMap { key -> map[key].orEmpty() }
                    val newLock: SimpleMutex =
                        object : SimpleMutex {
                            private val mutex = Mutex()

                            override suspend fun lock(owner: Any?) {
                                existingLocks.forEach { entry -> entry.mutex.lock(owner) }
                                mutex.lock(owner)
                            }

                            override fun unlock(owner: Any?) {
                                mutex.unlock(owner)
                                existingLocks.forEach { entry -> entry.mutex.unlock(owner) }
                            }
                        }
                    Entry(
                        key = id,
                        mutex = newLock,
                    )
                }

                keys.forEach { key ->
                    val list = map[key]
                    if (list != null) {
                        list += entry
                    } else {
                        val newList = mutableListOf(entry)
                        map[key] = newList
                    }
                }

                entry.mutex
            }
            return mutex.withLock {
                block()
            }
        } finally {
            synchronized(map) {
                keys.forEach { key ->
                    val list = map[key]
                    if (list != null) {
                        var i = 0
                        while (i < list.size) {
                            val el = list[i]
                            if (el.key == id) {
                                list.removeAt(i)
                            } else {
                                i++
                            }
                        }
                        if (list.isEmpty()) {
                            map.remove(key)
                        }
                    } else {
                        error("Trying to remove mutex that does not exist!")
                    }
                }
            }
        }
    }
}
