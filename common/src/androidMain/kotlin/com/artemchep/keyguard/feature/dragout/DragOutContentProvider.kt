package com.artemchep.keyguard.feature.dragout

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ProxyFileDescriptorCallback
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

/** Exposes only authorized prepared bytes through read-only, seekable proxy descriptors. */
class DragOutContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    // Newer framework transports clear Binder identity before asking for an anonymous MIME type.
    override fun getTypeAnonymous(uri: Uri): String? = null

    override fun getType(uri: Uri): String {
        val appContext = authorizedContext(uri)
        return DragOutContentRegistry.metadata(appContext, uri).mimeType
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val appContext = authorizedContext(uri)
        val metadata = DragOutContentRegistry.metadata(appContext, uri)
        val columns = (projection?.toList() ?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.distinct()
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) metadata.name else metadata.size })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = openFile(uri, mode, null)

    // The descriptor callback owns its admitted lease, including every failed opening path.
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    override fun openFile(uri: Uri, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val appContext = authorizedContext(uri)
        if (mode != "r") throw FileNotFoundException("Content is read-only")
        signal?.throwIfCanceled()
        val lease = DragOutContentRegistry.acquire(appContext, uri)
        var callback: ReaderCallback? = null
        var descriptor: ParcelFileDescriptor? = null
        try {
            callback = ReaderCallback(lease)
            val storage = appContext.getSystemService(StorageManager::class.java) ?: throw unavailable()
            descriptor = storage.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, callback.handler,
            )
            signal?.throwIfCanceled()
            return descriptor
        } catch (failure: Throwable) {
            try {
                try {
                    descriptor?.close()
                } catch (cleanupFailure: Throwable) {
                    cleanupFailure.throwIfDragOutFatal()
                }
            } finally {
                when {
                    callback == null -> lease.close()
                    // Without a descriptor, the framework never calls the callback's onRelease.
                    descriptor == null -> callback.unbind()
                    else -> callback.release()
                }
            }
            if (failure is android.os.OperationCanceledException) throw failure
            failure.throwIfDragOutFatal()
            throw unavailable()
        }
    }

    // Canonical URI shape and the external UID's exact read grant are independent security guards.
    @Suppress("ThrowsCount")
    private fun authorizedContext(uri: Uri): Context {
        val appContext = context ?: throw unavailable()
        if (dragOutToken(uri.toString(), dragOutAuthority(appContext.packageName)) == null) throw unavailable()
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && appContext.checkUriPermission(
                uri, Binder.getCallingPid(), uid, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            ) != PackageManager.PERMISSION_GRANTED
        ) throw SecurityException("Content read permission is required")
        return appContext
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    /**
     * The recipient alone decides when to close its descriptor. A descriptor left idle
     * loses its reader, so it no longer holds the content, its reader slot, or its storage,
     * and its thread may serve another reader; only this callback remains until [onRelease].
     */
    private class ReaderCallback(
        private val lease: DragOutContentRegistry.DescriptorLease,
    ) : ProxyFileDescriptorCallback() {
        private val released = AtomicBoolean()
        private val binding = ReaderThreads.bind()
        val handler = binding.handler
        private val idleCheck = Runnable(::checkIdle)
        // Confined to the reader thread, like every callback below.
        private var failed = false
        private var lastActivity = SystemClock.elapsedRealtime()

        init {
            handler.postDelayed(idleCheck, IDLE_LIMIT_MILLIS)
        }

        override fun onGetSize(): Long {
            if (released.get() || failed) throw ErrnoException("size", OsConstants.EIO)
            return lease.size
        }

        // Invalid ranges and unavailable readers fail before I/O; backing errors become redacted EIO.
        @Suppress("TooGenericExceptionCaught", "ThrowsCount")
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (offset < 0 || size < 0 || size > data.size) throw ErrnoException("read", OsConstants.EINVAL)
            if (released.get() || failed) throw ErrnoException("read", OsConstants.EIO)
            lastActivity = SystemClock.elapsedRealtime()
            return try {
                val count = lease.reader.readAt(offset, data, endIndex = size)
                val expected = if (offset >= lease.size) 0 else minOf(size.toLong(), lease.size - offset).toInt()
                check(count == expected)
                count
            } catch (failure: Throwable) {
                data.fill(0, 0, size)
                failed = true
                binding.stopReading()
                try {
                    lease.reader.close()
                } catch (cleanupFailure: Throwable) {
                    cleanupFailure.throwIfDragOutFatal()
                }
                failure.throwIfDragOutFatal()
                throw ErrnoException("read", OsConstants.EIO)
            }
        }

        override fun onRelease() = unbind()

        @Suppress("TooGenericExceptionCaught")
        private fun checkIdle() {
            if (released.get() || failed) return
            val idle = SystemClock.elapsedRealtime() - lastActivity
            if (idle < IDLE_LIMIT_MILLIS) {
                handler.postDelayed(idleCheck, IDLE_LIMIT_MILLIS - idle)
                return
            }
            failed = true
            binding.stopReading()
            try {
                lease.close()
            } catch (failure: Throwable) {
                failure.throwIfDragOutFatal()
                Log.w("DragOutContent", "Could not release an idle content reader")
            }
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            if (Looper.myLooper() == handler.looper) closeLease() else handler.post(::closeLease)
        }

        /** Ends the callback once the framework will never call it again. */
        fun unbind() {
            release()
            binding.unbind()
        }

        @Suppress("TooGenericExceptionCaught")
        private fun closeLease() {
            handler.removeCallbacks(idleCheck)
            binding.stopReading()
            try {
                lease.close()
            } catch (failure: Throwable) {
                failure.throwIfDragOutFatal()
                Log.w("DragOutContent", "Could not release content reader")
            }
        }
    }

    private fun unavailable() = FileNotFoundException("Content is unavailable")
}

/**
 * Gives each open reader a thread of its own, so a slow read never delays another
 * descriptor. A descriptor stays bound to its thread until the framework releases it,
 * since [ProxyFileDescriptorCallback.onRelease] arrives there; a thread quits once no
 * descriptor is bound to it. The pool only grows while every thread has a reader, so
 * the content store's reader quota bounds it.
 */
private object ReaderThreads {
    private val lock = Any()
    private val threads = mutableListOf<ReaderThread>()

    class ReaderThread(val handler: Handler) {
        var reading = false
        var bindings = 0
    }

    /** One descriptor's hold on a thread: exclusive while it reads, shared until it unbinds. */
    class Binding(private val thread: ReaderThread) {
        val handler get() = thread.handler
        private var reading = true
        private var bound = true

        /** Without a reader every callback fails fast, so the thread may serve another reader. */
        fun stopReading() = synchronized(lock) {
            if (reading) {
                reading = false
                thread.reading = false
            }
        }

        /** Due messages, such as a posted release, still run before the thread quits. */
        fun unbind() {
            synchronized(lock) {
                if (!bound) return
                bound = false
                thread.bindings -= 1
                if (thread.bindings > 0) return
                threads.remove(thread)
            }
            thread.handler.looper.quitSafely()
        }
    }

    fun bind(): Binding = synchronized(lock) {
        val thread = threads.firstOrNull { !it.reading }
            ?: ReaderThread(Handler(HandlerThread("drag-out-content-reader").apply { start() }.looper))
                .also(threads::add)
        thread.reading = true
        thread.bindings += 1
        Binding(thread)
    }
}

/** Matches the Windows transport: a reader untouched this long is severed. */
private const val IDLE_LIMIT_MILLIS = 5L * 60L * 1000L
