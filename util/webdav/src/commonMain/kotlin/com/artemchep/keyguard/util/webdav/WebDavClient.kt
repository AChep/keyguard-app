package com.artemchep.keyguard.util.webdav

import kotlinx.io.Sink
import kotlinx.io.Source

interface WebDavClient {
    suspend fun open(): WebDavOpenResult

    suspend fun stat(
        path: String,
    ): WebDavResource?

    suspend fun read(
        path: String,
        range: WebDavByteRange? = null,
    ): Source

    suspend fun write(
        path: String,
        mode: WebDavWriteMode = WebDavWriteMode.CreateOrReplace,
        bytes: ByteArray,
        precondition: WebDavWritePrecondition? = null,
    ): WebDavResource

    /**
     * The [write] callback may be invoked more than once when the client
     * has to retry or degrade the upload flow, so it must be able to
     * produce the same payload again on every invocation.
     */
    suspend fun write(
        path: String,
        mode: WebDavWriteMode = WebDavWriteMode.CreateOrReplace,
        contentLength: Long? = null,
        precondition: WebDavWritePrecondition? = null,
        write: suspend (Sink) -> Unit,
    ): WebDavResource

    suspend fun list(
        prefix: String,
    ): List<WebDavResource>

    /**
     * Lists the immediate children of [collectionPath].
     *
     * Unlike [list], this operation is non-recursive and includes both files
     * and collections. The path is relative to the configured base URL; an
     * empty path addresses the base collection itself. An existing collection
     * without children returns an empty list, while a missing collection throws
     * [WebDavException.NotFound].
     */
    suspend fun listChildren(
        collectionPath: String,
    ): List<WebDavResource>

    suspend fun delete(
        path: String,
    )

    suspend fun close() {
        // no-op by default
    }
}
