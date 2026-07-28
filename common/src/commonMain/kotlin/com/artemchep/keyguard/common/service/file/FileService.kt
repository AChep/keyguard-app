package com.artemchep.keyguard.common.service.file

import kotlinx.io.Sink
import kotlinx.io.Source

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
     * Atomically publishes [bytes] at [uri]. A convenience for the streaming
     * [atomicWriteToFile] overload, which defines the contract.
     */
    fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken? = null,
        bytes: ByteArray,
    ): Boolean = atomicWriteToFile(
        uri = uri,
        accessToken = accessToken,
    ) { sink ->
        sink.write(bytes)
    }

    /**
     * Atomically publishes streamed content at [uri] when the backend can
     * stage it and replace the destination without ever truncating it in
     * place. The backend must invoke [write] only after it has secured an
     * atomic staging destination.
     *
     * Returns `true` when the destination was replaced atomically. Returns
     * `false` only when the backend knows before touching the destination
     * that it cannot provide an atomic publish; [write] was not invoked and
     * the destination was untouched in that case. If the backend starts an
     * atomic publish and then fails, it throws so callers do not silently
     * degrade to an in-place overwrite after a partially-failed safe-save.
     */
    fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken? = null,
        write: (Sink) -> Unit,
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
