package com.artemchep.desktop

/** Worker callbacks retained by AppKit until the drag offer retires. */
public interface MacFilePromiseCallbacks : NativeDragCallbacks {
    /**
     * Opens an independent positional reader for file [index]. Called only by a native
     * worker, which it may block until the file is prepared.
     */
    public fun openReader(index: Int): MacFilePromiseReader

    /**
     * Reports that file [index] is complete at its destination: every byte written,
     * synced and closed. A failed write is never reported. Called by the native worker
     * that wrote the file, possibly concurrently with [ended] on AppKit's main thread.
     */
    public fun written(index: Int)
}

/** A blocking positional reader owned and closed by one promised-file writer. */
public interface MacFilePromiseReader : AutoCloseable {
    /** The exact byte count the writer must receive. */
    public val size: Long

    /** Reads up to [length] bytes at [offset] into data[0, length). Returns the count; 0 means EOF. */
    public fun readAt(offset: Long, data: ByteArray, length: Int): Int

    override fun close()
}

/** Bump with `ABI_VERSION` in file_promise.rs for an incompatible change. */
internal const val MAC_FILE_PROMISE_ABI_VERSION = 5

/**
 * Starts one AppKit file-promise drag of files called [names] from an NSWindow handle, without
 * producer file URLs. Only names are needed up front: each file's bytes and size come from its reader.
 * Native keeps the offer until [NativeDragSession.revoke] or a rejected drag.
 * Null means native did not take ownership and no callback will run.
 */
public fun startMacFilePromises(
    windowHandle: Long,
    names: List<String>,
    callbacks: MacFilePromiseCallbacks,
): NativeDragSession? {
    require(windowHandle != 0L)
    require(isValidMacFilePromiseNames(names))
    macFilePromiseLibrary.ensureLoaded()
    val handle = MacFilePromiseJni.start(
        windowHandle = windowHandle,
        names = names.toTypedArray(),
        callbacks = callbacks,
    )
    return if (handle != 0L) NativeDragHandle(handle, MacFilePromiseJni::revoke) else null
}

/**
 * Whether one drag can carry files called [names]. Names that differ only in case would
 * claim one destination on a case-insensitive volume. Fails fast before the bridge loads;
 * mirrors `kg_promise_names` in file_promise.m, the native authority.
 */
internal fun isValidMacFilePromiseNames(names: List<String>): Boolean =
    names.size in 1..NATIVE_DRAG_MAX_FILES &&
        names.all(::isValidMacFilePromiseName) &&
        names.distinctBy { it.lowercase() }.size == names.size

/** Whether AppKit can create [name] as one path component. Mirrors `kg_promise_names` in file_promise.m. */
internal fun isValidMacFilePromiseName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && '\u0000' !in name && '/' !in name

private val macFilePromiseLibrary = nativeDragLibrary(
    platform = "macOS",
    expectedAbiVersion = MAC_FILE_PROMISE_ABI_VERSION,
    abiVersion = MacFilePromiseJni::abiVersion,
)

internal object MacFilePromiseJni {
    external fun abiVersion(): Int

    /** Returns a positive offer handle, or zero when native did not take ownership. */
    external fun start(
        windowHandle: Long,
        names: Array<String>,
        callbacks: MacFilePromiseCallbacks,
    ): Long

    /** Ignores unknown, stale and already revoked handles. Handles are never reused. */
    external fun revoke(handle: Long)
}
