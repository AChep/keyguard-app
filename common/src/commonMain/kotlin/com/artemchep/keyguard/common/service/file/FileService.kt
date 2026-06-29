package com.artemchep.keyguard.common.service.file

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.time.Instant

interface FileService {
    fun exists(uri: String): Boolean

    fun exists(
        uri: String,
        accessToken: FileAccessToken?,
    ): Boolean = exists(uri)

    fun metadata(
        uri: String,
        accessToken: FileAccessToken? = null,
    ): FileMetadata? = null

    fun readFromFile(uri: String): Source

    fun readFromFile(
        uri: String,
        accessToken: FileAccessToken?,
    ): Source = readFromFile(uri)

    fun writeToFile(uri: String): Sink

    fun writeToFile(
        uri: String,
        accessToken: FileAccessToken?,
    ): Sink = writeToFile(uri)

    /**
     * Atomically publishes [bytes] at [uri] when the backend can stage bytes
     * and replace the destination without ever truncating it in place.
     *
     * Returns `true` when the destination was replaced atomically. Returns
     * `false` only when the backend knows before touching the destination that
     * it cannot provide an atomic publish. If the backend starts an atomic
     * publish and then fails, it should throw so callers do not silently
     * degrade to an in-place overwrite after a partially-failed safe-save.
     */
    fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken? = null,
        bytes: ByteArray,
    ): Boolean = false

    /**
     * Deletes the single resource addressed by [uri].
     *
     * This does not promise recursive directory deletion. Backends may delete an
     * empty directory if their underlying filesystem/provider supports it, but
     * non-empty directory cleanup must use a separate, explicit API.
     */
    fun delete(uri: String): Boolean

    fun deleteManagedSourceFile(uri: String): Boolean = false

    /**
     * Atomically replaces [destinationUri] with [sourceUri] on the same
     * volume (a rename-over operation).
     *
     * Returns `true` when the move was performed atomically. Returns
     * `false` when the platform or the URI scheme cannot perform an
     * atomic rename.
     */
    fun atomicMove(
        sourceUri: String,
        destinationUri: String,
        accessToken: FileAccessToken? = null,
    ): Boolean = false
}

