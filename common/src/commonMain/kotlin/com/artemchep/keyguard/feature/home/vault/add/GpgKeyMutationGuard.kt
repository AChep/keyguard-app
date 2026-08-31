package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.model.withGpgKeyMaterial
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Publishes GPG key mutations only while they still own the state snapshot they started from.
 *
 * The generation makes the guard robust against ABA changes: an asynchronous result remains stale
 * even if newer edits eventually restore a value equal to its original snapshot.
 */
internal class GpgKeyMutationGuard(
    private val sink: MutableStateFlow<GeneratedGpgKey>,
) {
    private val lock = SynchronizedObject()
    private var generation = 0L

    data class Snapshot internal constructor(
        val key: GeneratedGpgKey,
        internal val generation: Long,
    )

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            key = sink.value,
            generation = generation,
        )
    }

    fun isCurrent(snapshot: Snapshot): Boolean = synchronized(lock) {
        snapshot.generation == generation && snapshot.key == sink.value
    }

    fun commitReplacement(
        snapshot: Snapshot,
        value: GeneratedGpgKey,
    ): Boolean = commit(snapshot) {
        value
    }

    fun commitExpiration(
        snapshot: Snapshot,
        material: GpgKeyMaterial,
    ): Boolean = commit(snapshot) { current ->
        current.withGpgKeyMaterial(material)
    }

    fun replace(value: GeneratedGpgKey) {
        synchronized(lock) {
            while (true) {
                val current = sink.value
                if (value == current) {
                    return@synchronized
                }
                if (sink.compareAndSet(current, value)) {
                    generation += 1L
                    return@synchronized
                }
            }
        }
    }

    private fun commit(
        snapshot: Snapshot,
        transform: (GeneratedGpgKey) -> GeneratedGpgKey,
    ): Boolean = synchronized(lock) {
        if (snapshot.generation != generation || snapshot.key != sink.value) {
            return@synchronized false
        }

        val updated = transform(snapshot.key)
        if (!sink.compareAndSet(snapshot.key, updated)) {
            return@synchronized false
        }
        // Completing an operation always advances the generation so that only
        // one asynchronous result can commit ownership of a given snapshot.
        generation += 1L
        true
    }
}
