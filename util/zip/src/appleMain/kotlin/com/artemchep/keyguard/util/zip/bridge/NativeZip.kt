package com.artemchep.keyguard.util.zip.bridge

/**
 * The Apple-side surface of the native zip writer and reader (ABI v1).
 *
 * Every function but [abiVersion] returns a packed scalar: `0` is success, a
 * positive value is the payload, and a negative value is a failure decoded by
 * [decodeNativeZipFailure]. An argument rejected before dispatch answers
 * [NATIVE_ZIP_BRIDGE_INVALID_ARGUMENT] rather than throwing. An empty
 * password means "no password" in both directions.
 *
 * Lives in `appleMain` because the JVM uses zip4j and never crosses this
 * bridge.
 */
internal expect object NativeZip {
    fun abiVersion(): Int

    /** Creates or truncates an archive and returns its handle. */
    fun open(path: String, password: String): Long

    fun beginEntry(handle: Long, name: String): Long

    fun write(handle: Long, bytes: ByteArray, offset: Int, count: Int): Long

    fun endEntry(handle: Long): Long

    /** Consumes [handle] on every result. */
    fun finish(handle: Long): Long

    /** Consumes [handle] on every result. */
    fun abort(handle: Long): Long

    /** Opens an existing archive for reading and returns its handle. */
    fun readerOpen(path: String, password: String): Long

    /**
     * Writes the next entry's UTF-8 name into [nameBuffer] and returns its
     * length, or [NATIVE_ZIP_END_OF_ARCHIVE]. A `BUFFER_TOO_SMALL` failure
     * leaves the reader positioned before the entry.
     */
    fun readerNextEntry(handle: Long, nameBuffer: ByteArray): Long

    /** Returns the bytes read; `0` marks the end of the entry. */
    fun readerRead(handle: Long, buffer: ByteArray, offset: Int, count: Int): Long

    /** Consumes [handle] on every result; the file is left in place. */
    fun readerClose(handle: Long): Long
}
