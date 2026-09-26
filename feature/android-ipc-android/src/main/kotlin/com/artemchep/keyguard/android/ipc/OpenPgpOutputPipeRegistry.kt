package com.artemchep.keyguard.android.ipc

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.Closeable

internal class OpenPgpOutputPipeRegistry : Closeable {
    private companion object {
        const val SWEEP_INTERVAL_MS = 1_000L
    }

    private val store = OpenPgpOutputPipeStore<ParcelFileDescriptor>(
        maxPerUid = 8,
        maxGlobal = 32,
        lifetimeMs = 60_000L,
        elapsedNow = SystemClock::elapsedRealtime,
    )
    private val handler = Handler(Looper.getMainLooper())

    // The sweep only matters for pipes whose owner never returns; keep it
    // scheduled only while entries exist instead of waking up forever.
    private val sweep = object : Runnable {
        override fun run() {
            store.sweepExpired()
            if (!store.isEmpty()) {
                handler.postDelayed(this, SWEEP_INTERVAL_MS)
            }
        }
    }

    fun create(
        caller: AndroidIpcCaller,
        pipeId: Int,
    ): ParcelFileDescriptor? = store.create(
        uid = caller.uid,
        pid = caller.pid,
        pipeId = pipeId,
        createPipe = {
            val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
            readSide to writeSide
        },
    )?.also {
        handler.removeCallbacks(sweep)
        handler.postDelayed(sweep, SWEEP_INTERVAL_MS)
    }

    fun take(
        caller: AndroidIpcCaller,
        pipeId: Int,
    ): ParcelFileDescriptor? = store.take(
        uid = caller.uid,
        pid = caller.pid,
        pipeId = pipeId,
    )

    fun discard(
        caller: AndroidIpcCaller,
        pipeId: Int,
    ) = discard(
        uid = caller.uid,
        pid = caller.pid,
        pipeId = pipeId,
    )

    fun discard(
        uid: Int,
        pid: Int,
        pipeId: Int,
    ) = store.discard(
        uid = uid,
        pid = pid,
        pipeId = pipeId,
    )

    override fun close() {
        handler.removeCallbacks(sweep)
        store.close()
    }
}

internal class OpenPgpOutputPipeStore<T : Closeable>(
    private val maxPerUid: Int,
    private val maxGlobal: Int,
    private val lifetimeMs: Long,
    private val elapsedNow: () -> Long,
) : Closeable {
    private data class Key(
        val uid: Int,
        val pid: Int,
        val pipeId: Int,
    )

    private data class Entry<T>(
        val writeSide: T,
        val expiresAtElapsedMs: Long,
    )

    private val entries = LinkedHashMap<Key, Entry<T>>()

    @Synchronized
    fun create(
        uid: Int,
        pid: Int,
        pipeId: Int,
        createPipe: () -> Pair<T, T>,
    ): T? {
        sweepLocked()
        val key = Key(uid, pid, pipeId)
        val hasCapacity = pipeId > 0 &&
                key !in entries &&
                entries.size < maxGlobal &&
                entries.keys.count { it.uid == uid } < maxPerUid
        val pipe = if (hasCapacity) {
            runCatching(createPipe).getOrNull()
        } else {
            null
        }
        return pipe?.let { (readSide, writeSide) ->
            entries[key] = Entry(
                writeSide = writeSide,
                expiresAtElapsedMs = elapsedNow() + lifetimeMs,
            )
            readSide
        }
    }

    @Synchronized
    fun take(
        uid: Int,
        pid: Int,
        pipeId: Int,
    ): T? {
        sweepLocked()
        return entries
            .remove(Key(uid, pid, pipeId))
            ?.writeSide
    }

    @Synchronized
    fun discard(
        uid: Int,
        pid: Int,
        pipeId: Int,
    ) {
        sweepLocked()
        entries
            .remove(Key(uid, pid, pipeId))
            ?.writeSide
            ?.closeQuietly()
    }

    @Synchronized
    override fun close() {
        entries.values.forEach { it.writeSide.closeQuietly() }
        entries.clear()
    }

    @Synchronized
    fun sweepExpired() {
        sweepLocked()
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    private fun sweepLocked() {
        val now = elapsedNow()
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.expiresAtElapsedMs <= now) {
                iterator.remove()
                entry.writeSide.closeQuietly()
            }
        }
    }
}

internal fun Closeable?.closeQuietly() {
    runCatching { this?.close() }
}
