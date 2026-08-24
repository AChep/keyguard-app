package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.model.withGpgKeyMaterial
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.common.service.gpgagent.normalizeGpgFingerprint
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

    fun commitImport(
        snapshot: Snapshot,
        imported: GeneratedGpgKey,
    ): Boolean = commit(snapshot) { current ->
        current.mergeGpgKeyImport(imported)
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

private fun GeneratedGpgKey.mergeGpgKeyImport(
    imported: GeneratedGpgKey,
): GeneratedGpgKey {
    val sameFingerprint = fingerprint.normalizeGpgFingerprint()
        .takeIf { it.isNotEmpty() }
        ?.let { current ->
            imported.fingerprint.normalizeGpgFingerprint()
                .takeIf { it.isNotEmpty() }
                ?.let { importedFingerprint -> importedFingerprint == current }
        } == true
    return GeneratedGpgKey(
        privateKeyArmored = when {
            imported.privateKeyArmored.isNotBlank() -> imported.privateKeyArmored
            sameFingerprint -> privateKeyArmored
            else -> ""
        },
        publicKeyArmored = imported.publicKeyArmored.ifBlank {
            if (sameFingerprint) publicKeyArmored else ""
        },
        fingerprint = imported.fingerprint.ifBlank {
            if (sameFingerprint) fingerprint else ""
        },
        metadata = imported.metadata ?: metadata.takeIf { sameFingerprint },
        userId = imported.userId.ifBlank {
            if (sameFingerprint) userId else ""
        },
        typeLabel = imported.typeLabel.ifBlank {
            if (sameFingerprint) typeLabel else ""
        },
    )
}
