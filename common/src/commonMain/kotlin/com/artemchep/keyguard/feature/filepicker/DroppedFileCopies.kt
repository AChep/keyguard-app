package com.artemchep.keyguard.feature.filepicker

import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.deleteDroppedCopy
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Holds the files a screen works with, so that the copies drops leave in
 * app storage are deleted once the screen is done with them: when a file
 * is released or replaced, and when the screen closes. Work that may
 * outlive the screen, such as a save, takes a [lease] to keep the files
 * until it ends.
 *
 * Any file can be held: only the app's own copies are ever deleted, so a
 * screen does not need to know how a file arrived.
 */
class DroppedFileCopies(
    private val deleteCopy: (String) -> Unit,
) {
    private val lock = SynchronizedObject()

    // Held by hold() until release().
    private val held = mutableSetOf<String>()

    // Held by the latest sync().
    private val synced = mutableSetOf<String>()
    private val handedOff = mutableSetOf<String>()

    // Released while a lease was active.
    private val pending = mutableSetOf<String>()
    private var leases = 0
    private var closed = false

    private fun isKept(uri: String) = uri in held || uri in synced || uri in handedOff

    fun hold(uri: String) = mutate {
        when {
            uri in handedOff -> emptyList()
            closed -> listOf(uri)
            else -> {
                held += uri
                pending -= uri
                emptyList()
            }
        }
    }

    fun release(uri: String) = mutate {
        val wasHeld = held.remove(uri) or synced.remove(uri)
        if (wasHeld && !isKept(uri)) listOf(uri) else emptyList()
    }

    /**
     * Holds exactly [uris] as the files of the screen's current state,
     * releasing the ones an earlier call held. Unlike [hold], a later
     * call replaces the set, so it suits a state that is read from a
     * flow and may lag behind.
     */
    fun sync(uris: Set<String>) = mutate {
        val previous = synced.toList()
        synced.clear()
        val added = uris.filter { it !in handedOff }
        if (closed) {
            previous + added
        } else {
            synced += added
            pending.removeAll(added.toSet())
            previous.filter { !isKept(it) }
        }
    }

    /** Deletes [uri] unless the screen holds it, for a file it turned down. */
    fun discard(uri: String) = mutate {
        if (isKept(uri)) emptyList() else listOf(uri)
    }

    /** Someone else owns [uri] from now on: it is never deleted here. */
    fun handOff(uri: String) = mutate {
        held -= uri
        synced -= uri
        pending -= uri
        handedOff += uri
        emptyList()
    }

    /**
     * Keeps every file until the returned function is called. Take
     * it before starting the work, so the screen can not close first.
     */
    fun lease(): () -> Unit {
        synchronized(lock) { leases++ }
        var released = false
        return {
            mutate {
                if (!released) {
                    released = true
                    leases--
                }
                emptyList()
            }
        }
    }

    fun close() = mutate {
        closed = true
        (held + synced).toList()
            .also {
                held.clear()
                synced.clear()
            }
    }

    /**
     * Runs [block] under the lock, then deletes the files it released,
     * together with the ones that waited for a lease, once none is active.
     */
    private inline fun mutate(block: () -> List<String>) {
        val uris = synchronized(lock) {
            pending += block()
            if (leases > 0) {
                emptyList()
            } else {
                pending.toList()
                    .also { pending.clear() }
            }
        }
        uris.forEach(deleteCopy)
    }
}

/**
 * Creates the [DroppedFileCopies] of this screen, released
 * once the screen closes.
 */
fun RememberStateFlowScope.droppedFileCopies(
    fileService: FileService,
): DroppedFileCopies {
    val scope = appScope
    val copies = DroppedFileCopies { uri ->
        scope.launch {
            fileService.deleteDroppedCopy(uri)
        }
    }
    screenScope.coroutineContext.job.invokeOnCompletion {
        if (isScreenClosed) {
            copies.close()
        }
    }
    return copies
}
